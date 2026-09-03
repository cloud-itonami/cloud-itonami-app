(ns cloud.itonami.app.domain-tools
  "Agent-facing Domain API. Reads are direct; every mutation is Passkey-bound."
  (:require [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.authority.api :as authority-api]
            [cloud.itonami.app.cloudflare :as cloudflare]
            [cloud.itonami.app.identity :as identity]
            [yadori.cloudflare :as yadori]))

(def tools
  [{:name "domain_search" :description "Search Cloudflare's API-supported domain catalog. Discovery only; never purchase from this result."
    :parameters {:type "object" :required ["query"]
                 :properties {:query {:type "string"} :limit {:type "integer" :minimum 1 :maximum 50}}}}
   {:name "domain_check" :description "Authoritatively check current availability, registration price and renewal price."
    :parameters {:type "object" :required ["domains"]
                 :properties {:domains {:type "array" :items {:type "string"}}}}}
   {:name "domain_registrations" :description "List Cloudflare domain registrations for this operator account."
    :parameters {:type "object" :properties {}}}
   {:name "domain_registration_status" :description "Read a domain registration or its in-progress workflow status."
    :parameters {:type "object" :required ["domain"] :properties {:domain {:type "string"}}}}
   {:name "domain_dns_records" :description "List the current DNS records in an existing Cloudflare zone."
    :parameters {:type "object" :required ["zone_id"] :properties {:zone_id {:type "string"}}}}
   {:name "domain_registration_review"
    :description "Create a billable, non-refundable registration proposal from a fresh authoritative Check. A human Passkey approval is required before commit."
    :parameters {:type "object" :required ["domain"] :properties {:domain {:type "string"}}}}
   {:name "domain_auto_renew_review" :description "Propose enabling or disabling future automatic renewal charges. Requires Passkey approval."
    :parameters {:type "object" :required ["domain" "enabled"]
                 :properties {:domain {:type "string"} :enabled {:type "boolean"}}}}
   {:name "domain_dns_change_review" :description "Propose an exact DNS create, update or delete. Requires Passkey approval before mutation."
    :parameters {:type "object" :required ["operation" "zone_id"]
                 :properties {:operation {:type "string" :enum ["create" "update" "delete"]}
                              :zone_id {:type "string"} :record_id {:type "string"}
                              :record {:type "object"}}}}
   {:name "domain_proposals" :description "List this user's Domain and DNS proposals, including those awaiting Passkey approval."
    :parameters {:type "object" :properties {}}}
   {:name "domain_commit" :description "Execute only a proposal already approved by a human Passkey. Re-checks a registration quote before billing."
    :parameters {:type "object" :required ["proposal_id"] :properties {:proposal_id {:type "string"}}}}
   {:name "domain_reject" :description "Record that the human declined a pending Domain or DNS proposal."
    :parameters {:type "object" :required ["proposal_id"] :properties {:proposal_id {:type "string"}}}}])

(def ^:private names (into #{} (map :name tools)))
(defn tool? [name] (contains? names name))

(def ^:private writes
  #{"domain_registration_review" "domain_auto_renew_review"
    "domain_dns_change_review" "domain_commit" "domain_reject"})

(defn write-tool? [name] (contains? writes (str name)))

(defn describe [tool-name args]
  (case (str tool-name)
    "domain_registration_review" (str (:domain args) " の購入提案を現在価格から作成します。購入はまだ行いません。")
    "domain_auto_renew_review" (str (:domain args) " の自動更新を " (if (:enabled args) "有効" "無効") "にする提案を作成します。")
    "domain_dns_change_review" (str "zone " (:zone_id args) " の DNS " (:operation args) " 提案を作成します。")
    "domain_commit" (str "Passkey 承認済み proposal " (:proposal_id args) " だけを実行します。未承認なら拒否されます。")
    "domain_reject" (str "proposal " (:proposal_id args) " を却下済みとして記録します。")
    "Cloudflare の Domain / DNS 状態を読みます。"))

(defn- session [configuration]
  (when-let [token (agent-session/session-token configuration)]
    (let [resolved (identity/session token)]
      (when (and resolved
                 (= :agent (:kind resolved))
                 (identity/may-act? resolved))
        resolved))))

(defn available? [configuration]
  (and (cloudflare/available? configuration)
       (some? (session configuration))
       (true? (get-in configuration [:authorities :domain :enabled?]))))

(defn- session! [configuration]
  (or (session configuration)
      (throw (ex-info "Domain tools require an authenticated agent session"
                      {:type :domain-service/session-unavailable}))))

(defn- call [configuration request]
  (yadori/call! #(cloudflare/request! configuration %) request))

(defn call-tool [configuration tool-name {:keys [query limit domains domain enabled
                                                  operation zone_id record_id record
                                                  proposal_id]}]
  (let [account (cloudflare/account-id configuration)]
    (case tool-name
      "domain_search" (call configuration (yadori/search-request account query limit))
      "domain_check" (call configuration (yadori/check-request account domains))
      "domain_registrations" (call configuration (yadori/list-registrations-request account))
      "domain_registration_status"
      (call configuration (yadori/registration-request account domain))
      "domain_dns_records"
      (call configuration (yadori/list-dns-records-request zone_id))
      "domain_registration_review"
      (authority-api/review! configuration (session! configuration) :domain
                             {:op :domain/register :domain domain})
      "domain_auto_renew_review"
      (authority-api/review! configuration (session! configuration) :domain
                             {:op :domain/auto-renew :domain domain :enabled enabled})
      "domain_dns_change_review"
      (let [op ({"create" :dns/create "update" :dns/update "delete" :dns/delete}
                operation)]
        (authority-api/review! configuration (session! configuration) :domain
                               {:op op :zone-id zone_id :record-id record_id :record record}))
      "domain_proposals" (authority-api/proposals configuration (session! configuration) :domain)
      "domain_commit" (authority-api/commit! configuration (session! configuration) :domain proposal_id)
      "domain_reject" (authority-api/reject! configuration (session! configuration) :domain proposal_id)
      (throw (ex-info "unknown domain tool" {:type :mcp/unknown-tool})))))
