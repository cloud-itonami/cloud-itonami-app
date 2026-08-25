(ns cloud.itonami.app.decision-method-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.decision-method :as method]))

(def frame
  {:scope "choose a procurement route"
   :facts [{:id "quote" :statement "An intermediary quote exists"
            :evidence ["quote-2026-08-25"]}]
   :entities [{:id "buyer" :type "organization"}
              {:id "supplier" :type "manufacturer"}]
   :relations [{:from "buyer" :predicate "may-buy-from" :to "supplier"
                :evidence ["official storefront"]}]
   :dynamics {:mode "structural-sketch" :reason "lead time and inventory interact"
              :stocks ["required units"] :flows ["units delivered"]}
   :scenarios
   [{:id "baseline" :label "intermediary"
     :assumptions ["support has value"] :outcomes ["higher unit cost"]
     :scores {:expected_value 0.4 :evidence_confidence 0.8 :reversibility 0.8
              :authority_fit 1.0 :time_efficiency 0.7 :cost_efficiency 0.3
              :dependency_independence 0.2}}
    {:id "direct" :label "manufacturer"
     :assumptions ["official store accepts quantity"] :outcomes ["lower unit cost"]
     :scores {:expected_value 0.9 :evidence_confidence 0.7 :reversibility 0.8
              :authority_fit 1.0 :time_efficiency 0.7 :cost_efficiency 0.9
              :dependency_independence 0.9}}]
   :selected "direct"})

(deftest prepares-a-bounded-ranked-decision-frame
  (let [prepared (method/prepare-frame frame)]
    (is (= "cloud.itonami.decision-frame.v1"
           (:decision.method/schema prepared)))
    (is (= "direct" (:decision.method/selected prepared)))
    (is (= ["direct" "baseline"]
           (mapv :scenario/id (:decision.method/scenarios prepared))))
    (is (= 1.0 (reduce + (vals (:decision.method/score-weights prepared)))))))

(deftest refuses-fiction-shaped-frames
  (testing "facts without evidence are not facts"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"evidence"
                          (method/prepare-frame
                           (assoc-in frame [:facts 0 :evidence] [])))))
  (testing "relations cannot invent undeclared objects"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declared entities"
                          (method/prepare-frame
                           (assoc-in frame [:relations 0 :to] "ghost")))))
  (testing "dynamic work names stocks and flows"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stocks and flows"
                          (method/prepare-frame
                           (assoc-in frame [:dynamics :stocks] [])))))
  (testing "the selected scenario must exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declared selection"
                          (method/prepare-frame (assoc frame :selected "ghost"))))))

(deftest allows-an-explicit-non-dynamic-exception
  (let [prepared (method/prepare-frame
                  (assoc frame :dynamics
                         {:mode "not-material"
                          :reason "one reversible file read has no delayed feedback"
                          :stocks [] :flows []}))]
    (is (= "not-material"
           (get-in prepared [:decision.method/dynamics :dynamics/mode])))))
