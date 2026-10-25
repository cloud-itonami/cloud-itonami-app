(ns cloud.itonami.my-bots-ui-test
  (:require [cljs.test :refer [deftest is run-tests]]
            [kotoba.lang.text :as str]
            [cloud.itonami.my-bots-ui :as ui]))

(deftest signed-out-view-never-shows-private-state
  (let [html (pr-str (ui/screen {:principal nil :phase :ready
                                :bots [{:id "1" :name "Private name"}]
                                :bot {:id "1" :name "Private name" :messages [{:text "Private message"}]}} {}))]
    (is (not (str/includes? html "Private name")))
    (is (not (str/includes? html "Private message")))
    (is (str/includes? html "Sign in"))))

(deftest unconfirmed-run-cannot-be-submitted-again
  (let [view (ui/screen {:principal "did:test" :bot {:id "1" :name "Bot" :status "needs-review"}
                         :bots [] :locale :ja} {})
        nodes (tree-seq coll? seq view)]
    (is (str/includes? (pr-str view) "前の処理の結果"))
    (is (not-any? #(and (map? %) (= "my-bot-message" (:id %))) nodes))))

(deftest approval-is-an-explicit-human-choice
  (let [decisions (atom [])
        view (ui/screen {:principal "did:test" :bots []
                         :bot {:id "1" :name "Bot" :pendingApproval {:name "propose" :arguments {}}}}
                        {:approve #(swap! decisions conj %)})
        buttons (filter #(and (vector? %) (= :button (first %)) (contains? #{"Approve" "Decline"} (last %)))
                        (tree-seq coll? seq view))]
    (is (empty? @decisions))
    (doseq [button buttons] ((:on-click (second button)) nil))
    (is (= [true false] @decisions))))
(run-tests)
