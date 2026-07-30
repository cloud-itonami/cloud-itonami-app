(ns cloud.itonami.app.contracts
  "契約（継続課金）の読みモデル。何を契約していて、次にいくら引かれ、
  やめるなら何日前までに何をするのか。

  ## どこにデータがあるか

  vault（`kagi`）。この app は保管庫を持たない——契約は `~/.kagi` にある
  kagitaba item の `Contract` section で、ディスク上でもクラウド
  （kotobase.net）でも暗号文のまま置かれる（ADR-2607170500）。ここでやるのは
  復号された item を読み、`kaiyaku` の公開カタログと突き合わせて描画用の形に
  するところまで。

  ## E2E の代償を隠さない

  契約が E2E で封緘されているということは、**サーバ側では一切クエリできない**
  ということ。次回課金の集計も、予告期限の警告も、ここ（= 本人の端末で
  unlock された後）でしか計算できない。それが Proton/Storj 型の保管を選んだ
  ことの帰結で、回避策として金額や日付だけを平文で外に出すことはしない。

  ## 数えられないものを数えない

  - vault が `:locked` / `:absent` のときに空リストを返さない。
    「まだ開けていない」と「契約が 0 件」は違う。
  - 金額の合計は**通貨ごと**。為替を当てて 1 つの数字にまとめない
    （レートは今日の値であって契約の事実ではない）。
  - 金額が未記録の契約は合計に 0 として混ぜず、`:unpriced` として数える。
  - 解約手順はカタログに載っているものだけ。`:operator-verified` は false の
    まま返し、UI がそれを「未検証」と描けるようにする。

  ## 資格情報は出口に無い

  `kagi.vault-read/kagitaba-items` は機微値型の field を落としてから返す。
  この ns はそこから契約 section だけを読むので、パスワード・TOTP・カード番号が
  HTTP レスポンスに入る経路は存在しない（`contract-response-has-no-secrets`
  で固定）。"
  (:require [kagi.vault-read :as vault]
            [kagitaba.contract :as contract]
            [kaiyaku.catalog :as catalog]
            [kaiyaku.vault-ledger :as vault-ledger])
  (:import [java.time LocalDate]))

(def schema "cloud.itonami.app.contracts.v1")

(def reveal-purpose
  "台帳に残る開示理由。「なぜ開けたのか」が記録されない復号は後から検証できない。"
  :contract-review)

(defn today
  "`[y m d]`。契約の導出は純関数で、今日は引数として渡す。"
  ([] (today (LocalDate/now)))
  ([^LocalDate d] [(.getYear d) (.getMonthValue d) (.getDayOfMonth d)]))

(defn- present
  "欠損マーカーを JSON に出せる形にする。`:not-recorded` を null に潰さない
  ——null は「キーが無い」とも読めてしまい、未記録であることが伝わらない。"
  [v]
  (cond
    (= v contract/not-recorded) {:status "not-recorded"}
    (= v contract/unparseable) {:status "unparseable"}
    (and (vector? v) (= 3 (count v)) (every? int? v)) {:status "recorded"
                                                       :value (contract/print-date v)}
    (keyword? v) {:status "recorded" :value (name v)}
    (nil? v) {:status "not-recorded"}
    :else {:status "recorded" :value v}))

(defn- procedure-view
  "解約手順。カタログに無ければ nil——手順を発明するくらいなら何も出さない。"
  [entry]
  (when entry
    {:svc-id (:proc/svc-id entry)
     :name (:proc/name entry)
     ;; T1 公式 API > T2 ToS 許可済み browser > T3 self-submit。
     ;; 自動化の可否はここで決まり、UI はこれを表示するだけで実行はしない。
     :tier (catalog/derive-tier entry)
     :notice-days (:proc/notice-days entry)
     :penalty-jpy (:proc/penalty-jpy entry)
     :steps (vec (:proc/self-submit-steps entry))
     :source (:proc/disclosed-source entry)
     ;; 常に false。カタログの G6 がそう定めている——公開されている手順の
     ;; 「形」であって、今日その通りである保証ではない。
     :operator-verified (:proc/operator-verified entry)}))

(defn- contract-view [c procedures now]
  (let [proc (vault-ledger/procedure-for c procedures)
        charge (contract/next-charge-on-or-after c now)
        deadline (contract/notice-deadline c now)]
    {:item-id (:contract/item-id c)
     :title (:contract/title c)
     :plan (present (:contract/plan c))
     :status (present (:contract/status c))
     :amount {:minor (present (:contract/amount-minor c))
              :currency (present (:contract/currency c))}
     :cycle (present (:contract/cycle c))
     :annualized-minor (present (contract/annualized-minor c))
     :next-charge (present charge)
     :days-to-charge (present (contract/days-until charge now))
     :notice {:deadline (present deadline)
              :days-to-deadline (present (contract/days-until deadline now))
              :recorded-notice-days (present (:contract/notice-days c))}
     :auto-renew (present (:contract/auto-renew c))
     :procedure (procedure-view proc)
     ;; 読めなかった field。空でないなら、この行の数字は信用してはいけない。
     :problems (mapv #(select-keys % [:field :reason]) (:contract/problems c))}))

(defn totals
  "通貨ごとの月額合計と、値段の分からない契約の件数。

  1 つの数字にまとめない。JPY と USD を足すには今日のレートが要り、それを
  混ぜた瞬間「毎月いくら払っているか」が観測日に依存し始める。"
  [contracts]
  (reduce
   (fn [acc c]
     (let [amount (:contract/amount-minor c)
           cur (:contract/currency c)
           per-year (contract/charges-per-year c)
           cancelled? (contains? #{:cancelled} (:contract/status c))]
       (cond
         cancelled? acc
         (and (contract/recorded? amount) (contract/recorded? cur)
              (contract/recorded? per-year))
         (update-in acc [:monthly-minor cur] (fnil + 0)
                    (long (/ (* amount per-year) 12)))
         :else (update acc :unpriced inc))))
   {:monthly-minor {} :unpriced 0}
   contracts))

(defn report
  "契約画面の read model。vault を開き、契約 item だけを復号して読む。

  `session` を渡さなければこの機械の vault を開く。"
  ([] (report (vault/open)))
  ([session] (report session (today)))
  ([session now]
   (let [base {:schema schema
               :vault {:status (name (:status session))
                       :home (vault/redact-home (:vault-home session))
                       :did (:did session)}}]
     (if (not= :open (:status session))
       ;; 開いていない vault について、契約の話は一切しない。空の :contracts を
       ;; 返すと UI は「0 件」と描き、それは嘘になる。
       (assoc base :contracts nil :totals nil
              :note (case (:status session)
                      :locked "vault is locked on this device"
                      :absent "no vault on this device"
                      "vault unavailable"))
       (let [procedures (catalog/by-id (catalog/load-catalog))
             items (vault/kagitaba-items session reveal-purpose)
             contracts (into [] (comp (filter contract/contract?)
                                      (map #(contract/summary % now)))
                             items)]
         (assoc base
                :as-of (contract/print-date now)
                :contracts (mapv #(contract-view % procedures now)
                                 (sort-by :contract/title contracts))
                :totals (totals contracts)))))))
