(ns cloud.itonami.app.contracts-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.contracts :as contracts]
            [kagitaba.contract :as contract]
            [kagitaba.item :as item]
            [kagi.crypto :as crypto]
            [kagi.identity :as identity]
            [kagi.operation :as op]
            [kagi.store :as store]
            [langgraph.graph :as g]))

(def now [2026 7 30])

(defn- vault-session []
  (let [cr (crypto/jvm-provider)
        id (identity/generate-identity)
        st (store/mem-store {:members {(:did id) #:member{:did (:did id) :role :owner}}})]
    {:status :open :provider cr :identity id :did (:did id)
     :vmk (crypto/rand-bytes cr 32) :store st
     :vault-home (str (System/getProperty "user.home") "/.kagi")}))

(defn- seed! [{:keys [store provider identity vmk]} item-id kagitaba-item]
  (g/run* (op/build store {:crypto provider})
          {:request {:op :item/create :item-id item-id :compartment "personal"
                     :category :membership
                     :plaintext (.getBytes ^String (pr-str kagitaba-item) "UTF-8")}
           :context {:did (:did identity) :role :owner :phase 1 :vmk vmk
                     :purpose :daily-use}}
          {:thread-id (str "seed-" item-id)}))

(defn- membership [title c & [extra-sections]]
  (item/item* {:category :membership :title title
               :sections (into [(contract/section c)] extra-sections)}))

(def claude-pro
  (membership "Claude Pro"
              {:plan "Pro" :status :active :amount-minor 3000 :currency "JPY"
               :cycle :monthly :next-charge "2026-08-15" :notice-days 0
               :cancel-proc-id "claude-pro"}
              [{:title "Login"
                :fields [{:id "password" :title "password" :type :concealed
                          :value "correct-horse-battery-staple"}
                         {:id "username" :title "username" :type :string
                          :value "jun@example.com"}]}]))

(def chatgpt-plus
  (membership "ChatGPT Plus"
              {:plan "Plus" :status :active :amount-minor 2000 :currency "USD"
               :cycle :monthly :cancel-proc-id "chatgpt-plus"}))

(def x-premium
  (membership "X Premium"
              {:plan "Premium" :status :active :amount-minor 1380 :currency "JPY"
               :cycle :monthly :next-charge "2026-08-01" :notice-days 1
               :cancel-proc-id "x-premium"}))

(defn- report-of [items]
  (let [s (vault-session)]
    (doseq [[id it] items] (seed! s id it))
    (contracts/report s now)))

;; ── vault が開いていないことを 0 件と書かない ───────────────────────────────

(deftest locked-vault-is-not-zero-contracts
  (doseq [[status note] [[:locked "vault is locked on this device"]
                         [:absent "no vault on this device"]]]
    (let [r (contracts/report {:status status :vault-home "/x/.kagi"} now)]
      (is (nil? (:contracts r))
          "空リストを返すと UI は「契約 0 件」と描く — それは嘘")
      (is (nil? (:totals r)))
      (is (= note (:note r)))
      (is (= (name status) (get-in r [:vault :status]))))))

;; ── 資格情報は応答に無い ────────────────────────────────────────────────────

(deftest contract-response-has-no-secrets
  (let [r (report-of {"claude-pro" claude-pro})
        dump (pr-str r)]
    (is (= 1 (count (:contracts r))))
    (is (not (re-find #"correct-horse-battery-staple" dump))
        "契約を読むために item を復号したが、パスワードは応答に入らない")
    (is (not (re-find #"vmk|:plaintext" dump)))))

;; ── 合計は通貨ごと ──────────────────────────────────────────────────────────

(deftest totals-are-per-currency-and-never-converted
  (let [r (report-of {"claude-pro" claude-pro
                      "chatgpt-plus" chatgpt-plus
                      "x-premium" x-premium})
        monthly (get-in r [:totals :monthly-minor])]
    (is (= {"JPY" 4380 "USD" 2000} monthly)
        "JPY と USD は別々に積まれる（3000+1380 と 2000）")
    (is (= 0 (get-in r [:totals :unpriced])))))

(deftest unpriced-contracts-are-counted-not-zeroed
  (let [r (report-of {"claude-pro" claude-pro
                      "mystery" (membership "謎のサブスク" {:status :active})})]
    (is (= {"JPY" 3000} (get-in r [:totals :monthly-minor]))
        "金額不明の契約を 0 円として合計に混ぜない")
    (is (= 1 (get-in r [:totals :unpriced])))))

(deftest cancelled-contracts-leave-the-total
  (let [r (report-of {"gone" (membership "解約済み"
                                         {:status :cancelled :amount-minor 9999
                                          :currency "JPY" :cycle :monthly})})]
    (is (= {} (get-in r [:totals :monthly-minor])))
    (is (= 0 (get-in r [:totals :unpriced]))
        "解約済みは「値段が分からない」ではない")))

;; ── 欠損の表現 ──────────────────────────────────────────────────────────────

(deftest missing-fields-say-so
  (let [r (report-of {"mystery" (membership "謎のサブスク" {:plan "Pro"})})
        c (first (:contracts r))]
    (is (= {:status "recorded" :value "Pro"} (:plan c)))
    (is (= {:status "not-recorded"} (:status c)))
    (is (= {:status "not-recorded"} (get-in c [:amount :minor])))
    (is (= {:status "not-recorded"} (:annualized-minor c))
        "年額を 0 として出さない")))

(deftest broken-fields-are-flagged
  (let [r (report-of {"broken" (membership "壊れた"
                                           {:amount-minor "だいたい3000円"
                                            :cycle :monthly :currency "JPY"})})
        c (first (:contracts r))]
    (is (= {:status "unparseable"} (get-in c [:amount :minor])))
    (is (= [{:field "amount-minor" :reason :unparseable}] (:problems c))
        "読めなかった field を名指しする — この行の数字は信用できない")))

;; ── 解約手順 ────────────────────────────────────────────────────────────────

(deftest procedure-comes-from-the-catalog-and-stays-unverified
  (let [r (report-of {"x-premium" x-premium})
        p (:procedure (first (:contracts r)))]
    (is (= "x-premium" (:svc-id p)))
    (is (= "T3" (:tier p)) "ToS が browser 自動化を禁じているので T2 に上がらない")
    (is (= 1 (:notice-days p)) "24 時間ガイダンスが 0 に丸められていない")
    (is (false? (:operator-verified p))
        "カタログは開示された「形」であって live な ToS 主張ではない")
    (is (seq (:steps p)))
    (is (re-find #"^https://" (:source p)))))

(deftest unknown-service-has-no-invented-procedure
  (let [r (report-of {"mystery" (membership "謎のサブスク"
                                            {:amount-minor 550 :currency "JPY"
                                             :cycle :monthly})})]
    (is (nil? (:procedure (first (:contracts r))))
        "カタログに無い手順を作り出さない")))

;; ── 予告期限 ────────────────────────────────────────────────────────────────

(deftest notice-deadline-is-computed-and-can-be-in-the-past
  (let [r (report-of {"x-premium" x-premium})
        c (first (:contracts r))]
    (is (= {:status "recorded" :value "2026-07-31"} (get-in c [:notice :deadline]))
        "8/1 の課金に対して 1 日前")
    (is (= {:status "recorded" :value 1} (get-in c [:notice :days-to-deadline]))))
  (testing "予告日数が未記録なら期限も出さない"
    (let [r (report-of {"c" (membership "予告不明"
                                        {:cycle :monthly :next-charge "2026-08-15"})})
          c (first (:contracts r))]
      (is (= {:status "not-recorded"} (get-in c [:notice :deadline]))))))

(deftest stale-charge-dates-roll-forward
  (let [r (report-of {"c" (membership "古い記録"
                                      {:cycle :monthly :next-charge "2026-01-15"
                                       :amount-minor 500 :currency "JPY"})})
        c (first (:contracts r))]
    (is (= {:status "recorded" :value "2026-08-15"} (:next-charge c))
        "記録が半年前でも、周期から次の課金日を出す")
    (is (= {:status "recorded" :value 16} (:days-to-charge c)))))

;; ── 契約でない item は出てこない ────────────────────────────────────────────

(deftest non-contract-membership-items-are-skipped
  (let [s (vault-session)
        _ (seed! s "gym" (item/item* {:category :membership :title "ジム会員証"
                                      :sections [{:title "Membership"
                                                  :fields [{:id "member-number"
                                                            :title "member number"
                                                            :type :string
                                                            :value "A-1234"}]}]}))
        r (contracts/report s now)]
    (is (= [] (:contracts r))
        "Contract section を持たない membership item は契約ではない")))
