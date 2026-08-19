(ns cloud.itonami.app.bot-tools
  "CLI/MCP adapter for owner-scoped Bot work.

  This surface can list, submit, inspect, cancel, re-provision the workforce
  from the declared registry, and—only for a human-enabled omakase Bot—decide a
  held write. Every stateful operation goes to the resident process over its
  agent-session API.

  Creating a Bot and widening a grant remain human-only. Re-provisioning does
  not belong to that family: the caller names nothing, and the workforce is
  reconciled to what `network-awai/loop-yakuwari` declares, so the authority
  comes from a reviewed repository rather than from the call. Owner directive,
  2026-08-18 — before it, an objective edited in the registry could not reach a
  running Bot without a person opening the browser, and the workforce here had
  been running a three-day-old projection for exactly that reason."
  (:require [cloud.itonami.app.app-client :as client]))

(def tools
  [{:name "bots_list" :description "List this owner's Bots and their omakase/readiness state."
    :parameters {:type "object" :properties {}}}
   {:name "bot_messages" :description "Read one owned Bot's durable conversation."
    :parameters {:type "object" :required ["bot_id"]
                 :properties {:bot_id {:type "string"}}}}
   {:name "workforce_status" :description "Read the installed startup workforce and resident-job status, including when it was last provisioned and what its recent resident runs came to. A projection older than the registry means running Bots carry stale objectives. `outcomes` counts the last runs by what happened -- `completed` and `no-op` are both successes and are separate on purpose, and failures keep the provider's own name. Absent `outcomes` with a note means nothing has been measured, which is not the same as nothing being wrong."
    :parameters {:type "object" :properties {}}}
   {:name "workforce_provision" :description "Re-provision the workforce from the declared registry, so registry edits reach running Bots. Reconciles to what loop-yakuwari declares; it cannot create a Bot or widen a grant."
    :parameters {:type "object" :properties {}}}
   {:name "bot_task" :description "Submit one task to an owned Bot and wait for its bounded result. Omakase writes execute with receipts."
    :parameters {:type "object" :required ["bot_id" "text"]
                 :properties {:bot_id {:type "string"} :text {:type "string"}}}}
   {:name "bot_handoff" :description "Run one bounded, durable two-way handoff between two owned Bots. Each Bot keeps its own grants and isolated context."
    :parameters {:type "object" :required ["from_bot_id" "to_bot_id" "task"]
                 :properties {:from_bot_id {:type "string"}
                              :to_bot_id {:type "string"}
                              :task {:type "string"}
                              :depth {:type "integer"}}}}
   {:name "bot_decide" :description "Approve or reject a held write. Agent sessions are accepted only when that Bot's human-enabled omakase mode is on."
    :parameters {:type "object" :required ["bot_id" "card_id" "decision"]
                 :properties {:bot_id {:type "string"} :card_id {:type "string"}
                              :decision {:type "string" :enum ["approved" "rejected"]}}}}
   {:name "bot_cancel" :description "Cancel one active owned Bot run by its run id."
    :parameters {:type "object" :required ["bot_id" "run_id"]
                 :properties {:bot_id {:type "string"} :run_id {:type "string"}}}}])

(def ^:private tool-names (into #{} (map :name) tools))
(defn tool? [name] (contains? tool-names name))
(defn available? [configuration] (client/available? configuration))

(defn call-tool [configuration name {:keys [bot_id text card_id decision run_id
                                             from_bot_id to_bot_id task depth]}]
  (case name
    "bots_list" (client/request! configuration :get "/api/agent-bots")
    "bot_messages" (client/request! configuration :get
                                         (str "/api/agent-bots/" bot_id "/messages"))
    "workforce_status" (client/request! configuration :get
                                         "/api/agent-bots/workforce")
    ;; Provisioning walks the registry and reconciles every declared role, so it
    ;; is slower than the other reads -- give it the long-call path rather than
    ;; the default timeout.
    "workforce_provision" (client/request-with-timeout!
                            configuration :post
                            "/api/agent-bots/workforce/provision" 120 {})
    "bot_task" (client/request-with-timeout!
                 configuration :post (str "/api/agent-bots/" bot_id "/messages") 660
                 {:text text})
    "bot_handoff" (client/request-with-timeout!
                    configuration :post
                    (str "/api/agent-bots/" from_bot_id "/handoff") 660
                    {:to to_bot_id :task task :depth depth})
    "bot_decide" (client/request-with-timeout!
                   configuration :post
                   (str "/api/agent-bots/" bot_id "/cards/" card_id "/decide") 660
                   {:decision decision})
    "bot_cancel" (client/request! configuration :post
                                   (str "/api/agent-bots/" bot_id
                                        "/messages/" run_id "/cancel") {})
    (throw (ex-info (str "unknown Bot tool: " name)
                    {:type :bot-tools/unknown-tool}))))
