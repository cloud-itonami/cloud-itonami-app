(ns cloud.itonami.app.github-projects-writeback-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.github-projects-sandbox :as sandbox]
            [cloud.itonami.app.github-projects-writeback :as github]))

(def source
  {:kind :github-projects-v2
   :project-id "PVT_project"
   :item-id "PVTI_item"
   :field-id "PVTSSF_status"
   :field-name "Status"
   :write-capability :github.projects/status-write
   :basis {:project-id "PVT_project" :item-id "PVTI_item"
           :field-id "PVTSSF_status" :option-id "todo"
           :updated-at "2026-08-04T01:00:00Z"}
   :target-option-ids {:review "review" :failed "failed"}})

(def current-response
  {:data {:node {:id "PVTI_item" :updatedAt "2026-08-04T01:00:00Z"
                 :project {:id "PVT_project"}
                 :fieldValueByName {:optionId "todo"
                                    :field {:id "PVTSSF_status"}}}}})

(deftest basis-is-verified-before-project-mutation
  (let [calls (atom [])
        transport (fn [request]
                    (let [index (count (swap! calls conj request))]
                      (cond
                        (= 1 index) current-response
                        (= 2 index)
                        {:data {:updateProjectV2ItemFieldValue
                                {:projectV2Item
                                 {:id "PVTI_item"
                                  :updatedAt "2026-08-04T01:01:00Z"}}}}
                        :else
                        (-> current-response
                            (assoc-in [:data :node :updatedAt]
                                      "2026-08-04T01:01:00Z")
                            (assoc-in [:data :node :fieldValueByName :optionId]
                                      "review")))))
        basis (github/write-status!
               {:work-governance {:enabled? true
                                  :github-writeback-enabled? true}}
               source :review transport)]
    (is (= 3 (count @calls)))
    (is (= "review" (get-in @calls [1 :variables :option])))
    (is (= "review" (:option-id basis)))
    (is (= "2026-08-04T01:01:00Z" (:updated-at basis)))))

(deftest stale-basis-never-reaches-mutation
  (let [calls (atom [])
        transport (fn [request]
                    (swap! calls conj request)
                    (assoc-in current-response [:data :node :updatedAt]
                              "2026-08-04T02:00:00Z"))
        error (try
                (github/write-status!
                 {:work-governance {:enabled? true
                                    :github-writeback-enabled? true}}
                 source :review transport)
                (catch Exception error error))]
    (is (= :github-projects/stale-basis (:type (ex-data error))))
    (is (= 1 (count @calls)))))

(deftest writeback-has-an-independent-default-deny-gate
  (let [error (try
                (github/write-status!
                 {:work-governance {:enabled? true
                                    :github-writeback-enabled? false}}
                 source :review (fn [_] (throw (Exception. "must not call"))))
                (catch Exception error error))]
    (is (= :github-projects/writeback-disabled (:type (ex-data error))))))

(deftest sandbox-probe-verifies-target-and-restores-original-option
  (let [basis (atom (:basis source))
        calls (atom [])
        transport
        (fn [{:keys [query variables] :as request}]
          (swap! calls conj request)
          (if (= query github/item-basis-query)
            {:data {:node {:id "PVTI_item" :updatedAt (:updated-at @basis)
                           :project {:id "PVT_project"}
                           :fieldValueByName
                           {:optionId (:option-id @basis)
                            :field {:id "PVTSSF_status"}}}}}
            (let [next-basis (assoc @basis
                                    :option-id (:option variables)
                                    :updated-at
                                    (str "2026-08-04T01:0"
                                         (count @calls) ":00Z"))]
              (reset! basis next-basis)
              {:data {:updateProjectV2ItemFieldValue
                      {:projectV2Item {:id "PVTI_item"
                                      :updatedAt (:updated-at next-basis)}}}})))
        configuration {:work-governance
                       {:enabled? true :github-writeback-enabled? true
                        :github-write-capabilities
                        #{:github.projects/status-write}}}
        source-value (assoc source :sandbox-option-id "review")
        receipt (with-redefs [sandbox/ensure-enabled! (fn [_ _] true)]
                  (sandbox/probe! configuration source-value transport))]
    (is (= :verified-and-restored (:status receipt)))
    (is (= "todo" (:option-id @basis)))
    (is (= "review" (:sandbox-option-id receipt)))
    (is (= 7 (count @calls)))
    (is (str/starts-with? (:receipt-hash receipt) "sha256:"))))
