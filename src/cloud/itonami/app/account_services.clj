(ns cloud.itonami.app.account-services
  "Per-account service entitlements. Allocation and remotely measured usage
  stay separate so an unavailable meter is never presented as zero usage."
  (:require [cloud.itonami.app.store :as store]))

(def schema "cloud.itonami.app.account-services.v1")

(defn- allocation [service user organization]
  {:service (:id service)
   :name (:name service)
   :base-url (:base-url service)
   :plan (:plan service)
   :quota-bytes (:quota-bytes service)
   :quota-pins (:quota-pins service)
   :quota-source (:quota-source service)
   :used-bytes nil
   :used-pins nil
   :usage-status :not-synced
   :enforcement-status (if (= :service-contract (:quota-source service))
                         :service-contract
                         :allocation-only)
   :account-id (:account-id user)
   :user-did (:did user)
   :organization-did (:did organization)
   :allocated-at (store/now)})

(defn ensure-allocations!
  "Idempotently allocate all configured services to one authenticated user."
  [configuration user organization]
  (let [user-id (:id user)]
    (when-not user-id
      (throw (ex-info "service allocation requires an authenticated user"
                      {:type :identity/unauthenticated})))
    (store/transact!
     (fn [state]
       (reduce-kv
        (fn [state service-id service]
          (let [existing (get-in state [:service-allocations user-id service-id])
                current (allocation service user organization)]
            (assoc-in state [:service-allocations user-id service-id]
                      (if existing
                        (merge existing
                               (select-keys
                                current
                                [:service :name :base-url :plan :quota-bytes
                                 :quota-pins :quota-source
                                 :enforcement-status :account-id :user-did
                                 :organization-did]))
                        current))))
        state (:account-services configuration))))
    {:schema schema
     :account {:id user-id :account-id (:account-id user) :did (:did user)}
     :services (->> (get-in (store/snapshot)
                            [:service-allocations user-id])
                    vals (sort-by :service) vec)}))
