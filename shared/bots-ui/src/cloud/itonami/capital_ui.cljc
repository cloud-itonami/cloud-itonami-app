(ns cloud.itonami.capital-ui (:require [clojure.string :as str] [jp-go-dds.core :as dds]))
(defn usdc [value]
 (when value (let [s (str value) padded (str (apply str (repeat (max 0 (- 7 (count s))) "0")) s) n (count padded)]
  (str (subs padded 0 (- n 6)) "." (let [f (str/replace (subs padded (- n 6)) #"0+$" "")] (str f (apply str (repeat (max 0 (- 2 (count f))) "0")))) " USDC"))))
(def actions [["deposit" "預け入れ" "Deposit"] ["withdraw" "募集期間中の出金" "Withdraw before start"] ["start" "事業ラウンドを開始" "Start business round"] ["spend" "Botの事業支出" "Bot business spending"] ["repay" "元本返済・収益入金" "Repay / add income"] ["allocate" "未使用資金をAaveへ" "Allocate idle funds to Aave"] ["recall" "Aaveから回収" "Recall from Aave"] ["settle" "ラウンドを精算" "Settle round"] ["claim" "元本・分配金を受け取る" "Claim principal and distribution"] ["setExecutor" "Botの実行権限" "Bot spending authority"] ["harvest" "利益を回収しBot予算に分ける" "Realize yield and fund Bot budget"]])
(defn short-address [s] (when s (if (> (count s) 16) (str (subs s 0 6) "…" (subs s (- (count s) 4))) s)))
(defn panel [{:keys [locale capital capital-form capital-plan capital-status capital-error capital-busy? capital-pending] :as state} handlers]
 (let [ja? (= locale :ja) l (fn [ja en] (if ja? ja en)) f (or capital-form {})
       yield? (= "yield-budget-v1" (get-in capital [:settings :policy]))
       yield-new? (= "yield-budget-v1" (get-in state [:funding :terms :fundingPolicy]))
       deployed? (:vault capital) phase (str (get-in capital [:balances :phase] "0"))
       now #?(:cljs (quot (.now js/Date) 1000) :clj 0)
       deadline (get-in capital [:settings :fundingDeadline])
       open? (and (= phase "0") (or (nil? deadline) (< now deadline)))
       position (get-in capital [:balances :position]) has-position? (and position (not (re-matches #"0+" (str position))))
       a (if (and (= "withdraw" (:action f)) (= phase "0")) "withdraw" "deposit")
       admin-action (or (:admin-action f) "spend")
       field (fn [k ja en & [type]] [:label.bw-funding-field (l ja en)
         [:input (cond-> {:type (or type "text") :value (or (get f k) "") :on-change #((:capital-field handlers) k (.. % -target -value))}
           (= type "datetime-local") (assoc :on-input #((:capital-field handlers) k (.. % -target -value)))
           (contains? #{:amount :principal :income :fundingCap :cashReserve} k) (assoc :input-mode "decimal" :placeholder "0.00"))]])
       submit (fn [action ja en] (dds/button (l ja en) {:disabled (or capital-busy? (and (contains? #{"deposit" "withdraw" "allocate" "recall" "spend"} action) (not (re-matches #"[0-9]+(\.[0-9]{1,6})?" (or (:amount f) ""))))) :attrs {:on-click #((:capital-prepare handlers) action)}}))]
  [:section.bw-capital
   [:div.bw-capital-heading [:h2 (l "このBotに資金を貸す" "Lend to this Bot")]
    [:span.bw-capital-badge "USDC · Base"]]
   [:p.bw-capital-lead (l "Botの事業を支え、事業と未使用資金の運用から分配を受け取ります。" "Fund the business and receive distributions from its activity and idle capital.")]
   (when deployed? [:p.bw-capital-badge (if yield? (l "運用益型 · 元本は事業費に使いません" "Yield funded · principal cannot fund business costs") (l "事業貸付 · 元本を事業に利用" "Business loan · principal funds business"))])
   (when deployed? [:details.bw-capital-wallet [:summary (l "別の出資元を使う（Safe・任意）" "Use another funding wallet (Safe, optional)")]
    (if-let [account (:capital-safe state)]
     [:div [:p [:code (:address account)]]
      [:p (l "Base · 所有者の署名で実行。Botの自動決済権限は未接続です。" "Base · Owner-signed execution. Autonomous Bot spending is not connected.")]
      [:p (l "このSafeの残高・貸付持分を使います。新しい募集の作成時は、接続を解除して所有者ウォレットを使ってください。" "Uses this Safe’s balances and lending position. Disconnect to create a new round with the owner wallet.")]
      (dds/button (l "所有者ウォレットに戻す" "Use owner wallet") {:type :outline :attrs {:on-click (:capital-disconnect-safe handlers)}})]
     [:div (field :safe-address "BaseのSafeアドレス" "Safe address on Base")
      [:p (l "現在の所有者と署名条件をチェーンで確認します。接続だけでは送金もBotへの委任も行いません。" "Verifies current owners and threshold on-chain. Connecting does not transfer funds or delegate to a Bot.")]
      (dds/button (l "Safeを確認して接続" "Verify and connect Safe") {:disabled capital-busy? :attrs {:on-click (:capital-connect-safe handlers)}})])])
   (when capital-error [:p.bw-capital-notice {:role "alert"} capital-error])
   (when capital-status [:p {:role "status"} capital-status])
   (when capital-pending [:div.bw-capital-notice [:p (l "送信済みの取引があります。再送信せず、確定状況を確認してください。" "A transaction was submitted. Check confirmation before sending again.")]
    (dds/button (l "取引の確定を確認" "Check confirmation") {:disabled capital-busy? :attrs {:on-click (:capital-resume handlers)}})])
   (if deployed?
    [:div
     [:p.bw-capital-phase (cond open? (l "● 募集中" "● Open for funding") (= phase "0") (l "募集終了 · 開始待ち" "Funding closed · Awaiting start") (= phase "1") (l "運用中" "Operating") :else (l "精算済み" "Settled"))]
     [:ol.bw-capital-steps {:aria-label (l "資金の流れ" "Funding journey")}
      (for [[i ja en] [["0" "預ける" "Lend"] ["1" "Botが使う・運用する" "Work & earn"] ["2" "受け取る" "Receive"]]]
       [:li {:key i :aria-current (when (= phase i) "step")} (l ja en)])]
     [:div.bw-capital-balance [:span (l "あなたの貸付持分" "Your lending position")]
      [:strong (or (usdc position) (l "ウォレットでログインして確認" "Sign in with your wallet to view"))]
      (when-let [m (get-in capital [:settings :maturity])]
       [:p (str (l "満期：" "Maturity: ") #?(:cljs (.toLocaleDateString (js/Date. (* m 1000)) (if ja? "ja-JP" "en-US")) :clj m) (l " · 受取は精算後" " · Claim after settlement"))])
      [:p (l "元本と利回りは保証されません。" "Principal and yield are not guaranteed.")]]
     [:div.bw-capital-lender
      [:p (if (:capital-safe state) (l "確認済みのSafeから預けます。入金先はこの募集のコントラクトに自動設定されます。" "Deposit from your verified Safe. The destination is this round’s contract.") (l "接続中のウォレットから預けます。入金先のアドレス入力は不要です。" "Deposit from your connected wallet. No destination address is needed."))]
      [:ol [:li (l "金額を入力" "Enter amount")] [:li (l "契約条件を確認" "Review contract terms")] [:li (l "ウォレットで承認・入金" "Approve and deposit in your wallet")]]
      (cond
       (= phase "0") [:div
        [:div.bw-capital-actions
         (when open? (dds/button (l "預ける" "Lend") {:type (if (= a "deposit") :solid-fill :outline) :attrs {:aria-pressed (= a "deposit") :on-click #((:capital-field handlers) :action "deposit")}}))
         (when has-position? (dds/button (l "出金する" "Withdraw") {:type (if (= a "withdraw") :solid-fill :outline) :attrs {:aria-pressed (= a "withdraw") :on-click #((:capital-field handlers) :action "withdraw")}}))]
        (if (or open? (= a "withdraw")) [:div
         [:h3 (if (= a "withdraw") (l "いくら出金しますか？" "How much will you withdraw?") (l "いくら預けますか？" "How much will you lend?"))]
         (field :amount "金額（USDC）" "Amount (USDC)")
         (when (= a "deposit") [:div.bw-capital-presets (for [n ["10" "50" "100"]]
          (dds/button n {:key n :type :outline :attrs {:aria-label (str n " USDC") :on-click #((:capital-field handlers) :amount n)}}))])
         [:p.bw-capital-hint (l "ネットワークはBaseです。手数料用のETHも必要です。" "Use Base USDC and ETH for network fees.")]
         (submit a "内容を確認" "Review transaction")]
         [:p (l "募集は終了しました。運用開始までは、預けた資金を出金できます。" "Funding has closed. Existing lenders may withdraw until the round starts.")])]
       (= phase "1") [:div [:h3 (l "資金を運用しています" "Your capital is at work")]
        [:p (l "事業への貸付と、未使用資金のAave運用を確認できます。受け取りは満期後の精算が済んでからです。" "Track business lending and idle funds in Aave. Claims open after maturity and settlement.")]]
       :else [:div [:h3 (l "元本・分配金を受け取る" "Receive principal and distribution")]
        [:p (l "回収できた資金を、あなたの貸付持分に応じて受け取ります。" "Claim your share of recovered funds.")]
        (if has-position? (submit "claim" "受け取り内容を確認" "Review claim") [:p (l "受け取れる貸付持分はありません。" "There is no unclaimed lending position.")])])]
     [:details.bw-capital-breakdown [:summary (l "資金はどこで使われている？" "Where is the capital?")]
      [:dl (for [[k ja en] [[:principal "集まった元本" "Funded principal"] [:debt "Botが事業に使用中" "In use by the business"] [:idleAssets "Aaveで運用中" "Allocated to Aave"] [:cash "手元のUSDC" "Available USDC"] [:businessIncome "返済と一緒に受領した事業収益" "Business income received"] [:distributed "貸付者全体への分配済み額" "Distributed to all lenders"] [:writtenOff "確定した貸倒額" "Recognized default loss"]]]
       [:div {:key (name k)} [:dt (l ja en)] [:dd (or (usdc (get-in capital [:balances k])) (l "未確認" "Unverified"))]])]
      [:p (l "チェーン上の残高です。確定前の取引が含まれる場合があります。" "On-chain balances may include transactions awaiting finality.")]]
     [:details.bw-capital-terms [:summary (l "このラウンドに固定された条件" "Terms bound to this round")]
      [:p (str (l "条件バージョン：" "Terms version: ") (:termsVersion capital))]
      [:dl (for [[k ja en] [[:borrower "借入主体" "Borrower"] [:repayment "返済義務" "Repayment"] [:distribution "分配条件" "Distribution"] [:withdrawal "出金条件" "Withdrawal"] [:lossPolicy "損失負担" "Loss policy"] [:useOfFunds "使途" "Use of funds"]]]
       [:div {:key (name k)} [:dt (l ja en)] [:dd (get-in capital [:settings :registeredTerms k])]])]
      [:p (str (l "募集終了 / 満期（UTC）：" "Funding closes / maturity (UTC): ")
       #?(:cljs (when-let [n (get-in capital [:settings :fundingDeadline])] (.toISOString (js/Date. (* n 1000)))) :clj "") " / "
       #?(:cljs (when-let [n (get-in capital [:settings :maturity])] (.toISOString (js/Date. (* n 1000)))) :clj ""))]
      [:p (if yield? (str (l "運用益のBot配分は " "Bot share of realized surplus: ") (get-in capital [:settings :botShareBps]) (l " bps。残りは貸し手に留保します。未使用Bot予算と事業収益は精算時に分配。元本保証はなく、Aaveの出金制限で精算が遅れる場合があります。" " bps. The remainder is retained for lenders. Unused budget and business income are distributed at settlement. Principal is not guaranteed; Aave liquidity may delay settlement.")) (l "純収益は100%を持分に応じて分配します。未返済額は満期後7日の猶予後に損失として認識します。Aaveの流動性不足で精算が遅れることがあります。" "All net income is distributed pro rata. Unpaid debt is recognized as loss after the seven-day maturity grace. Aave illiquidity can delay settlement."))]
      [:p (str (l "日次上限 / 現金留保：" "Daily limit / cash reserve: ") (usdc (get-in capital [:settings :dailyLimit])) " / " (usdc (get-in capital [:settings :cashReserve])))]]

     [:details.bw-capital-technical {:open true} [:summary (l "募集方式・ラウンドを選ぶ" "Choose funding model and round")]
      [:label.bw-funding-field (l "貸付ラウンド" "Lending round") [:select {:value (:vault capital) :on-change #((:capital-round handlers) (.. % -target -value))}
       (for [[i round] (map-indexed vector (:rounds capital))] [:option {:key (:vault round) :value (:vault round)} (str (if (= "yield-budget-v1" (get-in round [:settings :policy])) (l "運用益型 · " "Yield funded · ") (l "事業貸付 · " "Business loan · ")) (l "ラウンド " "Round ") (inc i) " · " (short-address (:vault round)))])]]
      [:a {:href (str "https://basescan.org/address/" (:vault capital)) :target "_blank" :rel "noopener noreferrer"} (l "BaseScanで取引を見る ↗" "View transactions on BaseScan ↗")]]]
    (when (= "no-vault" (:status capital)) [:div.bw-capital-empty [:span.bw-capital-badge (l "募集前" "Not open yet")]
     [:h3 (l "運営側で入金用コントラクトを準備しています" "The operator is preparing the deposit contract")]
     [:p (l "アドレスを入力する必要はありません。コントラクトの公開後、この画面で金額・条件を確認し、ウォレットで承認して入金できます。現在はまだ入金できません。" "No address entry is needed. Once the contract is published, review the amount and terms here, then approve the deposit in your wallet. Deposits are not available yet.")]
     [:p (l "運営側が募集を公開すると、ここから金額を指定して預けられます。募集の作成は出資者の操作ではありません。" "The operator publishes the round. You then choose an amount and deposit here; investors do not create rounds.")]
     [:ol [:li (l "組織が返済・分配条件を公開" "The organization publishes terms")]
      [:li (l "あなたが金額と条件を確認して預ける" "You review terms and lend")]
      [:li (l "満期後の精算が済んだら受け取る" "You claim after maturity and settlement")]]]))
   (when capital-plan
    [:section.bw-capital-review {:aria-label (l "取引の確認" "Transaction review")}
     [:h3 {:tab-index -1} (l "ウォレットで確認する内容" "Review in your wallet")]
     [:p (str (:project capital-plan) " · Base USDC")]
     [:p (str (l "操作：" "Action: ") (or (some (fn [[id ja en]] (when (= id (:action capital-plan)) (l ja en))) actions) (if (= "deploy-launcher" (:action capital-plan)) (l "募集作成の初回権限を設定（入金ではありません）" "Set initial round-creation authority (not a deposit)") (l "募集を作成" "Create round"))))]
     [:p (str (l "使用するウォレット：" "Wallet: ") (short-address (get-in capital-plan [:transaction :from])))]
     (when (:review capital-plan) [:dl (for [[k value] (:review capital-plan) :when (not (contains? #{:termsHash :policy :controller :asset :vault} k))]
      [:div {:key (name k)} [:dt (get {:safe (l "利用するSafe" "Acting Safe") :signingOwner (l "署名する所有者" "Signing owner") :autonomousExecution (l "Botの自動実行" "Autonomous execution") :amount (l "金額（USDC）" "Amount (USDC)") :principal (l "返済元本（USDC）" "Principal (USDC)") :income (l "収益（USDC）" "Income (USDC)") :recipient (l "送金先" "Recipient") :payees (l "許可する送金先" "Approved payees") :fundingModel (l "募集方式" "Funding model") :botShareBps (l "利益のBot配分（bps）" "Bot yield share (bps)") :fundingCap (l "募集上限（USDC）" "Funding cap (USDC)") :cashReserve (l "現金留保（USDC）" "Cash reserve (USDC)") :fundingCloses (l "募集終了" "Funding closes") :maturity (l "満期" "Maturity") :executor (l "実行用アドレス" "Executor") :allowed (l "実行を許可" "Execution allowed") :intent (l "請求・タスクID" "Invoice/task ID")} k (name k))] [:dd (str value)]])])
     (when (= "deposit" (:action capital-plan)) [:div.bw-capital-notice
      [:p (l "このラウンドの返済・出金条件を確認してください。元本と利回りは保証されません。" "Review this round's repayment and withdrawal terms. Principal and yield are not guaranteed.")]
           [:details.bw-capital-terms [:summary (l "このラウンドに固定された条件" "Terms bound to this round")]
      [:p (str (l "条件バージョン：" "Terms version: ") (:termsVersion capital))]
      [:dl (for [[k ja en] [[:borrower "借入主体" "Borrower"] [:repayment "返済義務" "Repayment"] [:distribution "分配条件" "Distribution"] [:withdrawal "出金条件" "Withdrawal"] [:lossPolicy "損失負担" "Loss policy"] [:useOfFunds "使途" "Use of funds"]]]
       [:div {:key (name k)} [:dt (l ja en)] [:dd (get-in capital [:settings :registeredTerms k])]])]
      [:p (str (l "募集終了 / 満期（UTC）：" "Funding closes / maturity (UTC): ")
       #?(:cljs (when-let [n (get-in capital [:settings :fundingDeadline])] (.toISOString (js/Date. (* n 1000)))) :clj "") " / "
       #?(:cljs (when-let [n (get-in capital [:settings :maturity])] (.toISOString (js/Date. (* n 1000)))) :clj ""))]
      [:p (if yield? (str (l "運用益のBot配分は " "Bot share of realized surplus: ") (get-in capital [:settings :botShareBps]) (l " bps。残りは貸し手に留保します。未使用Bot予算と事業収益は精算時に分配。元本保証はなく、Aaveの出金制限で精算が遅れる場合があります。" " bps. The remainder is retained for lenders. Unused budget and business income are distributed at settlement. Principal is not guaranteed; Aave liquidity may delay settlement.")) (l "純収益は100%を持分に応じて分配します。未返済額は満期後7日の猶予後に損失として認識します。Aaveの流動性不足で精算が遅れることがあります。" "All net income is distributed pro rata. Unpaid debt is recognized as loss after the seven-day maturity grace. Aave illiquidity can delay settlement."))]
      [:p (str (l "日次上限 / 現金留保：" "Daily limit / cash reserve: ") (usdc (get-in capital [:settings :dailyLimit])) " / " (usdc (get-in capital [:settings :cashReserve])))]]
])
     [:ol.bw-capital-confirm-steps
      (when (:approval capital-plan) [:li (l "ウォレットで、この金額分のUSDC利用を許可" "Approve this USDC amount in your wallet")])
      [:li (l "取引を確認・署名" "Review and sign the transaction")]
      [:li (l "確定を待つ。画面に残高が反映されます" "Wait for confirmation and updated balances")]]
     [:div.bw-capital-actions (dds/button (l "戻って修正" "Edit") {:type :outline :disabled capital-busy? :attrs {:on-click (:capital-cancel handlers)}})
      (dds/button (l "ウォレットで確認" "Continue in wallet") {:disabled capital-busy? :attrs {:on-click (:capital-execute handlers)}})]])
   (when (:funding-operator? state) [:details.bw-capital-admin [:summary (l "運営者向け：募集・資金管理" "For operators: fundraising & management")]
    [:p (l "資金調達・Botの支出・運用の管理はこちら。操作にはウォレットの権限が必要です。" "Manage fundraising, Bot spending and allocation. Operations require the appropriate wallet authority.")]
    (when yield? [:div.bw-capital-notice
     [:strong (l "運用益型：元本の事業利用なし" "Yield funded: no principal spending")]
     [:p (str (l "利益のBot配分: " "Bot share of surplus: ") (get-in capital [:settings :botShareBps]) " bps")]
     [:p (str (l "使えるBot予算: " "Available Bot budget: ") (usdc (get-in capital [:balances :budgetCash])))]
     [:p (l "利益回収ではAaveの全額を一旦引き出します。元本と貸し手利益はVaultに残り、再運用は別の操作です。元本保証ではありません。" "Harvest first recalls all Aave assets. Principal and lender yield remain in the vault; resupply is a separate action. Principal is not guaranteed.")]])
    (when (get-in capital [:operatorGrant :launcher]) [:p {:role "status"} (l "初回の運営権限は確定済みです。Bot署名サービスの接続・募集作成が完了するまで入金はできません。" "Initial operator authority is confirmed. Deposits wait for operator signer connection and round creation.")])
    (:funding-editor state)
    (when deployed? [:div
     [:label.bw-funding-field (l "管理する操作" "Management action") [:select {:aria-label (l "管理する操作" "Management action") :value admin-action :on-change #((:capital-field handlers) :admin-action (.. % -target -value))}
      (for [[id ja en] (drop 2 actions) :when (and (not= id "claim") (or (not= id "harvest") yield?))] [:option {:key id :value id} (l ja en)])]]
     (when (contains? #{"spend" "allocate" "recall"} admin-action) (field :amount "金額（USDC）" "Amount (USDC)"))
     (when (= admin-action "spend") [:div (field :recipient "登録済みの送金先" "Approved recipient") (field :intent "請求・タスクの識別ハッシュ（0x…）" "Invoice/task hash (0x…)")])
     (when (= admin-action "repay") [:div (when-not yield? (field :principal "返済する元本（USDC）" "Principal repayment (USDC)")) (field :income "事業収益（USDC、なしは0）" "Income (USDC; 0 if none)")])
     (when (= admin-action "setExecutor") [:div (field :executor "Botの実行用アドレス" "Bot executor address") [:label [:input {:type "checkbox" :checked (boolean (:allowed f)) :on-change #((:capital-field handlers) :allowed (.. % -target -checked))}] (l "実行を許可する（オフで撤回）" "Grant execution (off revokes)")]])
     (submit admin-action "管理操作を確認" "Review management action")])
    [:details [:summary (l "2. 運営Botへ募集作成を委任" "2. Delegate round creation to the operator Bot")]
     (when yield-new? [:p (str (l "運用益型で作成。Bot配分 " "Create a yield-funded round. Bot share ") (get-in state [:funding :terms :botShareBps]) (l " bps。元本支出は禁止し、未使用Bot予算は満期に戻します。" " bps. Principal spending is prohibited; unused Bot budget returns at maturity."))])
     (when-not yield-new? [:p (l "先にこのウォレットで組織の条件を登録してください。V1は、募集終了後に支出を開始し、満期に全額を精算する方式です。純収益の100%を貸付持分で分配します。満期後7日を過ぎた未返済額は貸倒として認識し、回収済み資産だけを分配します。Aaveの流動性不足は精算を遅らせます。" "Register the organization terms with this wallet first. V1 starts spending after fundraising closes and settles at maturity. All net income is distributed pro rata to lenders. Unpaid debt after the seven-day grace is recognized as a loss; only recovered assets are distributed. Aave illiquidity can delay settlement.")])
     (field :executor "運営Botの実行用アドレス" "Operator Bot executor")
     [:p (l "この初回設定では条件を固定し、Botに募集1件の作成を許可します。入金は募集が実際に公開された後です。" "This initial setup fixes the terms and permits the Bot to create one round. Deposits follow actual publication.")]
     (field :fundingCap "募集上限（USDC）" "Funding cap (USDC)") (field :cashReserve "現金で残す額（USDC）" "Cash reserve (USDC)")
     (field :fundingDeadline "募集終了" "Funding closes" "datetime-local") (field :maturity "返済期限" "Repayment maturity" "datetime-local")
     (field :recipients "許可する送金先（アドレスを空白で区切る）" "Approved recipients (space-separated addresses)")
     [:label [:input {:type "checkbox" :checked (boolean (:policy f)) :on-change #((:capital-field handlers) :policy (.. % -target -checked))}] (l "上記の精算方式を、このラウンドの条件として確認しました" "I accept the stated settlement policy for this round")]
     (dds/button (if yield-new? (l "初回の運営権限を確認" "Review initial operator authority") (l "元本利用型の募集を確認" "Review business loan round")) {:disabled capital-busy? :attrs {:on-click #((:capital-prepare handlers) (if yield-new? "deploy-launcher" "deploy"))}})]
   ])
   (dds/button (l "最新の状況に更新" "Refresh status") {:type :text :attrs {:on-click (:capital-refresh handlers)}})]))
