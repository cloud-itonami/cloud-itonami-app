(ns cloud.itonami.app.paypay-bank-test
  "Parsers tested against the REAL notification text, transcribed from mails
  PayPay銀行 actually sent to this account in July 2026.

  Synthetic fixtures would have missed the thing that actually breaks these:
  the bank is inconsistent with itself about character width. The balance notice
  writes `口座残高：` with a full-width colon, the failure notice writes
  `収納企業:` with a half-width one, and shop names arrive in full-width Latin
  while amounts arrive in half-width digits. A fixture someone typed by hand
  would have been uniform, passed, and left the real mail unparsed."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.paypay-bank :as bank]))

;; Real, from message 19f9923559cfbaf5 (2026-07-25).
(def balance-notice-body
  (str "カワサキ ジュン様\n"
       "いつもPayPay銀行をご利用いただきありがとうございます。\n"
       "本メールは、残高不足により引き落としができない恐れのあるお客さまにお送りしています。\n"
       "2日後に引落予定の口座振替についてご案内いたします。\n"
       "残高が不足している場合は引落予定日午前0時までにご入金ください。\n"
       "引落予定日：2026/07/27\n"
       "合計引落予定額：496131円\n"
       "口座残高：435180円\n"))

;; Real, from message 19f4178c841d5322 (2026-07-08).
(def balance-notice-body-2
  (str "本メールは、残高不足により引き落としができない恐れのあるお客さまにお送りしています。\n"
       "引落予定日：2026/07/10\n"
       "合計引落予定額：95731円\n"
       "口座残高：3710円\n"))

;; Real, from message 19fa39d975cf5b9f (2026-07-27). Note the HALF-width colon.
(def failure-body
  (str "カワサキ ジュン様\n"
       "残高不足により、下記口座振替ができませんでした。\n"
       "引落日の当日21時までに普通預金口座へご入金いただけますと、21時以降、順次、再引落を行います。\n"
       "収納企業:ミツイスミトモカ－ド （カ\n"
       "取扱日時:2026年07月27日\n"))

;; Real, from message 19f9d6cb9aefa3aa (2026-07-26). Full-width shop name.
(def visa-debit-body
  (str "Visaデビットのご利用代金を普通預金口座より引き落としいたしました。\n"
       "引落日時：2026/07/26 16:52:27\n"
       "ご利用金額：186円\n"
       "ご利用のショップ名：ＣＬＯＵＤＦＬＡＲＥ\n"
       "取引明細番号：2A207001\n"))

;; Real, from message 19f7da02970cd3aa (2026-07-20).
(def visa-refund-body
  (str "Visaデビットのご利用代金をご返金いたしました。\n"
       "ご返金日時：2026/07/20 12:41:27\n"
       "ご返金額：2000円\n"
       "加盟店名：ＧＯＯＧＬＥ＊ＡＤＳ６４０６３３３６３１\n"
       "取引明細番号：3A201002\n"))

;; Real, from message 19fa0936b19fe42c (2026-07-27).
(def account-debit-body
  (str "下記日時にお客さまの普通預金口座からお引き落としを行いましたので、お知らせします。\n"
       "委託者名：三井住友カード\n"
       "お引落日時：2026/07/27 01:48:54\n"
       "お引落金額：411170円\n"))

;; ---------------------------------------------------------------------------
;; normalisation
;; ---------------------------------------------------------------------------

(deftest full-width-digits-and-colons-are-folded
  (is (= "2026/07/27" (bank/normalize "２０２６/０７/２７")))
  (is (= "口座残高:435180円" (bank/normalize "口座残高：435180円")))
  (is (= "Visa" (bank/normalize "Ｖｉｓａ"))
      "full-width Latin too: folding only digits left every Visa mail unclassified")
  (is (= "CLOUDFLARE" (bank/normalize "ＣＬＯＵＤＦＬＡＲＥ"))
      "which is also what makes a merchant name matchable against an invoice")
  (testing "nil in, nil out -- an absent body is not an empty one"
    (is (nil? (bank/normalize nil)))))

;; ---------------------------------------------------------------------------
;; kinds
;; ---------------------------------------------------------------------------

(deftest the-subject-decides-the-kind-and-an-unknown-one-is-nil
  (is (= :balance-notice (bank/notice-kind "【残高不足】引落予定のご案内")))
  (is (= :debit-failure (bank/notice-kind "【要対応】残高不足により口座振替できませんでした")))
  (is (= :visa-debit (bank/notice-kind "【Ｖｉｓａデビット】ご利用代金お引き落としのお知らせ"))
      "the subject itself is full-width Latin, which is why normalize runs first")
  (is (= :visa-debit-refund (bank/notice-kind "【Ｖｉｓａデビット】ご利用代金ご返金のお知らせ"))
      "refund must win over the plain debit rule, or a refund reads as a charge")
  (is (= :account-debit (bank/notice-kind "お引き落としのご連絡")))
  (is (= :incoming-transfer (bank/notice-kind "振込入金のご連絡")))
  (is (= :card-loan (bank/notice-kind "カードローンご返済日のお知らせ")))
  (testing "an unrecognised subject is nil, never a default"
    (is (nil? (bank/notice-kind "ポイント受取設定完了のお知らせ")))
    (is (nil? (bank/notice-kind nil)))))

;; ---------------------------------------------------------------------------
;; the balance
;; ---------------------------------------------------------------------------

(deftest the-balance-notice-yields-the-figure-the-bank-stated
  (let [p (bank/parse-balance-notice balance-notice-body)]
    (is (= 435180 (:amount-minor p)))
    (is (= "JPY" (:currency p)))
    (is (= "2026-07-27" (:scheduled-debit-date p)))
    (is (= 496131 (:scheduled-debit-total-minor p)))
    (is (= 60951 (:shortfall-minor p))
        "derived from the two figures actually read, not parsed from a third"))
  (testing "and the earlier, much worse one"
    (let [p (bank/parse-balance-notice balance-notice-body-2)]
      (is (= 3710 (:amount-minor p)))
      (is (= 92021 (:shortfall-minor p))))))

(deftest a-body-without-a-balance-line-yields-nil-not-zero
  (testing "this is the failure that would be catastrophic: a parser that
            returns 0 refuses every payment forever while appearing to work"
    (doseq [body [nil "" "引落予定日：2026/07/27\n合計引落予定額：496131円\n"
                  "口座残高が不足しています"]]
      (is (nil? (bank/parse-balance-notice body)) (pr-str body))))
  (testing "and a debit-failure body is not a balance notice either"
    (is (nil? (bank/parse-balance-notice failure-body)))))

(deftest a-comfortable-balance-reports-no-shortfall-rather-than-a-negative-one
  (let [p (bank/parse-balance-notice
           "引落予定日：2026/08/01\n合計引落予定額：1000円\n口座残高：50000円\n")]
    (is (= 50000 (:amount-minor p)))
    (is (zero? (:shortfall-minor p)))))

;; ---------------------------------------------------------------------------
;; the rest
;; ---------------------------------------------------------------------------

(deftest a-failure-notice-carries-the-collector-but-no-balance
  (let [p (bank/parse-debit-failure failure-body)]
    (is (= :debit-failure (:kind p)))
    (is (= "ミツイスミトモカ-ド (カ" (:collector p))
        "ASCII-folded: lossy for display, recoverable from the mail via its id")
    (is (= "2026-07-27" (:handled-on p)))
    (is (not (contains? p :amount-minor))
        "the mail says the balance was short, not what it was -- inventing a
         number here would be the worst kind of helpful")))

(deftest a-visa-debit-parses-with-its-merchant-and-reference
  (let [p (bank/parse-visa-debit visa-debit-body)]
    (is (= :visa-debit (:kind p)))
    (is (= 186 (:amount-minor p)))
    (is (false? (:refund? p)))
    (is (= "CLOUDFLARE" (:merchant p))
        "folded, so it can be matched against a Cloudflare invoice")
    (is (= "2A207001" (:reference p)))
    (is (= "2026/07/26 16:52:27" (:occurred-at-local p)))))

(deftest a-refund-is-not-a-charge
  (let [p (bank/parse-visa-debit visa-refund-body)]
    (is (= :visa-debit-refund (:kind p)))
    (is (true? (:refund? p)))
    (is (= 2000 (:amount-minor p)))
    (is (= "GOOGLE*ADS6406333631" (:merchant p)))
    (testing "same amount and reference as the charge it reverses, which is why
              the sign has to come from the kind and not from the number"
      (is (= "3A201002" (:reference p))))))

(deftest a-collected-direct-debit-parses
  (let [p (bank/parse-account-debit account-debit-body)]
    (is (= 411170 (:amount-minor p)))
    (is (= "三井住友カード" (:collector p)))
    (is (= "2026/07/27 01:48:54" (:occurred-at-local p)))))

(deftest dispatch-routes-by-subject-and-refuses-what-it-does-not-know
  (is (= 435180 (:amount-minor (bank/parse {:subject "【残高不足】引落予定のご案内"
                                            :body balance-notice-body}))))
  (is (= :debit-failure (:kind (bank/parse {:subject "【要対応】残高不足により口座振替できませんでした"
                                            :body failure-body}))))
  (is (= 411170 (:amount-minor (bank/parse {:subject "お引き落としのご連絡"
                                            :body account-debit-body}))))
  (testing "a subject it does not know is nil rather than a wrong parser"
    (is (nil? (bank/parse {:subject "ポイント受取設定完了のお知らせ" :body "..."}))))
  (testing "and a known subject with an unparseable body is also nil -- the
            caller's response to both is the same: there is nothing to record"
    (is (nil? (bank/parse {:subject "【残高不足】引落予定のご案内" :body "壊れた本文"})))))

;; ---------------------------------------------------------------------------
;; feeding the funding model
;; ---------------------------------------------------------------------------

(deftest a-balance-record-carries-the-mail-arrival-time-as-its-age
  (let [received "2026-07-25T11:54:23Z"
        r (bank/balance-record (bank/parse-balance-notice balance-notice-body)
                               received)]
    (is (= 435180 (:amount-minor r)))
    (is (= "JPY" (:currency r)))
    (is (= received (:as-of r))
        "the notice never says when the balance was measured, so the mail's
         arrival is the best available upper bound on its age")
    (is (= :statement (:source r))
        "published to us, not queried by us -- distinguishable from :api later")
    (is (re-find #"引落予定日 2026-07-27" (:source-detail r)))
    (testing "and funding accepts it, which is the only test of the contract
              between these two namespaces that cannot go stale"
      (is (funding/fresh?
           (funding/freshness {:amount-minor (:amount-minor r) :as-of (:as-of r)}
                              "2026-07-25T18:00:00Z"))))))

(deftest a-record-is-refused-without-the-arrival-time
  (let [p (bank/parse-balance-notice balance-notice-body)]
    (testing "received-at is required and is NOT defaulted to now -- a balance
              stamped with processing time quietly claims to be current"
      (doseq [received [nil "" 1690000000]]
        (is (nil? (bank/balance-record p received)) (pr-str received))))
    (testing "and a non-balance parse never becomes a balance record"
      (is (nil? (bank/balance-record (bank/parse-debit-failure failure-body)
                                     "2026-07-27T00:00:00Z")))
      (is (nil? (bank/balance-record nil "2026-07-27T00:00:00Z"))))))

(deftest the-warning-feed-ages-out-like-any-other-reading
  (testing "a balance learned from a warning notice is true when stated and
            decays -- it does not stand indefinitely because no worse news came.
            This is what stops 'no notice' being read as 'balance is fine'."
    (let [r (bank/balance-record (bank/parse-balance-notice balance-notice-body)
                                 "2026-07-25T11:54:23Z")
          b {:amount-minor (:amount-minor r) :as-of (:as-of r)}]
      (is (funding/fresh? (funding/freshness b "2026-07-25T20:00:00Z")))
      (is (= :stale (:funding/status (funding/freshness b "2026-07-30T00:00:00Z")))
          "five days later it can no longer answer 'will this clear?'"))))
