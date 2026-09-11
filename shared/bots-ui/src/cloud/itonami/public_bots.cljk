(ns cloud.itonami.public-bots
 (:require [kotoba.lang.text :as str] [jp-go-dds.core :as dds] [cloud.itonami.capital-ui :as capital]))
(defn funding-editor [{:keys [locale funding-form funding-saving? funding-error]} handlers]
 (let [label (fn [ja en] (if (= locale :ja) ja en))]
[:details [:summary (label "1. 募集条件を登録" "1. Register funding terms")]
       [:p (label "この公開リポジトリの管理者であるGitHubアカウントをプラグインで接続してください。登録内容は公開されます。" "Connect a GitHub plugin account with administrator access to this public repository. Submitted terms are public.")]
       [:form {:on-submit (fn [event] (.preventDefault event) ((:funding-save handlers)))}
        [:label.bw-funding-field (label "募集方式" "Funding model") [:select {:value (or (:fundingPolicy funding-form) "fixed-round-net-income-v1") :on-change #((:funding-field handlers) :fundingPolicy (.. % -target -value))}
         [:option {:value "fixed-round-net-income-v1"} (label "事業貸付：元本を事業に使う" "Business loan: spend principal")]
         [:option {:value "yield-budget-v1"} (label "運用益型：利益だけをBot予算にする" "Yield funded: only realized surplus funds Bot")]]]
        (when (= "yield-budget-v1" (:fundingPolicy funding-form)) [:div
         [:p (label "元本の事業支出は禁止。回収済みの利益をBot予算と貸し手分配に分けます。USDC・Aaveの損失リスクは残ります。" "Principal cannot fund business spending. Realized surplus is split between Bot budget and lenders. USDC and Aave loss risks remain.")]
         [:label.bw-funding-field (label "利益のBot配分（bps、5000＝50%）" "Bot share of surplus (bps; 5000 = 50%)") [:input {:required true :type "number" :min 1 :max 10000 :step 1 :value (or (:botShareBps funding-form) "") :on-change #((:funding-field handlers) :botShareBps (.. % -target -value))}]]])
        (for [[k ja en] [[:borrower "返済義務を負う法人・組織" "Responsible borrower"] [:repayment "返済期限・返済義務" "Repayment obligation and maturity"] [:distribution "事業収益・DeFi収益の分配条件" "Business and DeFi income distribution"] [:withdrawal "出金条件・待機期間" "Withdrawal conditions and waiting period"] [:lossPolicy "損失・債務不履行時の扱い" "Loss and default policy"] [:useOfFunds "Botが使える用途・送金先の範囲" "Permitted Bot spending and recipients"]]]
         [:label.bw-funding-field {:key (name k)} (label ja en) [:textarea {:required true :min-length 8 :max-length 2000 :value (or (get funding-form k) "") :on-change #((:funding-field handlers) k (.. % -target -value))}]])
        [:label.bw-funding-field (label "1日あたりの利用上限（USDC）" "Daily spending limit (USDC)") [:input {:required true :input-mode "decimal" :value (or (:dailyLimitUSDC funding-form) "") :on-change #((:funding-field handlers) :dailyLimitUSDC (.. % -target -value))}]]
        [:p (label "BaseのUSDCで募集します。未使用資金の運用先はAave V3です。" "Raise Base USDC. Idle funds can be allocated to Aave V3.")]
        [:label [:input {:type "checkbox" :required true :checked (boolean (:acceptPublication funding-form)) :on-change #((:funding-field handlers) :acceptPublication (.. % -target -checked))}] (label "この事業の条件として公開する" "Publish these terms for this project")]
        (when funding-error [:p {:role "alert"} funding-error])
        (dds/button (label "条件を登録" "Register terms") {:submit? true :disabled funding-saving?})]]))
(defn panel [{:keys [locale public-directory public-query public-org public-funding public-selected public-loading? public-error funding funding-form funding-saving? funding-error] :as state} handlers]
 (let [ja? (= locale :ja) label (fn [ja en] (if ja? ja en))
       items (:items public-directory)
       matches (filter #(and (or (str/blank? public-org) (= public-org (:org %)))
                              (str/includes? (str/lower (str (:id %) " " (:name %) " " (:description %))) (str/lower (or public-query "")))) items)
       status-of #(let [s (get-in % [:funding :status])] (if (#{"accepting" "not-accepting"} s) s "unknown"))
       matches-status? (fn [item value] (case value
        "" true "lending" (true? (get-in item [:funding :lending]))
        "funded" (true? (get-in item [:funding :funded])) (= value (status-of item))))
       filtered (filter #(matches-status? % (or public-funding "")) matches)
       statuses [["" "すべて" "All"] ["lending" "あなたが貸付中" "Your lending"] ["funded" "資金を預かり中" "Holding lender funds"] ["accepting" "募集中" "Accepting funding"] ["not-accepting" "募集していない" "Not accepting"] ["unknown" "未確認" "Unverified"]]
       selected (first (filter #(= public-selected (:id %)) items))]
  [:section.bw-public
   [:div.bw-plugin-heading
    (dds/button "←" {:type :text :attrs {:on-click (:public-back handlers)} :aria-label (label "自分のBotへ戻る" "Back to My Bots")})
    [:h1 (label "公開組織・Bot" "Public organizations & Bots")]]
   (when-not selected [:p (label "応援したいBotの事業を探す。募集条件を確認して、USDCで資金を貸せます。" "Find a Bot business to support. Review its funding terms and lend USDC.")])
   (when-not selected
    [:section.bw-capital-balance {:aria-label (label "全体の資金プール" "Total capital pool")}
     (when-let [safe (get-in state [:capital-safe :address])] [:p (str (label "貸付元のSafe：" "Lending Safe: ") (capital/short-address safe))])
     [:span (label "全体のプール残高" "Total pooled assets")]
     [:strong (or (capital/usdc (get-in public-directory [:pool :totals :pooled])) (label "未確認" "Unverified"))]
     [:p (label "保管中のUSDC・Aave資産・未使用Bot予算の合計。事業に支出した資金は含みません。" "USDC held, Aave assets and unused Bot budgets. Business spending is excluded.")]
     (when (= "partial" (get-in public-directory [:pool :status]))
      [:p {:role "status"} (str (label "一部を取得できません。確認できた残高：" "Some rounds are unavailable. Verified subtotal: ") (capital/usdc (get-in public-directory [:pool :verifiedSubtotal :pooled])))])
     [:dl (for [[k ja en] [[:outstanding "未返還の元本持分" "Unclaimed principal positions"] [:idleAssets "Aaveで運用中" "In Aave"] [:budgetCash "未使用Bot予算" "Unused Bot budgets"] [:debt "事業の未返済額" "Business debt"] [:distributed "分配済み" "Distributed"]]]
      [:div {:key (name k)} [:dt (label ja en)] [:dd (or (capital/usdc (get-in public-directory [:pool :totals k])) (label "未確認" "Unverified"))]])]
     (when (:asOfBlock public-directory) [:p (str (label "確認ブロック：" "Verified block: ") (:asOfBlock public-directory))])
     (when-not (:personalized public-directory) [:p (label "ウォレットでログインすると、あなたが貸付中のBotを確認できます。" "Sign in with your wallet to see Bots you lend to.")])])
   (when public-error [:p {:role "alert"} public-error])
   (if public-loading? [:p {:role "status"} (label "公開一覧を読み込み中…" "Loading directory…")]
    (if selected
     [:article
      (dds/button (label "一覧へ" "Back to directory") {:type :text :attrs {:on-click #((:public-select handlers) nil)}})
      [:p [:code (:id selected)]] [:h2 (:name selected)]
      (capital/panel (assoc state :funding-editor (funding-editor state handlers)) handlers)
      [:details.bw-project-description [:summary (label "この事業について" "About this business")] [:p (:description selected)]]
      [:div.bw-actions (dds/button (label "公開サイトを見る" "Public website") {:href (:url selected)})
       (dds/button "GitHub" {:type :outline :href (:source selected)})]
      [:p (label "Botが事業に使う資金と、使っていない間のDeFi運用を同じ事業で管理します。" "Business lending and idle DeFi allocations belong to the same project.")]
      (when (:terms funding)
       [:details [:summary (label "組織が登録した条件" "Organization-registered terms")]
        [:dl (for [[k ja en] [[:borrower "返済義務を負う主体" "Borrower"] [:repayment "返済条件" "Repayment"] [:distribution "分配条件" "Distribution"] [:withdrawal "出金条件" "Withdrawal"] [:lossPolicy "損失の扱い" "Loss policy"] [:useOfFunds "資金用途" "Use of funds"] [:idleStrategy "未使用資金の運用先" "Idle strategy"] [:dailyLimitUSDC "1日あたりの利用上限（USDC）" "Daily limit (USDC)"] [:chainId "チェーンID" "Chain ID"]]]
         [:div {:key (name k)} [:dt (label ja en)] [:dd (str (get-in funding [:terms k]))]])]
        [:p (if (get-in state [:capital :vault]) (label "条件に結び付いた貸付ラウンドがあります。" "A verified lending round is linked to registered terms.") (label "条件登録済み。運営側で募集の公開を準備しています。" "Terms registered. The operator is preparing the round."))]])
      ]
     [:div
      [:div.bw-plugin-filters
       [:label (label "検索" "Search") [:input {:type "search" :value (or public-query "") :on-change (:public-query handlers) :placeholder "org / repo"}]]
       [:label (label "組織" "Organization") [:select {:value (or public-org "") :on-change (:public-org handlers)}
        [:option {:value ""} (label "すべての組織" "All organizations")]
        (for [org (:orgs public-directory)] [:option {:key (:id org) :value (:id org)} (str (:id org) " (" (:count org) ")")])]]]
      [:div.bw-funding-filters {:role "group" :aria-label (label "資金の受付状況" "Funding availability")}
       (for [[value ja en] statuses]
        (dds/button (str (label ja en) " (" (count (filter #(matches-status? % value) matches)) ")")
         {:type (if (= value (or public-funding "")) :solid-fill :outline)
          :attrs {:key value :aria-pressed (= value (or public-funding "")) :on-click #((:public-funding handlers) value)}}))]
      [:p (label "受付状況は表示時点の情報です。入金前に最新の条件を確認してください。" "Availability is checked when loaded. Review current terms before depositing.")]
      [:p {:role "status"} (str (count filtered) (label "件" " projects"))]
      (for [item filtered]
       [:article.bw-public-card {:key (:id item)}
        [:p [:code (:id item)]] [:h2 (:name item)] [:p.bw-public-excerpt (:description item)]
        [:p (str/join " / " (distinct (map #(if (= "yield-budget-v1" (:policy %)) (label "運用益型" "Yield funded") (label "事業貸付" "Business loan")) (get-in item [:funding :rounds]))))]
        (when (true? (get-in item [:funding :lending])) [:p.bw-capital-badge (str (label "あなたが貸付中 · " "Your lending · ") (capital/usdc (get-in item [:funding :position])))])
        (when-let [pooled (get-in item [:funding :pool :totals :pooled])] [:p (str (label "プール残高：" "Pooled assets: ") (capital/usdc pooled))])
        [:p.bw-capital-badge (case (status-of item) "accepting" (label "募集中 · USDC" "Accepting funding · USDC") "not-accepting" (label "現在は募集していません" "Not accepting funding") (label "受付状況を確認できません" "Funding availability unverified"))]
        (dds/button (label "事業・資金情報を見る" "Business & funding details") {:type :outline :attrs {:on-click #((:public-select handlers) (:id item))}})])
      (when (empty? filtered) [:p (label "該当する公開事業がありません。" "No public projects match.")])]))]))
(def base-css ".bw-public{max-width:64rem;margin:auto;padding:var(--hig-spacing-4);overflow:auto;height:calc(100dvh - 5rem)}.bw-public input{font:inherit;padding:var(--hig-spacing-3);min-height:44px}.bw-public-card{padding:var(--hig-spacing-4) 0;border-bottom:1px solid var(--hig-color-separator)}.bw-public-card p{overflow-wrap:anywhere}.bw-funding-field{display:block;margin-block:var(--hig-spacing-4)}.bw-funding-field textarea{display:block;box-sizing:border-box;width:100%;min-height:6rem;font:inherit}.bw-capital select,.bw-funding-field input{max-width:100%;box-sizing:border-box;width:100%}.bw-capital dd{overflow-wrap:anywhere}.bw-public dt{font-weight:bold;margin-top:var(--hig-spacing-4)}.bw-public dd{margin-inline-start:0}.bw-public .bw-plugin-filters{flex-wrap:wrap}")

(def mobile-capital-css ".bw-public-excerpt{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}.bw-public h2{font-size:var(--hig-text-title3-font-size);line-height:1.5}.bw-project-description{margin-block:var(--hig-spacing-4)}.bw-public summary{padding-block:var(--hig-spacing-3);cursor:pointer;min-height:44px;box-sizing:border-box;font-weight:600}.bw-capital{margin-block:var(--hig-spacing-5);display:grid;gap:var(--hig-spacing-3)}.bw-capital-heading{display:flex;align-items:center;gap:var(--hig-spacing-3);flex-wrap:wrap}.bw-capital-heading h2{margin:0}.bw-capital-badge{display:inline-block;padding:var(--hig-spacing-1) var(--hig-spacing-2);border-radius:var(--hig-radius-sm);background:var(--hig-color-secondary-system-background);font-size:var(--hig-text-caption1-font-size)}.bw-capital-lead,.bw-capital-hint{color:var(--hig-color-secondary-label)}.bw-capital-balance,.bw-capital-empty,.bw-capital-lender,.bw-capital-review{padding:var(--hig-spacing-4);border:1px solid var(--hig-color-separator);border-radius:var(--hig-radius-md);margin-block:var(--hig-spacing-3);background:var(--hig-color-system-background)}.bw-capital-balance strong{display:block;font-size:var(--hig-text-title2-font-size);margin-top:var(--hig-spacing-2);overflow-wrap:anywhere}.bw-capital-balance p{margin-bottom:0}.bw-capital-phase{font-weight:700;color:var(--hig-color-tint)}.bw-capital-steps{display:flex;list-style:none;padding:0;gap:var(--hig-spacing-2);font-size:var(--hig-text-footnote-font-size)}.bw-capital-steps li{flex:1;padding-block:var(--hig-spacing-2);border-bottom:3px solid var(--hig-color-separator)}.bw-capital-steps li[aria-current]{border-color:var(--hig-color-tint);font-weight:700}.bw-capital-actions,.bw-capital-presets{display:flex;gap:var(--hig-spacing-2);flex-wrap:wrap}.bw-capital-actions>*{flex:1}.bw-capital-presets>*{flex:1;padding-inline:var(--hig-spacing-2)}.bw-capital input,.bw-capital select{font-size:var(--hig-text-body-font-size);min-height:48px}.bw-capital-lender button,.bw-capital-review button{min-height:48px}.bw-capital-lender>div>button{width:100%}.bw-capital-review{border:2px solid var(--hig-color-tint);scroll-margin-block-start:var(--hig-spacing-3)}.bw-capital-notice{padding:var(--hig-spacing-3);background:var(--hig-color-secondary-system-background);border-radius:var(--hig-radius-sm)}.bw-capital details{border-bottom:1px solid var(--hig-color-separator)}.bw-capital-admin{margin-top:var(--hig-spacing-5)}.bw-capital dl>div{display:flex;justify-content:space-between;gap:var(--hig-spacing-3);padding-block:var(--hig-spacing-2);flex-wrap:wrap}.bw-capital dt{margin:0}.bw-capital dd{max-width:100%;margin:0}.bw-capital-review h3:focus-visible{outline:2px solid var(--hig-color-tint)}")
(def css (str base-css mobile-capital-css ".bw-funding-filters{display:flex;flex-wrap:wrap;gap:var(--hig-spacing-2);margin-block:var(--hig-spacing-4)}.bw-funding-filters button{min-height:48px;flex:1 1 auto}"))
