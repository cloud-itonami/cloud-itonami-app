(ns cloud.itonami.app.policy)

(defn loopback-host? [host]
  (contains? #{"127.0.0.1" "localhost" "::1"} host))

(defn provider-allowed?
  "Local providers are admitted by being enabled. Cloud providers require BOTH
  the global cloud gate and the privacy gate; there is no implicit fallback
  from local to cloud.

  The same decision is written in `policy.kotoba`, and
  `policy-kotoba-parity-test` runs both over all sixteen combinations of the
  four booleans. Until 2026-08-08 this docstring instead claimed to be \"the
  host-side mirror of policy.kotoba\" — which nothing checked, and which was
  not true: the two files shared no function at all. Change the rule here and
  the parity test fails until `policy.kotoba` agrees."
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
