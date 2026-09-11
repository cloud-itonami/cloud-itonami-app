(ns cloud.itonami.bots-ui-test
  (:require [cljs.test :refer [deftest is run-tests]]
            [kotoba.lang.text :as str]
            [cloud.itonami.bots-ui :as ui]))

(deftest actions-require-explicit-capability-and-local-route
  (doseq [cap [nil {} {:available? true} {:href "/lend"}
               {:available? "true" :href "/lend"}
               {:available? true :href "https://example.com"}
               {:available? true :href "//example.com"}
               {:available? true :href "/\\example.com"}
               {:available? true :href "javascript:alert(1)"}]]
    (is (not (ui/action-ready? cap))))
  (is (ui/action-ready? {:available? true :href "/bots/42/lend"})))

(deftest public-feed-does-not-grant-transaction-actions
  (let [view (ui/business-bots
              {:locale :ja :bots [{:id "42" :name "Test Bot" :status "running"}]})
        nodes (tree-seq coll? seq view)
        buttons (filter #(and (vector? %) (= :button (first %))) nodes)]
    (is (= 3 (count buttons)))
    (is (every? #(true? (:disabled (second %))) buttons))))

(deftest unavailable-feed-is-not-invented-work
  (let [view (pr-str (ui/business-bots {:locale :ja :bots nil}))]
    (is (str/includes? view "活動を取得できません"))
    (is (not (str/includes? view "running")))))

(deftest only-configured-participation-is-enabled
  (let [view (ui/participation {:locale :en
                              :capabilities {:human {:available? true :href "/work/1"}}})
        nodes (tree-seq coll? seq view)]
    (is (= 1 (count (filter #(and (vector? %) (= :a (first %))) nodes))))))

(run-tests)
