(ns cloud.itonami.app.tenant-tools
  "MCP adapter for the versioned tenant-connection API. Approval is absent on
  purpose: only a browser Passkey session may approve a pending connection."
  (:require [cloud.itonami.app.app-client :as client]))

(def tools
  [{:name "tenant_list"
    :description "List only the tenants for which this agent's user has membership."
    :parameters {:type "object" :properties {}}}
   {:name "tenant_connection_list"
    :description "List this user's tenant connections and their lease status."
    :parameters {:type "object" :properties {}}}
   {:name "tenant_connection_request"
    :description (str "Request a tenant-bound loop lease. The result remains "
                      "pending until a human approves it with a Passkey session.")
    :parameters
    {:type "object" :required ["tenant_id" "capabilities"]
     :properties
     {:tenant_id {:type "string"}
      :agent_id {:type "string"}
      :capabilities {:type "array" :items {:type "string"}}
      :ttl_seconds {:type "integer" :minimum 60 :maximum 86400}
      :max_operations {:type "integer" :minimum 1 :maximum 100000}
      :max_storage_bytes {:type "integer" :minimum 0}
      :idempotency_key {:type "string"}}}}
   {:name "tenant_connection_status"
    :description "Read one tenant connection without changing active organization state."
    :parameters {:type "object" :required ["connection_id"]
                 :properties {:connection_id {:type "string"}}}}
   {:name "tenant_connection_renew"
    :description "Request a new TTL. A human must approve before expiry is extended."
    :parameters {:type "object" :required ["connection_id"]
                 :properties
                 {:connection_id {:type "string"}
                  :ttl_seconds {:type "integer" :minimum 60 :maximum 86400}}}}
   {:name "tenant_connection_revoke"
    :description "Revoke this agent session's tenant connection immediately."
    :parameters {:type "object" :required ["connection_id"]
                 :properties {:connection_id {:type "string"}}}}
   {:name "tenant_connection_context"
    :description (str "Consume one operation budget unit and return the immutable "
                      "tenant/repository context for a capability.")
    :parameters {:type "object" :required ["connection_id" "capability"]
                 :properties {:connection_id {:type "string"}
                              :capability {:type "string"}}}}
   {:name "tenant_repository_read"
    :description "Read the local plaintext EDN projection bound to a tenant connection."
    :parameters {:type "object" :required ["connection_id"]
                 :properties {:connection_id {:type "string"}}}}
   {:name "tenant_repository_write"
    :description (str "Replace the local EDN projection using semantic-CID "
                      "optimistic concurrency and consume storage budget.")
    :parameters {:type "object" :required ["connection_id" "state_edn"]
                 :properties
                 {:connection_id {:type "string"}
                  :state_edn {:type "string"}
                  :expected_cid {:type "string"}}}}
   {:name "tenant_repository_publish"
    :description (str "Encrypt the local projection with Kagi, publish ciphertext "
                      "blocks through DataLad, then advance the Kotobase head.")
    :parameters {:type "object" :required ["connection_id"]
                 :properties {:connection_id {:type "string"}}}}])

(def ^:private tool-names (into #{} (map :name tools)))
(defn tool? [name] (contains? tool-names name))
(def ^:dynamic *authenticated?*
  "True only while the HTTP MCP boundary has authenticated its bearer."
  false)
(defn available? [configuration]
  (or *authenticated?* (client/available? configuration)))

(defn call-tool [configuration tool-name arguments]
  (let [id (or (:connection-id arguments) (:connection_id arguments))]
    (case tool-name
      "tenant_list" (client/request! configuration :get "/v1/tenants")
      "tenant_connection_list"
      (client/request! configuration :get "/v1/tenant-connections")
      "tenant_connection_request"
      (client/request!
       configuration :post "/v1/tenant-connections"
       {:tenant_id (or (:tenant-id arguments) (:tenant_id arguments))
        :agent_id (or (:agent-id arguments) (:agent_id arguments))
        :capabilities (:capabilities arguments)
        :ttl_seconds (or (:ttl-seconds arguments) (:ttl_seconds arguments))
        :budget {:max_operations (or (:max-operations arguments)
                                     (:max_operations arguments))
                 :max_storage_bytes (or (:max-storage-bytes arguments)
                                        (:max_storage_bytes arguments))}
        :idempotency_key (or (:idempotency-key arguments)
                             (:idempotency_key arguments))})
      "tenant_connection_status"
      (client/request! configuration :get (str "/v1/tenant-connections/" id))
      "tenant_connection_renew"
      (client/request! configuration :post
                       (str "/v1/tenant-connections/" id "/renew")
                       {:ttl_seconds (or (:ttl-seconds arguments)
                                         (:ttl_seconds arguments))})
      "tenant_connection_revoke"
      (client/request! configuration :post
                       (str "/v1/tenant-connections/" id "/revoke") {})
      "tenant_connection_context"
      (client/request! configuration :post
                       (str "/v1/tenant-connections/" id "/context")
                       {:capability (:capability arguments)})
      "tenant_repository_read"
      (client/request! configuration :get
                       (str "/v1/tenant-connections/" id "/repository"))
      "tenant_repository_write"
      (client/request! configuration :post
                       (str "/v1/tenant-connections/" id "/repository")
                       {:state_edn (or (:state-edn arguments)
                                       (:state_edn arguments))
                        :expected_cid (or (:expected-cid arguments)
                                          (:expected_cid arguments))})
      "tenant_repository_publish"
      (client/request! configuration :post
                       (str "/v1/tenant-connections/" id
                            "/repository/publish") {})
      (throw (ex-info "unknown tenant tool"
                      {:type :tenant-tools/unknown-tool})))))
