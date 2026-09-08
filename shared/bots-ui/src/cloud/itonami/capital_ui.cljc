(ns cloud.itonami.capital-ui (:require [clojure.string :as str] [jp-go-dds.core :as dds]))
(defn usdc [value]
 (when value (let [s (str value) padded (str (apply str (repeat (max 0 (- 7 (count s))) "0")) s) n (count padded)]
  (str (subs padded 0 (- n 6)) "." (subs padded (- n 6)) " USDC"))))
(def actions [["deposit" "預け入れ" "Deposit"] ["withdraw" "募集期間中の出金" "Withdraw before start"] ["start" "事業ラウンドを開始" "Start business round"] ["spend" "Botの事業支出" "Bot business spending"] ["repay" "元本返済・収益入金" "Repay / add income"] ["allocate" "未使用資金をAaveへ" "Allocate idle funds to Aave"] ["recall" "Aaveから回収" "Recall from Aave"] ["settle" "ラウンドを精算" "Settle round"] ["claim" "元本・分配金を受け取る" "Claim principal and distribution"] ["setExecutor" "Botの実行権限" "Bot spending authority"]])
(defn panel [{:keys [locale capital capital-form capital-plan capital-status capital-error capital-busy? capital-pending]} handlers]
 (let [ja? (= locale :ja) l (fn [ja en] (if ja? ja en)) f (or capital-form {})
       field (fn [k ja en & [type]] [:label.bw-funding-field (l ja en) [:input {:type (or type "text") :value (or (get f k) "") :on-change #((:capital-field handlers) k (.. % -target -value))}]])
       deployed? (:vault capital) a (or (:action f) "deposit")]
  [:section.bw-capital
   [:h2 (l "事業資金" "Business capital")]
   [:p (l "Base · USDC · 未使用資金はAave V3。送金にはウォレットで取引内容の確認が必要です。" "Base · USDC · Aave V3 for idle capital. Review each transaction in your wallet.")]
   (when capital-error [:p {:role "alert"} capital-error])
   (when capital-status [:p {:role "status"} capital-status])
   (when capital-pending (dds/button (l "送信済み取引の確定状況を再確認" "Resume submitted transaction confirmation") {:disabled capital-busy? :attrs {:on-click (:capital-resume handlers)}}))
   (if deployed?
    [:div
     [:label (l "貸付ラウンド" "Lending round") [:select {:value (:vault capital) :on-change #((:capital-round handlers) (.. % -target -value))}
      (for [round (:rounds capital)] [:option {:key (:vault round) :value (:vault round)} (:vault round)])]]
     [:p [:a {:href (str "https://basescan.org/address/" (:vault capital)) :target "_blank" :rel "noopener noreferrer"} (l "コントラクト・取引履歴" "Contract and transactions")]]
     [:details {:open true} [:summary (l "このラウンドに固定された条件" "Terms bound to this round")]
      [:p (str (l "条件バージョン：" "Terms version: ") (:termsVersion capital))]
      [:dl (for [[k ja en] [[:borrower "借入主体" "Borrower"] [:repayment "返済義務" "Repayment"] [:distribution "分配条件" "Distribution"] [:withdrawal "出金条件" "Withdrawal"] [:lossPolicy "損失負担" "Loss policy"] [:useOfFunds "使途" "Use of funds"]]]
       [:div {:key (name k)} [:dt (l ja en)] [:dd (get-in capital [:settings :registeredTerms k])]])]
      [:p (str (l "募集終了 / 満期（UTC）：" "Funding closes / maturity (UTC): ")
       #?(:cljs (when-let [n (get-in capital [:settings :fundingDeadline])] (.toISOString (js/Date. (* n 1000)))) :clj "") " / "
       #?(:cljs (when-let [n (get-in capital [:settings :maturity])] (.toISOString (js/Date. (* n 1000)))) :clj ""))]
      [:p (l "純収益は100%を持分に応じて分配します。未返済額は満期後7日の猶予後に損失として認識します。Aaveの流動性不足で精算が遅れることがあります。" "All net income is distributed pro rata. Unpaid debt is recognized as loss after the seven-day maturity grace. Aave illiquidity can delay settlement.")]
      [:p (str (l "日次上限 / 現金留保：" "Daily limit / cash reserve: ") (usdc (get-in capital [:settings :dailyLimit])) " / " (usdc (get-in capital [:settings :cashReserve])))]]
     [:p (l "表示はチェーン上の残高です。未確定の取引が含まれる場合があります。" "Balances are read from the chain and may include transactions awaiting finality.")]
     [:dl (for [[k ja en] [[:position "自分の貸付持分" "My lending position"] [:principal "ラウンド元本" "Round principal"] [:cash "利用可能な現金" "Cash"] [:debt "Botへの貸付残高" "Business debt"] [:idleAssets "Aave運用残高" "Aave assets"] [:businessIncome "返済時の事業収益" "Business income received"] [:distributed "分配済み" "Distributed"] [:writtenOff "認識済み貸倒額" "Recognized default loss"]]]
      [:div {:key (name k)} [:dt (l ja en)] [:dd (or (usdc (get-in capital [:balances k])) (l "未確認" "Unverified"))]])]
     [:label.bw-funding-field (l "操作" "Action") [:select {:aria-label (l "操作" "Action") :value a :on-change #((:capital-field handlers) :action (.. % -target -value))}
      (for [[id ja en] actions] [:option {:key id :value id} (l ja en)])]]
     (when (contains? #{"deposit" "withdraw" "spend" "allocate" "recall"} a) (field :amount "金額（USDC）" "Amount (USDC)"))
     (when (= a "spend") [:div (field :recipient "登録済みの送金先" "Approved recipient") (field :intent "請求・タスクの識別ハッシュ（0x…）" "Invoice/task hash (0x…)")])
     (when (= a "repay") [:div (field :principal "返済する元本（USDC）" "Principal repayment (USDC)") (field :income "事業収益（USDC、なしは0）" "Income (USDC; 0 if none)")])
     (when (= a "setExecutor") [:div (field :executor "Botの実行用アドレス" "Bot executor address") [:label [:input {:type "checkbox" :checked (boolean (:allowed f)) :on-change #((:capital-field handlers) :allowed (.. % -target -checked))}] (l "実行を許可する（オフで撤回）" "Grant execution (off revokes)")]])
     (dds/button (l "内容を確認" "Review transaction") {:disabled capital-busy? :attrs {:on-click #((:capital-prepare handlers) a)}})]
    [:details [:summary (l "組織管理者：貸付ラウンドを作成" "Organization administrator: create lending round")]
     [:p (l "先にこのウォレットで組織の条件を登録してください。V1は、募集終了後に支出を開始し、満期に全額を精算する方式です。純収益の100%を貸付持分で分配します。満期後7日を過ぎた未返済額は貸倒として認識し、回収済み資産だけを分配します。Aaveの流動性不足は精算を遅らせます。" "Register the organization terms with this wallet first. V1 starts spending after fundraising closes and settles at maturity. All net income is distributed pro rata to lenders. Unpaid debt after the seven-day grace is recognized as a loss; only recovered assets are distributed. Aave illiquidity can delay settlement.")]
     (field :fundingCap "募集上限（USDC）" "Funding cap (USDC)") (field :cashReserve "現金で残す額（USDC）" "Cash reserve (USDC)")
     (field :fundingDeadline "募集終了" "Funding closes" "datetime-local") (field :maturity "返済期限" "Repayment maturity" "datetime-local")
     (field :recipients "許可する送金先（アドレスを空白で区切る）" "Approved recipients (space-separated addresses)")
     [:label [:input {:type "checkbox" :checked (boolean (:policy f)) :on-change #((:capital-field handlers) :policy (.. % -target -checked))}] (l "上記の精算方式を、このラウンドの条件として確認しました" "I accept the stated settlement policy for this round")]
     (dds/button (l "ラウンドの内容を確認" "Review new round") {:disabled capital-busy? :attrs {:on-click #((:capital-prepare handlers) "deploy")}})])
   (when capital-plan
    [:section {:aria-label (l "取引の確認" "Transaction review")}
     [:h3 (l "ウォレットで確認する内容" "Review in your wallet")]
     [:p (str (:project capital-plan) " · " (:action capital-plan) " · Base / USDC")]
     [:p (str (l "操作するアドレス：" "Account: ") (get-in capital-plan [:transaction :from]))]
     (when (:review capital-plan) [:dl (for [[k value] (:review capital-plan)] [:div {:key (name k)} [:dt (name k)] [:dd (str value)]])])
     (when (:approval capital-plan) [:p (l "最初に、この操作の金額分だけUSDCの利用を許可します。その後、取引を確認します。" "First approve only the USDC amount for this operation, then confirm the transaction.")])
     [:p (l "元本や利回りは保証されません。組織の条件と、このラウンドの精算方式を確認してください。" "Principal and yield are not guaranteed. Review the organization's terms and this round's settlement policy.")]
     (dds/button (l "ウォレットで実行" "Execute with wallet") {:disabled capital-busy? :attrs {:on-click (:capital-execute handlers)}})])
   (dds/button (l "残高を更新" "Refresh balances") {:type :text :attrs {:on-click (:capital-refresh handlers)}})]))
