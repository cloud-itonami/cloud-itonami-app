(ns cloud.itonami.my-bots-ui
  "One private Bot list, thread and approval view. Hosts provide verified
  state and handlers; this component has no browser or native dependency."
  (:require [clojure.string :as str]))

(def css
  ".my-bots{box-sizing:border-box;max-width:76rem;margin:auto;padding:1rem;font-size:1rem;overflow-wrap:anywhere}.my-bots *{box-sizing:border-box;min-width:0}.my-bots button,.my-bots input,.my-bots textarea{font:inherit;min-height:44px;max-width:100%}.my-bots__layout{display:grid;grid-template-columns:minmax(14rem,20rem) minmax(0,1fr);gap:1rem}.my-bots__list{display:grid;gap:.5rem;align-content:start}.my-bots__row{display:grid;text-align:start;width:100%;padding:.75rem;gap:.375rem}.my-bots__row[aria-current=true]{outline:2px solid currentColor}.my-bots__thread{display:flex;flex-direction:column;gap:1rem}.my-bots__messages{display:grid;gap:.75rem;max-height:55dvh;overflow:auto;white-space:pre-wrap;list-style:none;padding:0}.my-bots__message{padding:1rem;border:1px solid currentColor;border-radius:.75rem}.my-bots__composer{display:grid;gap:.5rem}.my-bots__composer textarea{width:100%;resize:vertical}.my-bots__actions{display:flex;gap:.5rem;flex-wrap:wrap}.my-bots__back{display:none}@media(max-width:700px){.my-bots__layout{grid-template-columns:minmax(0,1fr)}.my-bots[data-selected=true] .my-bots__rail{display:none}.my-bots[data-selected=false] .my-bots__thread{display:none}.my-bots__back{display:block}.my-bots__messages{max-height:50dvh}}")

(def strings
  {:en {:new "New Bot" :name "Bot name" :create "Create Bot" :search "Search Bots"
        :empty "No Bots yet. Create your first Bot." :choose "Choose a Bot to continue."
        :signin "Sign in with Web3 or a passkey to open My Bots." :login "Sign in"
        :send "Send" :message "Message" :approve "Approve" :decline "Decline"
        :review "Review this Bot's request" :back "All Bots" :refresh "Refresh"
        :loading "Loading Bots…" :start "Start a conversation." :ready "Ready" :waiting-approval "Needs your review" :needs-review "Needs confirmation" :unknown "Status unavailable" :uncertain "The previous request needs confirmation before this Bot can continue."}
   :ja {:new "Botを追加" :name "Botの名前" :create "Botを作成" :search "Botsを検索"
        :empty "まだBotがありません。最初のBotを作成しましょう。" :choose "Botを選んでください。"
        :signin "Web3またはパスキーでログインして、My Botsを開きます。" :login "ログイン"
        :send "送信" :message "メッセージ" :approve "承認する" :decline "見送る"
        :review "Botからの依頼を確認" :back "Bots一覧" :refresh "更新"
        :loading "Botsを読み込み中…" :start "Botに仕事を依頼する。" :ready "待機中" :waiting-approval "確認待ち" :needs-review "処理の確認が必要" :unknown "状態を取得できません" :uncertain "前の処理の結果を確認するまで、このBotは待機します。"}})

(defn screen [{:keys [locale principal bots bot busy? error phase query name text signin-href]} handlers]
  (let [t #(get-in strings [(if (= locale :ja) :ja :en) %])
        click (fn [key & args] (when-let [f (get handlers key)] (fn [_] (apply f args))))
        visible (filter #(str/includes? (str/lower-case (:name %)) (str/lower-case (or query ""))) bots)]
    [:main {:class "my-bots" :data-selected (boolean bot)}
     [:h1 "My Bots"]
     (when error [:p {:role "alert"} error])
     (cond
       (= phase :loading) [:p {:role "status"} (t :loading)]
       (nil? principal) [:div [:p (t :signin)] [:a {:href (or signin-href "/#web-connect")} (t :login)]]
       :else
       [:div {:class "my-bots__layout"}
        [:aside {:class "my-bots__rail" :aria-label "My Bots"}
         [:label {:for "my-bots-search"} (t :search)]
         [:input {:id "my-bots-search" :type "search" :value (or query "") :on-change (:query handlers)}]
         [:button {:type "button" :disabled busy? :on-click (click :refresh)} (t :refresh)]
         [:details [:summary (t :new)]
          [:form {:class "my-bots__composer" :on-submit (:create handlers)}
           [:label {:for "my-bot-name"} (t :name)]
           [:input {:id "my-bot-name" :value (or name "") :max-length 80 :required true :on-change (:name handlers)}]
           [:button {:type "submit" :disabled busy?} (t :create)]]]
         (if (seq bots)
           (into [:div {:class "my-bots__list"}]
                 (for [item visible]
                   [:button {:key (:id item) :type "button" :class "my-bots__row" :disabled busy?
                             :aria-current (= (:id item) (:id bot)) :on-click (click :select (:id item))}
                    [:strong (:name item)] [:span (:lastMessage item)]
                    [:small (or (t (keyword (:status item))) (t :unknown))]]))
           [:p (t :empty)])]
        [:section {:class "my-bots__thread"}
         (if bot
           [:div
            [:button {:type "button" :class "my-bots__back" :disabled busy? :on-click (click :back)} (t :back)]
            [:h2 (:name bot)]
            (if (seq (:messages bot))
              (into [:ol {:class "my-bots__messages" :aria-label (t :message)}]
                    (for [[index message] (map-indexed vector (:messages bot))]
                      [:li {:key index :class "my-bots__message"}
                       [:small (:role message)] [:p (:text message)]]))
              [:p (t :start)])
            (cond
              (= "needs-review" (:status bot)) [:p {:role "status"} (t :uncertain)]
              (:pendingApproval bot)
              [:section [:h3 (t :review)]
               [:p (:name (:pendingApproval bot))]
               [:pre {:style {:white-space "pre-wrap"}} (pr-str (:arguments (:pendingApproval bot)))]
               [:div {:class "my-bots__actions"}
                [:button {:type "button" :disabled busy? :on-click (click :approve true)} (t :approve)]
                [:button {:type "button" :disabled busy? :on-click (click :approve false)} (t :decline)]]]
              :else
              [:form {:class "my-bots__composer" :on-submit (:send handlers)}
               [:label {:for "my-bot-message"} (t :message)]
               [:textarea {:id "my-bot-message" :rows 3 :value (or text "") :max-length 4096
                           :required true :on-change (:text handlers)}]
               [:button {:type "submit" :disabled busy?} (t :send)]])]
           [:p (t :choose)])]])]))
