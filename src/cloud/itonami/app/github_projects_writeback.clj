(ns cloud.itonami.app.github-projects-writeback
  "Basis-checked GitHub Projects v2 status updates.

  Reading a board never grants execution authority.  This adapter performs a
  mutation only when the item still has the exact project, field, option and
  updatedAt basis captured by the WorkItem lease."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.identity :as identity])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def schema "cloud.itonami.app.github-projects-writeback.v1")

(def item-basis-query
  "query WorkItemBasis($item:ID!,$fieldName:String!){node(id:$item){... on ProjectV2Item{id updatedAt project{id} fieldValueByName(name:$fieldName){... on ProjectV2ItemFieldSingleSelectValue{optionId field{... on ProjectV2SingleSelectField{id}}}}}}}")

(def status-mutation
  "mutation WorkItemStatus($project:ID!,$item:ID!,$field:ID!,$option:String!){updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$field,value:{singleSelectOptionId:$option}}){projectV2Item{id updatedAt}}}")

(defn- required! [value field]
  (when (or (nil? value) (and (string? value) (str/blank? value)))
    (throw (ex-info (str field " is required")
                    {:type :github-projects/invalid-source :field field})))
  value)

(defn source [value]
  (doseq [field [:project-id :item-id :field-id :field-name :basis
                 :target-option-ids :write-capability]]
    (required! (get value field) field))
  (assoc value :schema schema :kind :github-projects-v2))

(defn- response-errors! [response]
  (when-let [errors (seq (:errors response))]
    (throw (ex-info "GitHub GraphQL returned errors"
                    {:type (if (some #(or (= "RATE_LIMITED" (:type %))
                                         (= "RATE_LIMITED"
                                            (get-in % [:extensions :type])))
                                     errors)
                             :github-projects/rate-limited
                             :github-projects/graphql-error)
                     :retryable? (boolean
                                  (some #(= "RATE_LIMITED" (:type %)) errors))
                     :errors errors})))
  response)

(defn current-basis
  "Read the comparison tuple from a GraphQL response."
  [response]
  (let [node (get-in (response-errors! response) [:data :node])
        field-value (:fieldValueByName node)]
    (when-not node
      (throw (ex-info "GitHub Project item was not found"
                      {:type :github-projects/item-not-found})))
    {:project-id (get-in node [:project :id])
     :item-id (:id node)
     :field-id (get-in field-value [:field :id])
     :option-id (:optionId field-value)
     :updated-at (:updatedAt node)}))

(def basis-fields [:project-id :item-id :field-id :option-id :updated-at])

(defn verify-basis!
  "Fail closed unless every mutable-board basis field still matches."
  [expected actual]
  (let [expected (select-keys expected basis-fields)
        actual (select-keys actual basis-fields)]
    (when-not (= expected actual)
      (throw (ex-info "GitHub Project item changed after it was leased"
                      {:type :github-projects/stale-basis
                       :expected expected :actual actual})))
    actual))

(defn request
  "Call an injected GraphQL transport. The transport accepts query/variables
  and returns the decoded keyword-keyed GraphQL body."
  [transport query variables]
  (response-errors! (transport {:query query :variables variables})))

(defn write-status!
  "Verify source basis, then update one single-select status. Returns the new
  source basis. No mutation is attempted when write-back is disabled."
  [configuration source-value status transport]
  (let [cfg (:work-governance configuration)
        source-value (source source-value)]
    (when-not (and (:enabled? cfg) (:github-writeback-enabled? cfg))
      (throw (ex-info "GitHub Projects write-back is disabled"
                      {:type :github-projects/writeback-disabled})))
    (let [option-id (get (:target-option-ids source-value) status)]
      (required! option-id [:target-option-ids status])
      (let [read-response (request transport item-basis-query
                                   {:item (:item-id source-value)
                                    :fieldName (:field-name source-value)})
            _ (verify-basis! (:basis source-value)
                             (current-basis read-response))
            mutation-response
            (request transport status-mutation
                     {:project (:project-id source-value)
                      :item (:item-id source-value)
                      :field (:field-id source-value)
                      :option option-id})
            item (get-in mutation-response
                         [:data :updateProjectV2ItemFieldValue :projectV2Item])]
        (when-not (= (:item-id source-value) (:id item))
          (throw (ex-info "GitHub mutation did not return the leased item"
                          {:type :github-projects/invalid-mutation-receipt})))
        (let [expected {:project-id (:project-id source-value)
                        :item-id (:item-id source-value)
                        :field-id (:field-id source-value)
                        :option-id option-id
                        :updated-at (:updatedAt item)}
              post-response (request transport item-basis-query
                                     {:item (:item-id source-value)
                                      :fieldName (:field-name source-value)})]
          (try
            (verify-basis! expected (current-basis post-response))
            (catch Exception error
              (throw (ex-info "GitHub Project changed during status mutation"
                              {:type :github-projects/post-write-conflict
                               :mutation-basis expected
                               :observed (:actual (ex-data error))}
                              error)))))))))

(defn github-transport
  "Production GraphQL transport. The connected GitHub OAuth token is resolved
  only at call time and is never placed in EDN or returned."
  [{:keys [query variables]}]
  (let [token (identity/access-token :github)]
    (when (str/blank? token)
      (throw (ex-info "A connected GitHub account is required"
                      {:type :github-projects/token-required})))
    (let [body (json/write-str {:query query :variables variables})
          client (-> (HttpClient/newBuilder)
                     (.connectTimeout (Duration/ofSeconds 10))
                     (.build))
          request (-> (HttpRequest/newBuilder
                       (URI/create "https://api.github.com/graphql"))
                      (.timeout (Duration/ofSeconds 30))
                      (.header "Authorization" (str "Bearer " token))
                      (.header "Accept" "application/vnd.github+json")
                      (.header "Content-Type" "application/json")
                      (.header "User-Agent" "cloud-itonami-app")
                      (.POST (HttpRequest$BodyPublishers/ofString body))
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (when-not (<= 200 (.statusCode response) 299)
        (let [status (.statusCode response)
              retryable? (contains? #{429 502 503 504} status)
              retry-after (some-> (.firstValue (.headers response)
                                                "retry-after")
                                  (.orElse nil))]
          (throw (ex-info "GitHub GraphQL request failed"
                          {:type (cond
                                   (= 429 status) :github-projects/rate-limited
                                   retryable? :github-projects/transient-error
                                   :else :github-projects/http-error)
                           :retryable? retryable?
                           :retry-after retry-after
                           :status status}))))
      (json/read-str (.body response) :key-fn keyword))))
