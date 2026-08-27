(ns cloud.itonami.app.host
  "Host-injected fs + process seams for this application.

  Ambient java.nio / ProcessBuilder stay out of product call sites that have
  been cut over. Roots and binary maps are granted here; cells only see
  IFilesystem / IProcess handles (kotoba-lang/fs, kotoba-lang/process).

  Catalog identities match aiueos host imports (ADR-0067): `:process/spawn`
  for spawn, `:fs/write` for confined durable write. Bind
  `*granted-capabilities*` when tendered under aiueos; nil keeps the legacy
  JVM desktop host."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.lang.fs :as fs]
            [kotoba.lang.fs-host :as fs-host]
            [kotoba.lang.process :as proc]
            [kotoba.lang.process-host :as proc-host])
  (:import [java.io FileOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]))

(def ^:private default-max-bytes
  "Bound for a confined write of a DOCUMENT -- content whose size is not ours
  to predict."
  (* 16 1024 1024))

(def store-max-bytes
  "Bound for the durable store writing its OWN state.

  These are different bounds and conflating them took the resident down. The
  document bound is a defence against content we did not author; the store
  file is content we author, and its size is a function of how much history
  the fleet has accumulated -- 28 MB and climbing on 2026-08-20, past a 16 MiB
  document bound, so every write was refused and the process could not start.
  Nothing untrusted has ever gone through this path: `write-atomic!` has
  exactly one caller, and it is the store.

  Still bounded, because unbounded is not the alternative to wrong. But this
  number does not fix the growth -- 670 runs are retained with full goals and
  never pruned, so the file will reach any ceiling eventually. Retention is
  the actual gap; this only stops the ceiling from being the wrong one."
  (* 256 1024 1024))

(def ^:dynamic *granted-capabilities*
  "When non-nil, a set of aiueos catalog capability keywords the caller holds.
  Nil = untendered legacy host (ADR-0067)."
  nil)

(defn require-cap!
  "Fail closed when a grant set is bound and `cap` is absent."
  [cap]
  (when-let [granted *granted-capabilities*]
    (when-not (contains? granted cap)
      (throw (ex-info (str "Host capability not granted: " cap)
                      {:type :host/capability-denied
                       :capability cap
                       :granted granted}))))
  nil)

(defn filesystem-at
  "IFilesystem confined to an existing absolute directory root."
  ([root] (filesystem-at root default-max-bytes))
  ([root max-bytes]
   (require-cap! :fs/write)
   (let [dir (io/file (str root))]
     (.mkdirs dir)
     (fs-host/host-filesystem
      {:root (.getCanonicalPath dir)
       :max-bytes max-bytes}))))

(def ^:const approaching-bound-fraction
  "Warn once a write is this far into its bound.

  0.75 is not a tuned number; it is a number that leaves room to act. What
  matters is that the warning exists at all."
  0.75)

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
  [^java.io.File file size max-bytes]
  (when (> size (* approaching-bound-fraction max-bytes))
    (let [pct (int (* 100 (/ (double size) max-bytes)))
          k [(.getPath file) (quot pct 10)]]
      (when-not (contains? @warned k)
        (swap! warned conj k)
        (binding [*out* *err*]
          (println (format (str "WARNING %s is %d%% of its %.0f MiB write bound "
                                "(%.1f MiB). At the bound the write is REFUSED and "
                                "a process that must write this file will not start. "
                                "This is the only notice before that.")
                           (.getName file) pct
                           (/ (double max-bytes) 1048576)
                           (/ (double size) 1048576))))))))

(defn write-atomic!
  "Write `content` to `file` via a confined sibling tmp + atomic rename.

  `max-bytes` defaults to the DOCUMENT bound. A caller writing its own state
  passes its own bound -- see `store-max-bytes`.

  The parent directory is the jail root, so the relative name cannot escape."
  ([^java.io.File file content] (write-atomic! file content default-max-bytes))
  ([^java.io.File file content max-bytes]
  (let [parent (.getParentFile file)
        _ (when parent (.mkdirs parent))
        root (.getCanonicalPath (or parent (io/file ".")))
        handle (filesystem-at root max-bytes)
        name (.getName file)
        tmp (str name ".tmp")
        body (str content)
        ;; Preflight the disk BEFORE the tmp write. On 2026-08-23 the disk
        ;; filled and thirteen resident turns died as an opaque `fs/io write
        ;; failed`; the one that kept its message said `No space left on
        ;; device` after the bytes were already half-written. Refusing here
        ;; loses nothing — the write was going to fail — and keeps the
        ;; provenance: a typed error carrying what was measured, which the
        ;; SLO surface can aggregate and `gc/sweep!` can act on. A probe
        ;; that answers 0 means 'unable to determine' and does NOT refuse:
        ;; the write itself is the better witness then.
        needed (+ (count body) (* 32 1024 1024))
        usable (try (.getUsableSpace (io/file root))
                    (catch Exception _ 0))]
    (when (and (pos? usable) (< usable needed))
      (throw (ex-info (str "disk pressure: refusing atomic write of "
                           (.getName file))
                      {:type :fs/disk-pressure
                       :file (.getPath file)
                       :usable-bytes usable
                       :needed-bytes needed})))
    (warn-approaching-bound! file (count body) max-bytes)
    (fs/write handle tmp body)
    (Files/move (.toPath (io/file parent tmp))
                (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    nil)))

(defn append-durable!
  "Append UTF-8 `content` to an app-owned file and fsync it before returning.

  Journals need a different durability primitive from snapshots: rewriting the
  snapshot for every small transaction is exactly the amplification a journal
  removes, while a buffered append without `sync` can acknowledge state that a
  power loss never records. The file is still confined to its declared parent
  and bounded by `max-bytes`."
  [^java.io.File file content max-bytes]
  (require-cap! :fs/write)
  (let [parent (.getParentFile file)
        _ (when parent (.mkdirs parent))
        body (.getBytes (str content) StandardCharsets/UTF_8)
        next-size (+ (.length file) (alength body))
        usable (try (.getUsableSpace (or parent (io/file ".")))
                    (catch Exception _ 0))]
    (when (> next-size max-bytes)
      (throw (ex-info (str "append exceeds durable file bound: " (.getName file))
                      {:type :fs/content-too-large
                       :file (.getPath file)
                       :bytes next-size
                       :max-bytes max-bytes})))
    (when (and (pos? usable) (< usable (+ (alength body) (* 4 1024 1024))))
      (throw (ex-info (str "disk pressure: refusing append to " (.getName file))
                      {:type :fs/disk-pressure
                       :file (.getPath file)
                       :usable-bytes usable
                       :needed-bytes (+ (alength body) (* 4 1024 1024))})))
    (with-open [stream (FileOutputStream. file true)]
      (.write stream body)
      (.flush stream)
      (.sync (.getFD stream)))
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
       :output (str (:stdout r)
                    (when (not (str/blank? (:stderr r)))
                      (str (when (not (str/blank? (:stdout r))) "\n")
                           (:stderr r))))}
      {:exit -1
       :output (str (or (:message r) (name (:code r))))})))
