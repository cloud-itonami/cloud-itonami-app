(ns cloud.itonami.app.github-projects-source-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.github-projects-source :as source]))

(def config
  {:id :engineering-board :organization "org-1" :project "project-1"
   :project-id "PVT_project" :field-id "PVTSSF_status" :field-name "Status"
   :yakuwari :implementer :capability :repository/change
   :write-capability :github.projects/status-write
   :status-option->work-status {"todo" :ready}
   :target-option-ids {:review "review" :done "done"}})

(deftest project-page-becomes-content-and-basis-bound-work
  (let [page (source/fetch-page
              (fn [_]
                {:data {:node
                        {:id "PVT_project"
                         :items {:pageInfo {:hasNextPage true
                                           :endCursor "cursor-2"}
                                 :nodes
                                 [{:id "PVTI_item" :type "ISSUE"
                                   :updatedAt "2026-08-04T01:00:00Z"
                                   :fieldValueByName
                                   {:optionId "todo"
                                    :field {:id "PVTSSF_status"}}
                                   :content
                                   {:id "I_issue" :title "Implement it"
                                    :body "Acceptance" :url "https://example"
                                    :updatedAt "2026-08-04T00:00:00Z"
                                    :repository
                                    {:nameWithOwner "o/r"}}}]}}}})
              config nil)
        item (first (:items page))]
    (is (= "cursor-2" (:cursor page)))
    (is (:has-next? page))
    (is (= :ready (:work.item/status item)))
    (is (= "github-project-item:PVTI_item" (:work.item/id item)))
    (is (= :github.projects/status-write
           (get-in item [:work.item/source :write-capability])))
    (is (= "todo" (get-in item [:work.item/source :basis :option-id])))
    (is (re-matches #"sha256:[0-9a-f]{64}"
                    (:work.item/content-hash item)))))
