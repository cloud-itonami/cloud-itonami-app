(ns cloud.itonami.app.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cloud.itonami.app.config-policy :as policy-layer]
            [clojure.string :as str]
            [cloud.itonami.app.policy :as policy])
  (:import [java.security MessageDigest]))

(def ^:private data-dir-property "cloud.itonami.data-dir")

(defn- canonical ^java.io.File [file]
  (try (.getCanonicalFile (io/file file))
       (catch Exception _ (.getAbsoluteFile (io/file file)))))

(defn- app-directory
  "The install this process was started from.

  `CLOUD_ITONAMI_APP_DIR` when a launcher set it, otherwise the working
  directory -- the same two candidates `server-process/app-directory` reads,
  in the same order."
  []
  (canonical (or (some-> (System/getenv "CLOUD_ITONAMI_APP_DIR")
                         str/trim not-empty)
                 (System/getProperty "user.dir"))))

(defn- resident-data-dir
  "`~/.cloud-itonami/data` when this process IS the resident install, else nil.

  `~/.cloud-itonami/app` and `~/.cloud-itonami/data` are one installation, and
  `bin/itonami` already says so -- it exports `CLOUD_ITONAMI_DATA_DIR` before
  spawning the CLI. That covered commands run THROUGH the launcher and nothing
  else. A bare leftover JVM CLI or leftover JVM server in the same directory fell back
  to the relative `data` and created a THIRD store at
  `~/.cloud-itonami/app/data`, holding an enrollment key no running server had
  ever written. Measured 2026-08-20 on this machine: that directory existed,
  and the key in it was refused as `invalid-key` by the server on 1338.

  The rule belongs here and not only in the launcher, because 'which store is
  mine' is this namespace's question. A rule that only one of the three
  entrances applies is not a rule."
  []
  (when-let [home (some-> (System/getProperty "user.home") str/trim not-empty)]
    (let [resident (io/file home ".cloud-itonami")]
      (when (= (canonical (io/file resident "app")) (app-directory))
        (io/file resident "data")))))

(defn data-dir
  "Where this process keeps its store.

  The system property WINS over the environment variable, which is the reverse
  of the usual precedence and is deliberate. The property is set by a specific
  invocation -- the `:test` alias sets it to a throwaway directory -- while the
  environment variable is ambient and may be left over in a shell. If the
  environment won, running the suite in a terminal that happened to export
  CLOUD_ITONAMI_DATA_DIR would write test fixtures into a real store.

  That is not hypothetical. On 2026-07-30 the suite, run from the repository
  root where this defaults to `./data`, replaced a developer's real identity
  state with a test fixture -- an organization named `jk-corp`, a user with no
  email and no user handle -- which then made Passkey registration fail with a
  NullPointerException inside the WebAuthn builder. Eighteen `store/transact!`
  calls across the tests write through this function, and two of them replace
  the whole `:identity` partition.

  The resident install is resolved BEFORE the relative fallback and AFTER both
  explicit answers, so an operator who names a directory still gets it -- see
  `resident-data-dir` for the store that leg exists to stop being created."
  []
  (.getCanonicalFile
   (io/file (or (System/getProperty data-dir-property)
                (System/getenv "CLOUD_ITONAMI_DATA_DIR")
                (some-> (resident-data-dir) .getPath)
                "data"))))

(defn store-fingerprint
  "A short, stable name for a store that is not its path.

  Two processes need to settle one question -- 'are we opening the same store?'
  -- and neither needs the other's filesystem layout to answer it. `/health`
  publishes this instead of the directory because that route takes no session,
  and a path would name the operator's home directory to every caller that can
  reach the port.

  Of the canonical path, so the same directory reached through a symlink or a
  relative name fingerprints the same. Twelve hex characters: this distinguishes
  the two or three stores that exist on one machine, and is not a secret."
  ([] (store-fingerprint (data-dir)))
  ([dir]
   (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (.getPath (canonical dir)) "UTF-8"))]
     (subs (apply str (map #(format "%02x" (bit-and % 0xff)) digest)) 0 12))))

(def ^:private deep-merge policy-layer/deep-merge)

(defn- read-edn-file [file]
  (when (.isFile file)
    (edn/read-string (slurp file))))

(defn- profile-overrides []
  (when-let [profile (some-> (System/getenv "CLOUD_ITONAMI_PROFILE")
                             str/trim not-empty)]
    (let [direct (io/file profile)
          named (io/file "profiles" (str profile ".edn"))
          file (if (.isFile direct) direct named)]
      (when-not (.isFile file)
        (throw (ex-info "Cloud Itonami profile was not found."
                        {:profile profile
                         :path (.getPath file)})))
      (read-edn-file file))))

(def overlay-providers
  "See `config-policy/overlay-providers`. Re-exported because callers name it
  here, and because the layering is the part that had a silent failure -- it
  now lives where a test can reach it without a filesystem."
  policy-layer/overlay-providers)

(defn load-config []
  (let [defaults (-> "cloud-itonami-app.defaults.edn" io/resource slurp edn/read-string)
        override-file (io/file (data-dir) "config.edn")
        profile (or (profile-overrides) {})
        overrides (or (read-edn-file override-file) {})
        ;; The layering is `config-policy`'s; the environment, the directory
        ;; and the slurps above are this namespace's. The one validation below
        ;; stays here because it goes through the Kotoba oracle.
        config (policy-layer/compose defaults profile overrides)
        host (get-in config [:server :host])]
    (when (and (get-in config [:privacy :bind-loopback-only?])
               (not (policy/loopback-host? host)))
      (throw (ex-info "privacy policy requires a loopback server bind"
                      {:host host})))
    config))

(defn env-secret [provider]
  (when-let [env-name (policy-layer/secret-env-name provider)]
    (not-empty (System/getenv env-name))))
