(ns cloud.itonami.funding-role-test (:require [cljs.test :refer [deftest is run-tests]] [clojure.string :as str] [cloud.itonami.capital-ui :as ui]))
(deftest investor-never-sees-launch-or-grant-forms
 (let [render #(pr-str (ui/panel (merge {:locale :ja :capital {:status "no-vault"} :funding {:terms {:fundingPolicy "yield-budget-v1"}}} %) {}))
       investor (render {}) operator (render {:funding-operator? true})]
  (is (str/includes? investor "募集の作成は出資者の操作ではありません"))
  (is (not (str/includes? investor "初回の運営権限を確認")))
  (is (not (str/includes? investor "許可する送金先")))
  (is (str/includes? operator "初回の運営権限を確認"))))
(deftest deposit-does-not-require-a-safe
 (let [render #(pr-str (ui/panel {:locale :ja :capital %} {}))
       pending (render {:status "no-vault"})
       ready (render {:vault "0x123" :balances {:phase "0"}})]
  (is (not (str/includes? pending "BaseのSafeアドレス")))
  (is (str/includes? pending "現在はまだ入金できません"))
  (is (str/includes? ready "入金先のアドレス入力は不要"))
  (is (str/includes? ready "別の出資元を使う（Safe・任意）"))))
(run-tests)
