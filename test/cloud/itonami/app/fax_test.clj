(ns cloud.itonami.app.fax-test
  "Every test here drives an injected `send-fn`. Nothing in this file reaches
  `api.hellofax.com`, and nothing in this repository ever has: no account is
  provisioned and a live call costs money and rings a real phone line."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.fax :as fax]
            [lawfirm.actor :as lf-actor]
            [lawfirm.demo :as demo]
            [lawfirm.store :as lf-store]
            [lawfirm.transmission :as lf-transmission]
            [lawfirm.workspace :as lf-workspace])
  (:import [java.nio.charset StandardCharsets]))

(defn- bytes-of [^String s] (.getBytes s StandardCharsets/UTF_8))

(def ^:private pdf (bytes-of "%PDF-1.4 準備書面"))
(def ^:private pdf-digest (fax/sha256-hex pdf))

;; ---------------------------------------------------------------------------
;; The gate
;; ---------------------------------------------------------------------------

(deftest the-surface-is-off-unless-an-operator-turned-it-on
  (is (false? (fax/enabled? {})))
  (is (false? (fax/enabled? {:fax {}})))
  (is (false? (fax/enabled? {:fax {:enabled? "true"}})))
  (is (true? (fax/enabled? {:fax {:enabled? true}}))))

(deftest the-shipped-default-is-off
  (is (false? (fax/enabled?))))

(deftest a-password-is-never-taken-from-configuration
  (testing "config carries where the credential is, never what it is"
    (is (nil? (fax/credentials {:fax {:username "a@example.jp"
                                      :account-guid "guid"
                                      :password "hunter2"}}))
        "even when a password is handed in, it is not a credential source")
    (is (nil? (fax/credentials {:fax {:account-guid "guid"}})))
    (is (nil? (fax/credentials {})))))

(deftest status-reports-presence-never-value
  (let [s (fax/status {:fax {:enabled? true :username "a@example.jp"
                             :account-guid "guid" :callback-token "t"}})]
    (is (true? (:enabled? s)))
    (is (false? (:credentials-present? s)) "no keychain item in the test environment")
    (is (not (str/includes? (pr-str s) "hunter2")))
    (is (not (contains? s :password)))))

;; ---------------------------------------------------------------------------
;; The document must be the one that was approved
;; ---------------------------------------------------------------------------

(deftest only-the-approved-bytes-match
  (is (true? (fax/document-matches? (str "sha256:" pdf-digest) pdf)))
  (is (true? (fax/document-matches? (str "sha256:" (str/upper-case pdf-digest)) pdf)))
  (is (false? (fax/document-matches? (str "sha256:" pdf-digest) (bytes-of "別の書面"))))
  (testing "and an unidentified document is not a match — 'I cannot tell' is not a yes"
    (is (false? (fax/document-matches? nil pdf)))
    (is (false? (fax/document-matches? "" pdf)))
    (is (false? (fax/document-matches? "opaque-ref" pdf)))))

;; ---------------------------------------------------------------------------
;; The provider's answer
;; ---------------------------------------------------------------------------

(defn- response [status-code & [extra]]
  {:status 200
   :body (json/write-str (merge {"Transmission" (merge {"Guid" "tx-guid"
                                                        "StatusCode" status-code}
                                                       extra)}))})

(deftest status-codes-map-the-way-the-practice-needs
  (doseq [[code expected] {"S" :ok "E" :failed "T" :pending "P" :pending "O" :pending}]
    (testing code
      (is (= expected (:result (fax/parse-response (response code) #(json/read-str %))))))))

(deftest on-hold-is-pending-and-not-failed
  (testing "nothing was refused — reporting failure would send someone to
            re-send a document that is queued"
    (is (= :pending (:result (fax/parse-response (response "O") #(json/read-str %)))))))

(deftest an-error-code-is-turned-into-something-an-operator-can-read
  (let [r (fax/parse-response (response "E" {"ErrorCode" "N"}) #(json/read-str %))]
    (is (= :failed (:result r)))
    (is (= "応答なし" (:error r)))))

(deftest a-destination-the-provider-did-not-echo-stays-nil
  (testing "and that nil is load-bearing — the practice turns it into
            :undeterminable rather than into a claim the destination was right"
    (is (nil? (:dialled (fax/parse-response (response "S") #(json/read-str %)))))
    (is (= "0312345678"
           (:dialled (fax/parse-response (response "S" {"To" "0312345678"})
                                         #(json/read-str %)))))))

(deftest the-daily-cap-is-named-rather-than-reported-as-a-bare-429
  (let [r (fax/parse-response {:status 429 :body "{}"} #(json/read-str %))]
    (is (false? (:ok? r)))
    (is (= :provider-rejected (:reason r)))
    (is (str/includes? (:detail r) "200"))))

(deftest an-unreadable-body-is-a-refusal-not-a-success
  (is (false? (:ok? (fax/parse-response {:status 200 :body "<html>"} #(json/read-str %))))))

;; ---------------------------------------------------------------------------
;; dispatch! — every refusal happens before the transport is touched
;; ---------------------------------------------------------------------------

(defn- recording-send-fn [calls]
  (fn [request] (swap! calls conj request) (response "S" {"To" "03-XXXX-0001"})))

(def ^:private config-on
  {:fax {:enabled? true :username "a@example.jp" :account-guid "guid"}
   :lawfirm {:enabled? true}})

(deftest a-disabled-surface-never-reaches-the-transport
  (let [calls (atom [])
        r (fax/dispatch! {:transmission-id "TX-1" :bytes pdf :today demo/today
                          :configuration {:fax {:enabled? false}}
                          :send-fn (recording-send-fn calls)})]
    (is (false? (:ok? r)))
    (is (= :fax-disabled (:reason r)))
    (is (empty? @calls))))

(deftest missing-credentials-refuse-before-the-transport
  (let [calls (atom [])
        r (fax/dispatch! {:transmission-id "TX-1" :bytes pdf :today demo/today
                          :configuration config-on
                          :send-fn (recording-send-fn calls)})]
    (is (false? (:ok? r)))
    (is (= :credentials-missing (:reason r)))
    (is (empty? @calls)
        "no keychain item exists here, so this is the real refusal path")))

;; ---------------------------------------------------------------------------
;; The port itself, over a practice record
;; ---------------------------------------------------------------------------

(defn- practice-with-fax []
  (let [s (demo/fresh-store)]
    ;; A real number so the direction check has something to compare.
    (lf-store/register-recipient! s (assoc-in (lf-store/recipient s "R-1")
                                              [:channels :fax :number] "03-1234-5678"))
    (lf-store/register-work-product! s (assoc (lf-store/work-product s "W-9")
                                              :object-ref (str "sha256:" pdf-digest)))
    s))

(deftest the-number-handed-to-the-transport-comes-from-the-record
  (let [s (practice-with-fax)
        plan (lf-workspace/dispatch-plan s "TX-1")
        calls (atom [])
        port (fax/dispatch-port {:send-fn (recording-send-fn calls)
                                 :decode #(json/read-str %)})]
    (is (true? (:ok? plan)))
    (lf-workspace/-dispatch port {:plan plan :bytes pdf :filename "w9.pdf"
                                  :credentials {:username "u" :password "p"
                                                :account-guid "g"}
                                  :cover {:to "東京地方裁判所"}})
    (is (= "03-1234-5678" (:to (first @calls)))
        "not a caller-supplied number — the registered one")
    (testing "and it follows the record when the record changes"
      (lf-store/register-recipient! s (assoc-in (lf-store/recipient s "R-1")
                                                [:channels :fax :number] "03-5555-0000"))
      (lf-workspace/-dispatch port {:plan (lf-workspace/dispatch-plan s "TX-1")
                                    :bytes pdf :filename "w9.pdf"
                                    :credentials {} :cover {}})
      (is (= "03-5555-0000" (:to (last @calls)))))))

(deftest the-cover-page-names-the-registered-recipient
  (let [s (practice-with-fax)
        calls (atom [])
        port (fax/dispatch-port {:send-fn (recording-send-fn calls)
                                 :decode #(json/read-str %)})]
    (lf-workspace/-dispatch port {:plan (lf-workspace/dispatch-plan s "TX-1")
                                  :bytes pdf :filename "w9.pdf" :credentials {}
                                  :cover {:to (get-in (lf-workspace/dispatch-plan s "TX-1")
                                                      [:recipient :name])}})
    (is (= "東京地方裁判所 民事第○部" (get-in (first @calls) [:cover :to])))))

(deftest a-transmission-the-practice-refuses-produces-no-plan-to-dispatch
  (let [s (practice-with-fax)]
    (testing "an inbound row"
      (is (= :not-outbound (:reason (lf-workspace/dispatch-plan s "IN-9001")))))
    (testing "a document that was never issued"
      (lf-store/register-transmission! s {:transmission-id "TX-D" :matter-id "M-1"
                                          :direction :outbound :channel :fax
                                          :doc-id "W-1" :recipient-id "R-1"})
      (is (= :work-product-not-issued (:reason (lf-workspace/dispatch-plan s "TX-D")))))))

;; ---------------------------------------------------------------------------
;; Recording the outcome
;; ---------------------------------------------------------------------------

(deftest an-outcome-goes-back-through-the-practices-gate
  (let [s (practice-with-fax)
        g (lf-actor/build-graph {:store s})
        run (lf-actor/run-request!
             g {:op :confirm-transmission :matter-id "M-1" :bengoshi-id "B-1"
                :client-id "C-1"
                :transmission-confirmation
                {:transmission-id "TX-1" :matter-id "M-1" :result :ok
                 :provider :dropbox-fax :provider-id "tx-guid"
                 :provider-status "S" :dialled "+81312345678"
                 :confirmed-on demo/today}}
             {:today demo/today} "t-fax")
        row (first (filter #(= "TX-1" (:transmission-id %))
                           (lf-store/transmissions-of s "M-1")))]
    (is (lf-actor/committed? run))
    (is (= :ok (:result row)))
    (is (= :match (:direction-check row))
        "+81 3-… is the same line as 03-…")
    (is (false? (:misdirected? row)))))

(deftest a-provider-that-dialled-elsewhere-is-recorded-as-an-incident
  (let [s (practice-with-fax)
        g (lf-actor/build-graph {:store s})]
    (lf-actor/run-request!
     g {:op :confirm-transmission :matter-id "M-1" :bengoshi-id "B-1"
        :client-id "C-1"
        :transmission-confirmation
        {:transmission-id "TX-1" :matter-id "M-1" :result :ok
         :dialled "03-9999-0000" :confirmed-on demo/today}}
     {:today demo/today} "t-fax-bad")
    (is (= ["TX-1"] (mapv :transmission-id (lf-transmission/misdirected s "M-1")))
        "recorded, not refused — the pages have already gone")))

;; ---------------------------------------------------------------------------
;; Callback
;; ---------------------------------------------------------------------------

(deftest a-callback-without-the-configured-token-is-refused
  (doseq [token [nil "" "wrong" "toolongtoken"]]
    (is (false? (fax/callback-token-ok? {:fax {:callback-token "secret-token"}} token))
        (str "token " (pr-str token)))))

(deftest a-callback-with-the-configured-token-is-accepted
  (is (true? (fax/callback-token-ok? {:fax {:callback-token "secret-token"}}
                                     "secret-token"))))

(deftest an-unconfigured-callback-token-accepts-nothing
  (testing "an absent token must not become a wildcard"
    (is (false? (fax/callback-token-ok? {:fax {}} "")))
    (is (false? (fax/callback-token-ok? {:fax {}} "anything")))
    (is (false? (fax/callback-token-ok? {:fax {:callback-token ""}} "")))))

(deftest a-callback-on-a-disabled-surface-is-refused
  (is (= :fax-disabled (:reason (fax/on-callback! {:configuration {:fax {:enabled? false}}
                                                   :token "t"})))))

(deftest the-plan-supplies-the-provenance-the-gate-demands
  (testing "a host that made these up would be inventing provenance; without
            them every confirmation is held for :no-client"
    (let [plan (lf-workspace/dispatch-plan (practice-with-fax) "TX-1")]
      (is (= "C-1" (:client-id plan)))
      (is (= "B-1" (:bengoshi-id plan))))))

(deftest a-callback-for-a-guid-this-practice-never-sent-matches-nothing
  (testing "which is also the answer for a forged one"
    (let [s (practice-with-fax)]
      (is (nil? (fax/transmission-by-provider-id s "guid-nobody-sent")))
      (is (nil? (fax/transmission-by-provider-id s nil)))
      (is (nil? (fax/transmission-by-provider-id s ""))))))

(deftest a-callback-is-joined-to-the-transmission-by-the-providers-own-guid
  (let [s (practice-with-fax)]
    (lf-store/register-transmission!
     s (assoc (first (filter #(= "TX-1" (:transmission-id %))
                             (lf-store/transmissions-of s "M-1")))
              :provider-id "tx-guid"))
    (is (= "TX-1" (:transmission-id (fax/transmission-by-provider-id s "tx-guid"))))))
