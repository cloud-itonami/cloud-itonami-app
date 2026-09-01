(ns cloud.itonami.app.human-work-tools
  "Bot tools for requesting real human work.

  These tools never register or verify a worker. Creating, publishing, and
  cancelling are writes, so the resident Bot approval-card boundary applies.
  A Bot acts for its owning Human User and organization; its id is retained in
  the request source rather than impersonating a Person performer."
  (:require [clojure.string :as str]
            [cloud.itonami.app.human-work :as human-work]))

(def tool-definitions
  [{:name "human_work_request_status"
    :description "Read this owner's human-work requests, or one request by id."
    :parameters {:type "object"
                 :properties {:request_id {:type "string"}}}}
   {:name "human_work_request_create"
    :description (str "Create a draft request for work that must be performed by a qualified Human User. "
                      "This does not publish or fund the request. (write)")
    :parameters
    {:type "object"
     :required ["title" "summary" "category" "work_mode" "location"
                "work_window" "evidence_contract"]
     :properties
     {:title {:type "string"}
      :summary {:type "string"}
      :category {:type "string"}
      :visibility {:type "string" :enum ["organization" "public"]
                   :description "Public listings are redacted and require identity assurance."}
      :work_mode {:type "string" :enum ["onsite" "remote" "hybrid"]}
      :location {:type "object"
                 :description (str "Public work area only: country, region/locality, service_area, "
                                   "and minimum_verification. Never put the exact private address here.")}
      :work_window {:type "object"
                    :description "ISO-8601 start and end."}
      :requirements {:type "object"
                     :description (str "credentials: licence, qualification, permit, insurance, "
                                       "training, or asset requirements with scopes and jurisdiction.")}
      :private_details {:type "object"
                        :description "Withheld until a qualified Human User accepts."}
      :evidence_contract {:type "array" :items {:type "string"}}
      :compensation {:type "object"
                     :description (str "USDC amount_atomic (six decimals), EVM CAIP-2 network, and optional platform_fee_bps. "
                                       "A configured x402 auth-capture flow funds escrow after a worker accepts, captures after verification, and voids on cancellation or rejection.")}
      :goal_id {:type "string"}
      :goal_step_id {:type "string"}
      :work_item_id {:type "string"}}}}
   {:name "human_work_request_publish"
    :description "Publish one qualified-human work request after reviewing its requirements and private-data boundary. (write)"
    :parameters {:type "object" :required ["request_id"]
                 :properties {:request_id {:type "string"}}}}
   {:name "human_work_matches"
    :description "List Human Users whose verified location, licence, qualifications, availability, and conflict state satisfy one open request."
    :parameters {:type "object" :required ["request_id"]
                 :properties {:request_id {:type "string"}}}}
   {:name "human_work_request_cancel"
    :description "Cancel one draft or open human-work request. Accepted work cannot be cancelled here. (write)"
    :parameters {:type "object" :required ["request_id"]
                 :properties {:request_id {:type "string"}}}}])

(def ^:private names (into #{} (map :name) tool-definitions))
(def ^:private writes
  #{"human_work_request_create" "human_work_request_publish"
    "human_work_request_cancel"})

(defn tool? [name] (contains? names (str name)))
(defn write-tool? [name] (contains? writes (str name)))

(defn- kebab-key [key]
  (if (keyword? key)
    (keyword (str/replace (name key) "_" "-"))
    key))

(defn- normalize-input [value]
  (cond
    (map? value) (into {} (map (fn [[key item]]
                                 [(kebab-key key) (normalize-input item)])) value)
    (vector? value) (mapv normalize-input value)
    :else value))

(defn- owner [bot]
  (or (:owner-id bot) (:bot/owner bot)))

(defn- organization [bot]
  (or (:organization-id bot) (:bot/organization bot)))

(defn- owned-request! [bot request-id]
  (let [request (human-work/request request-id)]
    (when-not request
      (throw (ex-info "Human work request was not found"
                      {:type :human-work/not-found})))
    (when-not (and (= (owner bot) (:requester-id request))
                   (= (organization bot) (:organization-id request)))
      (throw (ex-info "Human work request is outside this Bot's owner or organization"
                      {:type :human-work/forbidden})))
    request))

(defn describe [name input]
  (let [input (normalize-input input)
        id (:request-id input)]
    (case (str name)
      "human_work_request_status"
      (if id (str id " の人間作業状況を読みます。")
          "この所有者が依頼した人間作業を読みます。")
      "human_work_request_create"
      (str "資格・場所・空き時間を検証する人間作業「" (:title input)
           "」の非公開draftを作成します。公開・決済はしません。")
      "human_work_request_publish"
      (str id " を適格なHuman Userへ公開します。")
      "human_work_matches"
      (str id " に現在適格なHuman Userを確認します。")
      "human_work_request_cancel"
      (str id " の未受諾依頼を取り消します。")
      "人間作業依頼を更新します。")))

(defn call-tool! [bot name input]
  (let [input (normalize-input input)
        request-id (:request-id input)
        actor (owner bot)]
    (case (str name)
      "human_work_request_status"
      (if request-id
        (owned-request! bot request-id)
        (human-work/requests actor (organization bot)))

      "human_work_request_create"
      (human-work/create-request!
       (-> input
           (assoc :organization-id (organization bot))
           (assoc :source (merge (:source input)
                                 {:bot-id (:bot/id bot)
                                  :goal-id (:goal-id input)
                                  :goal-step-id (:goal-step-id input)
                                  :work-item-id (:work-item-id input)})))
       actor)

      "human_work_request_publish"
      (do (owned-request! bot request-id)
          (human-work/publish! request-id actor))

      "human_work_matches"
      (do (owned-request! bot request-id)
          (human-work/matches request-id actor))

      "human_work_request_cancel"
      (do (owned-request! bot request-id)
          (human-work/cancel! request-id actor))

      (throw (ex-info "Unknown human-work tool"
                      {:type :human-work/unknown-tool :tool name})))))
