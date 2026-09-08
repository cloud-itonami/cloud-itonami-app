(ns cloud.itonami.funding-role-test (:require [cljs.test :refer [deftest is run-tests]] [clojure.string :as str] [cloud.itonami.capital-ui :as ui]))
(deftest investor-never-sees-launch-or-grant-forms
 (let [render #(pr-str (ui/panel (merge {:locale :ja :capital {:status "no-vault"} :funding {:terms {:fundingPolicy "yield-budget-v1"}}} %) {}))
       investor (render {}) operator (render {:funding-operator? true})]
  (is (str/includes? investor "募集の作成は出資者の操作ではありません"))
  (is (not (str/includes? investor "初回の運営権限を確認")))
  (is (not (str/includes? investor "許可する送金先")))
  (is (str/includes? operator "初回の運営権限を確認"))))
(run-tests)
