(ns cloud.itonami.app.policy)

(defn loopback-host? [host]
  (contains? #{"127.0.0.1" "localhost" "::1"} host))

(defn provider-allowed?
  "The host-side mirror of policy.kotoba. Local providers are admitted by
  default. Cloud providers require both the global cloud gate and provider
  enablement; there is no implicit fallback from local to cloud."
  [config provider]
  (and (:enabled? provider)
       (or (:local? provider)
           (and (get-in config [:routing :cloud-enabled?])
                (get-in config [:privacy :allow-cloud-without-review?])))))

(defn select-provider [config requested-id]
  (let [provider-id (or requested-id
                        (get-in config [:routing :default-provider]))]
    (some #(when (and (= provider-id (:id %))
                      (provider-allowed? config %))
             %)
          (:providers config))))
