(ns cloud.itonami.app.authority.domain
  "Passkey-bound authority adapter for billable registration and DNS changes."
  (:require [clojure.string :as str]
            [cloud.itonami.app.cloudflare :as cloudflare]
            [yadori.cloudflare :as yadori]))

(def authority-key :domain)
(def ops
  {:domain/register :domain/registration-approval
   :domain/auto-renew :domain/renewal-policy-approval
   :dns/create :domain/dns-change-approval
   :dns/update :domain/dns-change-approval
   :dns/delete :domain/dns-delete-approval})

(defn- refuse [type message]
  (throw (ex-info message {:type type})))

(defn- request! [configuration request]
  (cloudflare/request! configuration request))

(defn with-server-facts
  "Overwrite security-bearing quote facts with a live Cloudflare Check."
  [configuration request]
  (if (= :domain/register (:op request))
    (let [domain (yadori/fqdn (:domain request))
          checked (yadori/call! #(request! configuration %)
                                (yadori/check-request
                                 (cloudflare/account-id configuration) [domain]))]
      (assoc request :domain domain :quote (yadori/domain-quote checked domain)))
    request))

(defn pre-check [_configuration _session {:keys [op domain quote enabled zone-id
                                                  record-id record]}]
  (when-not (contains? ops op)
    (refuse :domain/op-unsupported (str "unsupported domain operation: " op)))
  (case op
    :domain/register
    (do (when-not (and (map? quote) (= (yadori/fqdn domain) (:domain quote)))
          (refuse :domain/quote-missing "authoritative Cloudflare quote is required"))
        {:op op :domain (yadori/fqdn domain) :quote quote})

    :domain/auto-renew
    {:op op :domain (yadori/fqdn domain) :enabled (boolean enabled)}

    :dns/create
    (let [request (yadori/create-dns-record-request zone-id record)]
      {:op op :zone-id zone-id :record (:body request)})

    :dns/update
    (let [request (yadori/update-dns-record-request zone-id record-id record)]
      {:op op :zone-id zone-id :record-id record-id :record (:body request)})

    :dns/delete
    (do (when (str/blank? (str record-id))
          (refuse :domain/record-id-missing "record-id is required"))
        {:op op :zone-id (str zone-id) :record-id (str record-id)})))

(defn material [{:keys [op domain quote enabled zone-id record-id record]}]
  (case op
    :domain/register (yadori/quote-material quote)
    :domain/auto-renew (str "yadori-auto-renew/v1|domain=" domain "|enabled=" enabled)
    :dns/create (str "yadori-dns/v1|op=create|zone=" zone-id
                     "|type=" (:type record) "|name=" (:name record)
                     "|content=" (:content record) "|ttl=" (:ttl record)
                     "|proxied=" (:proxied record))
    :dns/update (str "yadori-dns/v1|op=update|zone=" zone-id "|record=" record-id
                     "|type=" (:type record) "|name=" (:name record)
                     "|content=" (:content record) "|ttl=" (:ttl record)
                     "|proxied=" (:proxied record))
    :dns/delete (str "yadori-dns/v1|op=delete|zone=" zone-id "|record=" record-id)))

(defn- outcome [result reference]
  (let [state (or (:state result) (get result "state"))]
    (cond
      (contains? #{"pending" "in_progress" "blocked"} state)
      {:authority/pending? true :authority/reference reference}

      (= "action_required" state)
      {:authority/ok? false
       :authority/refusal {:type :domain/action-required :result result}}

      (= "failed" state)
      {:authority/ok? false
       :authority/refusal {:type :domain/cloudflare-failed :result result}}

      :else {:authority/ok? true :authority/record result})))

(defn commit! [configuration _session proposal]
  (let [{:keys [op domain quote enabled zone-id record-id record]} (:value proposal)
        account (cloudflare/account-id configuration)
        call #(request! configuration %)
        result (case op
                 :domain/register
                 (yadori/register-approved! call account quote)
                 :domain/auto-renew
                 (yadori/call! call (yadori/auto-renew-request account domain enabled))
                 :dns/create
                 (yadori/call! call (yadori/create-dns-record-request zone-id record))
                 :dns/update
                 (yadori/call! call (yadori/update-dns-record-request zone-id record-id record))
                 :dns/delete
                 (yadori/call! call (yadori/delete-dns-record-request zone-id record-id)))]
    (outcome result (or domain record-id))))

(defn status! [configuration _session proposal]
  (let [{:keys [op domain]} (:value proposal)]
    (if (= :domain/register op)
      (outcome (yadori/call! #(request! configuration %)
                             (yadori/registration-status-request
                              (cloudflare/account-id configuration) domain))
               domain)
      {:authority/ok? false
       :authority/refusal {:type :domain/no-pending-status}})))

(defn domain []
  {:authority/key authority-key
   :authority/status status!
   :authority/context-type #(get ops %)
   :authority/pre-check pre-check
   :authority/material material
   :authority/commit! commit!})
