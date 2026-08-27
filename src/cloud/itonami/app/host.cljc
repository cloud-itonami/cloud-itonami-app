(ns cloud.itonami.app.host
  "Host-injected fs + process seams for this application.

  Ambient java.nio / ProcessBuilder stay out of product call sites that have
  been cut over. Roots and binary maps are granted here; cells only see
  IFilesystem / IProcess handles (kotoba-lang/fs, kotoba-lang/process).

  Catalog identities match aiueos host imports (ADR-0067): `:process/spawn`
  for spawn, `:fs/write` for confined durable write. Bind
  `*granted-capabilities*` when tendered under aiueos; nil keeps the legacy
  JVM desktop host.

  ## Portable since 2026-08-27

  `kotoba.lang.fs-host` and `kotoba.lang.process-host` were already `.cljc`
  with working `:cljs` branches -- measured, not assumed: a ClojureScript
  `host-filesystem` writes, reads, and refuses `../` with `:fs/escape`. This
  namespace was the `.clj` on top of them, and it is what
  `cloud.itonami.app.store` reaches for its durable write.

  Five host operations remain, and they are the only reader-conditional code
  here: make a directory, rename atomically, ask how much disk is left, ask how
  long a file is, append-and-fsync. Every DECISION -- whether a write is near
  its bound, whether the disk is too full to start, whether a journal belongs
  to the snapshot beside it, what the warning says -- is a pure function above
  them, and is tested on both runtimes."
  (:require [clojure.string :as str]
            [cloud.itonami.app.host-bounds :as host-bounds]
            [kotoba.lang.fs :as fs]
            [kotoba.lang.fs-host :as fs-host]
            [kotoba.lang.process :as proc]
            [kotoba.lang.process-host :as proc-host])
  #?(:clj (:import [java.nio.file Files StandardCopyOption])
     :cljs (:require ["node:fs" :as node-fs]
                     ["node:path" :as node-path])))

(def default-max-bytes host-bounds/default-max-bytes)
(def store-max-bytes host-bounds/store-max-bytes)
(def approaching-bound-fraction host-bounds/approaching-bound-fraction)
(def disk-headroom-bytes host-bounds/disk-headroom-bytes)
(def journal-max-bytes host-bounds/journal-max-bytes)
(def journal-max-entries host-bounds/journal-max-entries)

;; The decisions live in `host-bounds`, which depends on NOTHING -- not on this
;; namespace's filesystem libraries, not on a host. That is what lets
;; `bin/test-portable-cljs` run them: it grants no classpath beyond `src` and
;; `test` on purpose, because a portable judgement that needed a resolved
;; dependency tree to run would not be very portable. Re-exported here so the
;; callers that say `host/store-max-bytes` keep working.
(def ^:dynamic *granted-capabilities*
  "When non-nil, a set of aiueos catalog capability keywords the caller holds.
  Nil = untendered legacy host (ADR-0067).

  Stays HERE, where every caller already binds it. `host-bounds/require-cap!`
  takes the set as an argument instead of reading a var of its own -- see its
  docstring for what happened when it did not."
  nil)

(defn require-cap!
  "Fail closed when a grant set is bound and `cap` is absent."
  [cap]
  (host-bounds/require-cap! *granted-capabilities* cap))
(def bound-warning host-bounds/bound-warning)
(def disk-pressure? host-bounds/disk-pressure?)

;; ---------- the three host operations ----------
;;
;; Everything below this block is pure. These three are the only places this
;; namespace touches an OS, and each is one call on each runtime.

(defn ensure-directory!
  "Create PATH and its parents if absent; answer its canonical form.

  `fs-host/host-filesystem` requires an EXISTING directory as its root, so this
  runs before a handle can be granted."
  [path]
  #?(:clj (let [dir (java.io.File. (str path))]
            (.mkdirs dir)
            (.getCanonicalPath dir))
     :cljs (do (node-fs/mkdirSync (str path) #js {:recursive true})
               (node-fs/realpathSync (str path)))))

(defn atomic-rename!
  "Replace TO with FROM in one step, so no reader sees a partial document."
  [from to]
  #?(:clj (Files/move (.toPath (java.io.File. ^String (str from)))
                      (.toPath (java.io.File. ^String (str to)))
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING
                                   StandardCopyOption/ATOMIC_MOVE]))
     ;; `rename(2)` within one filesystem is atomic and REPLACE_EXISTING is its
     ;; default, which is the same guarantee the JVM call above asks for.
     :cljs (node-fs/renameSync (str from) (str to)))
  nil)

(defn usable-space
  "Bytes free under PATH, or 0 for 'unable to determine'.

  0 is a real answer with a defined meaning downstream: the preflight below
  does NOT refuse on it, because a probe that could not measure is worse
  evidence than the write itself."
  [path]
  (try
    #?(:clj (.getUsableSpace (java.io.File. ^String (str path)))
       ;; `statfsSync` is Node 18.15+. Older hosts answer 0, which is the
       ;; documented 'unable to determine', not a failure.
       :cljs (if (exists? node-fs/statfsSync)
               (let [st (node-fs/statfsSync (str path))]
                 (* (.-bsize st) (.-bavail st)))
               0))
    (catch #?(:clj Exception :cljs :default) _ 0)))

(defn file-size
  "Bytes on disk at PATH, or 0 when it does not exist.

  Journalling needs this per append, and reading the file to count it would
  make every small transaction pay for the whole journal -- the amplification
  the journal exists to remove."
  [path]
  (try
    #?(:clj (let [f (java.io.File. ^String (str path))]
              (if (.isFile f) (.length f) 0))
       :cljs (if (node-fs/existsSync (str path))
               (.-size (node-fs/statSync (str path)))
               0))
    (catch #?(:clj Exception :cljs :default) _ 0)))

(defn append-sync!
  "Append UTF-8 CONTENT to PATH and fsync before returning.

  A journal needs a different primitive from a snapshot. `write-atomic!`
  replaces a whole file, which is what a journal is there to avoid; and a
  buffered append without an fsync acknowledges state that a power loss never
  recorded. So this is the fourth host operation, and like the other three it
  is one call on each runtime with every decision made above it."
  [path content]
  #?(:clj (with-open [stream (java.io.FileOutputStream. ^String (str path) true)]
            (.write stream (.getBytes ^String (str content) "UTF-8"))
            (.flush stream)
            (.sync (.getFD stream)))
     :cljs (let [fd (node-fs/openSync (str path) "a")]
             (try
               (node-fs/writeSync fd (str content))
               (node-fs/fsyncSync fd)
               (finally (node-fs/closeSync fd)))))
  nil)

(defn filesystem-at
  "IFilesystem confined to an existing absolute directory root."
  ([root] (filesystem-at root default-max-bytes))
  ([root max-bytes]
   (require-cap! :fs/write)
   (fs-host/host-filesystem
    {:root (ensure-directory! root) :max-bytes max-bytes})))

(def ^:private warned (atom #{}))

(defn- warn-approaching-bound!
  "Say it BEFORE the write that cannot happen.

  On 2026-08-20 the resident died on startup because the store had grown past
  its bound. There was no prior signal: writes succeeded at 99% of the bound
  and the process failed to start at 101%. The first thing anyone learned was
  that the fleet was down, and the log said `content exceeds :max-bytes` --
  true, and useless, because by then the only way out was a code change.

  Warns once per (file, decile) so a busy writer does not fill the log while
  still reporting real movement toward the wall."
  [path name size max-bytes]
  (when-let [message (bound-warning name size max-bytes)]
    (let [pct (int (* 100 (/ (double size) max-bytes)))
          k [path (quot pct 10)]]
      (when-not (contains? @warned k)
        (swap! warned conj k)
        ;; `*print-fn*` is ClojureScript-only and `*err*` is JVM-only, so the
        ;; two ways of reaching stderr do not share a spelling.
        #?(:clj (binding [*out* *err*] (println message))
           :cljs (binding [*print-fn* *print-err-fn*] (println message)))))))

(defn write-atomic!
  "Write `content` to `path` via a confined sibling tmp + atomic rename.

  `max-bytes` defaults to the DOCUMENT bound. A caller writing its own state
  passes its own bound -- see `store-max-bytes`.

  The parent directory is the jail root, so the relative name cannot escape."
  ([path content] (write-atomic! path content default-max-bytes))
  ([path content max-bytes]
   (let [path (str path)
         parent (or (not-empty (fs/dirname path)) ".")
         name (fs/basename path)
         root (ensure-directory! parent)
         handle (filesystem-at root max-bytes)
         tmp (str name ".tmp")
         body (str content)]
     ;; Preflight the disk BEFORE the tmp write; see `disk-pressure?`.
     (let [usable (usable-space root)]
       (when (disk-pressure? usable (count body))
         (throw (ex-info (str "disk pressure: refusing atomic write of " name)
                         {:type :fs/disk-pressure
                          :file path
                          :usable-bytes usable
                          :needed-bytes (+ (count body) disk-headroom-bytes)}))))
     (warn-approaching-bound! path name (count body) max-bytes)
     (fs/write handle tmp body)
     (atomic-rename! (fs/join root tmp) (fs/join root name))
     nil)))

(defn append-durable!
  "Append one record to an app-owned journal, bounded and fsynced.

  Same two preflights as `write-atomic!` -- the file's own bound and the disk's
  free space -- decided by the same pure functions, so a journal cannot be the
  one write path that grows without a ceiling."
  [path content max-bytes]
  (require-cap! :fs/write)
  (let [path (str path)
        parent (or (not-empty (fs/dirname path)) ".")
        name (fs/basename path)
        root (ensure-directory! parent)
        body (str content)
        current (file-size path)]
    (when (host-bounds/append-exceeds-bound? current (count body) max-bytes)
      (throw (ex-info (str "append exceeds durable file bound: " name)
                      {:type :fs/content-too-large
                       :file path
                       :bytes (+ current (count body))
                       :max-bytes max-bytes})))
    (let [usable (usable-space root)]
      (when (disk-pressure? usable (count body))
        (throw (ex-info (str "disk pressure: refusing append to " name)
                        {:type :fs/disk-pressure
                         :file path
                         :usable-bytes usable
                         :needed-bytes (+ (count body) disk-headroom-bytes)}))))
    (warn-approaching-bound! path name (+ current (count body)) max-bytes)
    (append-sync! path body)
    nil))

(defn process
  "IProcess with an explicit basename→absolute-path binary map. No PATH."
  [binaries]
  (proc-host/os-spawn {:binaries binaries}))

(defn spawn!
  "Run `argv` (basename command) on `proc`. Returns
  `{:exit :output}` with stdout/stderr merged, matching legacy ProcessBuilder
  redirectErrorStream callers.

  Requires `:process/spawn` when `*granted-capabilities*` is bound."
  [proc argv & {:keys [timeout-ms max-stdout-bytes]
                :or {timeout-ms 600000 max-stdout-bytes 65536}}]
  (require-cap! :process/spawn)
  (let [r (proc/spawn! proc {:argv (vec argv)
                             :timeout-ms timeout-ms
                             :max-stdout-bytes max-stdout-bytes})]
    (if (= :ok (:tag r))
      {:exit (:exit r)
       :output (host-bounds/merge-output (:stdout r) (:stderr r))}
      {:exit -1
       :output (str (or (:message r) (name (:code r))))})))
