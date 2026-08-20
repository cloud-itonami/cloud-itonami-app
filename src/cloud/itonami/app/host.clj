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
  (:import [java.nio.file Files StandardCopyOption]))

(def ^:private default-max-bytes (* 16 1024 1024))

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

(defn write-atomic!
  "Write `content` to `file` via a confined sibling tmp + atomic rename.

  The parent directory is the jail root, so the relative name cannot escape."
  [^java.io.File file content]
  (let [parent (.getParentFile file)
        _ (when parent (.mkdirs parent))
        root (.getCanonicalPath (or parent (io/file ".")))
        handle (filesystem-at root)
        name (.getName file)
        tmp (str name ".tmp")]
    (fs/write handle tmp (str content))
    (Files/move (.toPath (io/file parent tmp))
                (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
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
