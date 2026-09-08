(ns cloud.itonami.public-bots-test
 (:require [cljs.test :refer [deftest is run-tests]] [clojure.string :as str] [cloud.itonami.public-bots :as ui]))
(def directory {:personalized true :pool {:status "complete" :totals {:pooled "12000000"}}
 :items [{:id "org/lent" :name "Lent Bot" :funding {:status "not-accepting" :lending true :position "10000000"}}
         {:id "org/other" :name "Other Bot" :funding {:status "accepting" :lending false}}]})
(deftest lending-filter-is-independent-of-open-funding
 (let [s (pr-str (ui/panel {:locale :ja :public-directory directory :public-funding "lending"} {}))]
  (is (str/includes? s "Lent Bot")) (is (not (str/includes? s "Other Bot")))
  (is (str/includes? s "10.00 USDC")) (is (str/includes? s "12.00 USDC"))))
(deftest missing-pool-is-not-zero
 (let [s (pr-str (ui/panel {:locale :ja :public-directory {:items [] :pool {:status "partial" :verifiedSubtotal {:pooled "5000000"}}}} {}))]
  (is (str/includes? s "未確認")) (is (str/includes? s "5.00 USDC")) (is (not (str/includes? s "0.00 USDC")))))
(run-tests)
