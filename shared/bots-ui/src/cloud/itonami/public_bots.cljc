(ns cloud.itonami.public-bots
 (:require [clojure.string :as str] [jp-go-dds.core :as dds] [cloud.itonami.capital-ui :as capital]))
(defn panel [{:keys [locale public-directory public-query public-org public-selected public-loading? public-error funding funding-form funding-saving? funding-error] :as state} handlers]
 (let [ja? (= locale :ja) label (fn [ja en] (if ja? ja en))
       items (:items public-directory)
       filtered (filter #(and (or (str/blank? public-org) (= public-org (:org %)))
                              (str/includes? (str/lower-case (str (:id %) " " (:name %) " " (:description %))) (str/lower-case (or public-query "")))) items)
       selected (first (filter #(= public-selected (:id %)) items))]
  [:section.bw-public
   [:div.bw-plugin-heading
    (dds/button "←" {:type :text :attrs {:on-click (:public-back handlers)} :aria-label (label "自分のBotへ戻る" "Back to My Bots")})
    [:h1 (label "公開組織・Bot" "Public organizations & Bots")]]
   [:p (label "公開されている事業を組織・リポジトリ別に探せます。公開されていることと、Botの稼働・資金運用が確認済みであることは別です。" "Explore public businesses by organization and repository. Listing does not establish active Bot execution or verified financing.")]
   (when public-error [:p {:role "alert"} public-error])
   (if public-loading? [:p {:role "status"} (label "公開一覧を読み込み中…" "Loading directory…")]
    (if selected
     [:article
      (dds/button (label "一覧へ" "Back to directory") {:type :text :attrs {:on-click #((:public-select handlers) nil)}})
      [:p [:code (:id selected)]] [:h2 (:name selected)] [:p (:description selected)]
      [:div.bw-actions (dds/button (label "公開サイトを見る" "Public website") {:href (:url selected)})
       (dds/button "GitHub" {:type :outline :href (:source selected)})]
      [:p (label "Botが事業に使う資金と、使っていない間のDeFi運用を同じ事業で管理します。" "Business lending and idle DeFi allocations belong to the same project.")]
      (when (:terms funding)
       [:div [:h3 (label "組織が登録した条件" "Organization-registered terms")]
        [:dl (for [[k ja en] [[:borrower "返済義務を負う主体" "Borrower"] [:repayment "返済条件" "Repayment"] [:distribution "分配条件" "Distribution"] [:withdrawal "出金条件" "Withdrawal"] [:lossPolicy "損失の扱い" "Loss policy"] [:useOfFunds "資金用途" "Use of funds"] [:idleStrategy "未使用資金の運用先" "Idle strategy"] [:dailyLimitUSDC "1日あたりの利用上限（USDC）" "Daily limit (USDC)"] [:chainId "チェーンID" "Chain ID"]]]
         [:div {:key (name k)} [:dt (label ja en)] [:dd (str (get-in funding [:terms k]))]])]
        [:p (if (get-in state [:capital :vault]) (label "条件に結び付いた貸付ラウンドがあります。" "A verified lending round is linked to registered terms.") (label "条件登録済み。下のフォームから貸付ラウンドを作成できます。" "Terms registered. Create a lending round below."))]])
      [:details [:summary (label "組織管理者：貸付条件を登録" "Organization administrator: register lending terms")]
       [:p (label "この公開リポジトリの管理者であるGitHubアカウントをプラグインで接続してください。登録内容は公開されます。" "Connect a GitHub plugin account with administrator access to this public repository. Submitted terms are public.")]
       [:form {:on-submit (fn [event] (.preventDefault event) ((:funding-save handlers)))}
        (for [[k ja en] [[:borrower "返済義務を負う法人・組織" "Responsible borrower"] [:repayment "返済期限・返済義務" "Repayment obligation and maturity"] [:distribution "事業収益・DeFi収益の分配条件" "Business and DeFi income distribution"] [:withdrawal "出金条件・待機期間" "Withdrawal conditions and waiting period"] [:lossPolicy "損失・債務不履行時の扱い" "Loss and default policy"] [:useOfFunds "Botが使える用途・送金先の範囲" "Permitted Bot spending and recipients"]]]
         [:label.bw-funding-field {:key (name k)} (label ja en) [:textarea {:required true :min-length 8 :max-length 2000 :value (or (get funding-form k) "") :on-change #((:funding-field handlers) k (.. % -target -value))}]])
        [:label.bw-funding-field (label "1日あたりの利用上限（USDC）" "Daily spending limit (USDC)") [:input {:required true :input-mode "decimal" :value (or (:dailyLimitUSDC funding-form) "") :on-change #((:funding-field handlers) :dailyLimitUSDC (.. % -target -value))}]]
        [:label.bw-funding-field (label "チェーンID" "Chain ID") [:input {:required true :type "number" :min 1 :value (or (:chainId funding-form) "8453") :on-change #((:funding-field handlers) :chainId (.. % -target -value))}]]
        [:label.bw-funding-field (label "未使用資金の運用先（接続予定）" "Idle strategy (planned integration)") [:select {:value (or (:idleStrategy funding-form) "aave-v3") :on-change #((:funding-field handlers) :idleStrategy (.. % -target -value))}
         [:option {:value "aave-v3"} "Aave V3"] [:option {:value "veda"} "Veda"] [:option {:value "steakhouse-morpho"} "Steakhouse / Morpho"] [:option {:value "none"} (label "運用しない" "No allocation")]]]
        [:label [:input {:type "checkbox" :required true :checked (boolean (:acceptPublication funding-form)) :on-change #((:funding-field handlers) :acceptPublication (.. % -target -checked))}] (label "この事業の条件として公開する" "Publish these terms for this project")]
        (when funding-error [:p {:role "alert"} funding-error])
        (dds/button (label "条件を登録" "Register terms") {:submit? true :disabled funding-saving?})]]
      (capital/panel state handlers)]
     [:div
      [:div.bw-plugin-filters
       [:label (label "検索" "Search") [:input {:type "search" :value (or public-query "") :on-change (:public-query handlers) :placeholder "org / repo"}]]
       [:label (label "組織" "Organization") [:select {:value (or public-org "") :on-change (:public-org handlers)}
        [:option {:value ""} (label "すべての組織" "All organizations")]
        (for [org (:orgs public-directory)] [:option {:key (:id org) :value (:id org)} (str (:id org) " (" (:count org) ")")])]]]
      [:p {:role "status"} (str (count filtered) (label "件" " projects"))]
      (for [item filtered]
       [:article.bw-public-card {:key (:id item)}
        [:p [:code (:id item)]] [:h2 (:name item)] [:p (:description item)]
        [:p (label "公開情報 · 稼働実績は未確認 · USDC運用は未接続" "Public listing · Execution unverified · USDC funding not connected")]
        (dds/button (label "事業・資金情報を見る" "Business & funding details") {:type :outline :attrs {:on-click #((:public-select handlers) (:id item))}})])
      (when (empty? filtered) [:p (label "該当する公開事業がありません。" "No public projects match.")])]))]))
(def css ".bw-public{max-width:64rem;margin:auto;padding:var(--hig-spacing-4);overflow:auto;height:calc(100dvh - 5rem)}.bw-public input{font:inherit;padding:var(--hig-spacing-3);min-height:44px}.bw-public-card{padding:var(--hig-spacing-4) 0;border-bottom:1px solid var(--hig-color-separator)}.bw-public-card p{overflow-wrap:anywhere}.bw-funding-field{display:block;margin-block:var(--hig-spacing-4)}.bw-funding-field textarea{display:block;box-sizing:border-box;width:100%;min-height:6rem;font:inherit}.bw-capital select,.bw-funding-field input{max-width:100%;box-sizing:border-box;width:100%}.bw-capital dd{overflow-wrap:anywhere}.bw-public dt{font-weight:bold;margin-top:var(--hig-spacing-4)}.bw-public dd{margin-inline-start:0}.bw-public .bw-plugin-filters{flex-wrap:wrap}")
