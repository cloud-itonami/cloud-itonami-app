(ns cloud.itonami.app.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.policy :as policy]))

(def ^:private data-dir-property "cloud.itonami.data-dir")

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
  the whole `:identity` partition."
  []
  (.getCanonicalFile
   (io/file (or (System/getProperty data-dir-property)
                (System/getenv "CLOUD_ITONAMI_DATA_DIR")
                "data"))))

(defn- deep-merge
  ([a b]
   (merge-with (fn [x y]
                 (if (and (map? x) (map? y))
                   (deep-merge x y)
                   y))
               a b))
  ([a b & more]
   (reduce deep-merge (deep-merge a b) more)))

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

(defn load-config []
  (let [defaults (-> "cloud-itonami-app.defaults.edn" io/resource slurp edn/read-string)
        override-file (io/file (data-dir) "config.edn")
        profile (or (profile-overrides) {})
        overrides (or (read-edn-file override-file) {})
        provider-overrides (into {} (map (juxt :id identity)
                                         (:providers overrides)))
        providers (mapv #(deep-merge % (get provider-overrides (:id %) {}))
                        (:providers defaults))
        config (assoc (deep-merge defaults profile
                                  (dissoc overrides :providers))
                      :providers providers)
        host (get-in config [:server :host])]
    (when (and (get-in config [:privacy :bind-loopback-only?])
               (not (policy/loopback-host? host)))
      (throw (ex-info "privacy policy requires a loopback server bind"
                      {:host host})))
    config))

(defn env-secret [provider]
  (let [env-name (:api-key-env provider)]
    (when-not (str/blank? env-name)
      (not-empty (System/getenv env-name)))))
