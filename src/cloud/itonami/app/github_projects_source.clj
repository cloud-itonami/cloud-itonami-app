(ns cloud.itonami.app.github-projects-source
  "Bounded GitHub Projects v2 item ingestion. Cursor persistence belongs to the
  host; this adapter converts one GraphQL page into canonical WorkItems."
  (:require [clojure.string :as str]
            [cloud.itonami.app.github-projects-writeback :as github])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def schema "cloud.itonami.app.github-projects-source.v1")

(def items-query
  "query WorkItems($project:ID!,$first:Int!,$after:String,$fieldName:String!){node(id:$project){... on ProjectV2{id items(first:$first,after:$after){pageInfo{hasNextPage endCursor} nodes{id type updatedAt fieldValueByName(name:$fieldName){... on ProjectV2ItemFieldSingleSelectValue{optionId field{... on ProjectV2SingleSelectField{id}}}} content{... on Issue{id title body url updatedAt repository{nameWithOwner}} ... on PullRequest{id title body url updatedAt repository{nameWithOwner}} ... on DraftIssue{id title body updatedAt}}}}}}}")

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn content-hash [value]
  (str "sha256:"
       (hex (.digest (MessageDigest/getInstance "SHA-256")
                     (.getBytes (pr-str value) StandardCharsets/UTF_8)))))

(def required-source-fields
  [:id :organization :project :project-id :field-id :field-name :yakuwari
   :capability :status-option->work-status :target-option-ids
   :write-capability])

(defn source-config [value]
  (doseq [field required-source-fields]
    (when (or (nil? (get value field))
              (and (string? (get value field))
                   (str/blank? (get value field))))
      (throw (ex-info (str field " is required")
                      {:type :github-projects/invalid-source-config
                       :field field}))))
  (assoc value :schema schema))

(defn- node->work-item [source node]
  (let [content (:content node)
        field-value (:fieldValueByName node)
        option-id (:optionId field-value)
        status (get (:status-option->work-status source) option-id :backlog)
        identity {:title (:title content)
                  :body (:body content)
                  :content-id (:id content)
                  :content-updated-at (:updatedAt content)
                  :repository (get-in content [:repository :nameWithOwner])}]
    {:work.item/id (str "github-project-item:" (:id node))
     :work.item/organization (:organization source)
     :work.item/project (:project source)
     :work.item/title (or (:title content) "Untitled GitHub Project item")
     :work.item/capability (:capability source)
     :work.item/yakuwari (:yakuwari source)
     :work.item/content-hash (content-hash identity)
     :work.item/status status
     :work.item/created-at (or (:created-at source) 0)
     :work.item/source
     {:kind :github-projects-v2
      :source-id (:id source)
      :project-id (:project-id source)
      :item-id (:id node)
      :field-id (:field-id source)
      :field-name (:field-name source)
      :write-capability (:write-capability source)
      :url (:url content)
      :repository (get-in content [:repository :nameWithOwner])
      :basis {:project-id (:project-id source)
              :item-id (:id node)
              :field-id (:field-id source)
              :option-id option-id
              :updated-at (:updatedAt node)}
      :target-option-ids (:target-option-ids source)}}))

(defn fetch-page
  "Fetch at most `page-size` items after cursor and return canonical WorkItems."
  [transport source-value cursor]
  (let [source (source-config source-value)
        response (github/request
                  transport items-query
                  {:project (:project-id source)
                   :first (int (min 100 (max 1 (or (:page-size source) 50))))
                   :after cursor
                   :fieldName (:field-name source)})
        project (get-in response [:data :node])
        items (:items project)]
    (when-not (= (:project-id source) (:id project))
      (throw (ex-info "GitHub Project source was not found"
                      {:type :github-projects/source-not-found
                       :project-id (:project-id source)})))
    {:schema schema
     :source (:id source)
     :items (mapv #(node->work-item source %) (:nodes items))
     :cursor (get-in items [:pageInfo :endCursor])
     :has-next? (true? (get-in items [:pageInfo :hasNextPage]))}))
