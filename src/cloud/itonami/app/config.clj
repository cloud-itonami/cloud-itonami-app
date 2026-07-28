(ns cloud.itonami.app.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.policy :as policy]))

(defn data-dir []
  (.getCanonicalFile
   (io/file (or (System/getenv "CLOUD_ITONAMI_DATA_DIR") "data"))))

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
