(ns cloud.itonami.bots-ui
  "Pure shared Bot participation UI for desktop, mobile and itonami.cloud.
  Hosts own authenticated data, effects and navigation. No invented balances,
  availability or wallet authority. This package has no runtime dependencies."
  (:require [kotoba.lang.text :as str]))

(def messages
  {:en {:business "Business Bots" :mine "My Bots"
        :lead "Work with Itonami's business bots."
        :lend "Lend USDC" :lend-help "Provide capital for a bot's business."
        :data "Sell data" :data-help "Offer data a bot needs, on terms you review."
        :human "Human Computing" :human-help "Take on tasks that need a person."
        :held "Not available yet" :unknown "Status unavailable"
        :empty "No business bot activity is available right now."
        :details "Ways to participate" :open "Open Bot"}
   :ja {:business "Business Bots" :mine "My Bots"
        :lead "Itonamiの事業Botと、一緒に働く。"
        :lend "USDCを貸す" :lend-help "Botの事業に資金を貸し出す。"
        :data "データを売る" :data-help "Botが必要とするデータを、条件を確認して販売する。"
        :human "Human Computing" :human-help "人の力が必要な仕事を引き受ける。"
        :held "準備中" :unknown "稼働状況を取得できません"
        :empty "現在、事業Botの活動を取得できません。"
        :details "このBotに参加する" :open "Botを開く"}})

(defn label [locale k] (get-in messages [(if (= locale :ja) :ja :en) k]))

(defn local-route?
  "Only host-owned relative routes may be supplied as action destinations."
  [href]
  (and (string? href) (str/starts-with? href "/")
       (not (str/starts-with? href "//"))
       (not (re-find #"[\\\\\s]" href))))

(defn action-ready? [{:keys [available? href]}]
  (and (true? available?) (local-route? href)))

(def panel-style
  {:display "grid" :gap "0.75rem" :min-width "0" :max-width "100%"
   :box-sizing "border-box" :overflow-wrap "anywhere"})

(defn participation
  "A shared view, not a transaction executor. Capabilities must come from an
  authenticated host response; an address or public activity never grants one."
  [{:keys [locale capabilities]}]
  (into [:div {:class "itonami-bot-participation" :style panel-style}]
        (for [[kind help] [[:lend :lend-help] [:data :data-help] [:human :human-help]]
              :let [cap (get capabilities kind) ready? (action-ready? cap)]]
          [:div {:key (name kind) :style panel-style}
           [:strong (label locale kind)]
           [:p {:style {:margin "0"}} (label locale help)]
           (if ready?
             [:a {:href (:href cap) :class "button" :style {:min-height "44px"}}
              (label locale kind)]
             [:button {:type "button" :disabled true :style {:min-height "44px"}}
              (str (label locale kind) " · " (label locale :held))])])))

(defn business-bots
  "Read-only bot activity with per-bot participation. No activity is not zero
  work. Missing feeds have an explicit empty state, without fictional bots."
  [{:keys [locale bots]}]
  [:section {:class "itonami-business-bots" :style panel-style
             :aria-label (label locale :business)}
   [:header [:h2 (label locale :business)] [:p (label locale :lead)]]
   (if (seq bots)
     (into [:div {:style panel-style}]
           (for [{:keys [id name summary status capabilities href]} bots]
             [:details {:key id :style {:border "1px solid currentColor"
                                       :border-radius "12px" :padding "1rem"
                                       :min-width "0"}}
              [:summary {:style {:min-height "44px" :cursor "pointer"}}
               [:strong (or name id)] " · " (or status (label locale :unknown))]
              (when (seq summary) [:p summary])
              (when (local-route? href)
                [:p [:a {:href href} (label locale :open)]])
              [:h3 (label locale :details)]
              (participation {:locale locale :capabilities capabilities})]))
     [:div {:style panel-style}
      [:p {:role "status"} (label locale :empty)]
      (participation {:locale locale})])])
