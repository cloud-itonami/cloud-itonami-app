(ns cloud.itonami.app.lawfirm-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.lawfirm :as app-lawfirm]
            [cloud.itonami.app.store :as store]
            [lawfirm.actor :as lf-actor]
            [lawfirm.demo :as demo]
            [lawfirm.projection :as projection]
            [lawfirm.store :as lf-store]
            [lawfirm.workspace :as lf-workspace]
            [mail.inbound :as inbound]
            [mail.mailbox :as mailbox]))

;; ---------------------------------------------------------------------------
;; The capability gate
;; ---------------------------------------------------------------------------

(deftest the-surface-is-off-unless-an-operator-turned-it-on
  (testing "fail-closed: absent, nil and non-true are all off"
    (is (false? (app-lawfirm/enabled? {})))
    (is (false? (app-lawfirm/enabled? {:lawfirm {}})))
    (is (false? (app-lawfirm/enabled? {:lawfirm {:enabled? nil}})))
    (is (false? (app-lawfirm/enabled? {:lawfirm {:enabled? "true"}}))
        "a string is not a decision"))
  (testing "and on only for literal true"
    (is (true? (app-lawfirm/enabled? {:lawfirm {:enabled? true}})))))

(deftest the-shipped-default-is-off
  (testing "holding a practice's 一件記録 is a deployment decision, not a default"
    (is (false? (app-lawfirm/enabled?))
        "the test data dir has no config, so this reads the shipped defaults")))

(deftest a-disabled-surface-reports-nil-rather-than-zero
  (let [s (app-lawfirm/status)]
    (is (false? (:enabled? s)))
    (is (nil? (get-in s [:record :matters]))
        "zero matters would be a measurement of something never looked at")))

(deftest every-other-entry-point-refuses-while-disabled
  (doseq [[label f] [["summary" #(app-lawfirm/summary "2026-07-31")]
                     ["docket" #(app-lawfirm/docket "2026-07-31")]
                     ["practice" #(app-lawfirm/practice)]
                     ["sync-inbound!" #(app-lawfirm/sync-inbound! {:since "1970-01-01"})]
                     ["publish-matter-drive!" #(app-lawfirm/publish-matter-drive! "u" "M-1")]]]
    (testing label
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"無効です" (f))))))

;; ---------------------------------------------------------------------------
;; The durable seam
;;
;; Exercised against a `durable-store` wired to the same version-guarding
;; persist the app installs, rather than against the app's global one — the
;; suite must not write a practice record into the shared state file.
;; ---------------------------------------------------------------------------

(defn- versioned-persist
  "The host-side check `cloud.itonami.app.lawfirm/persist-db!` performs,
  over a plain atom so a test can drive it out of order."
  [state-atom]
  (fn [db]
    (swap! state-atom
           (fn [state]
             (let [current (get-in state [:lawfirm/db lf-store/version-key] 0)
                   incoming (get db lf-store/version-key 0)]
               (if (< incoming current) state (assoc state :lawfirm/db db)))))))

(deftest the-record-round-trips-through-the-hosts-state
  (let [state (atom {})
        s (lf-store/durable-store {:persist! (versioned-persist state)})]
    (store/new-id "unused")                     ; the app's store is untouched
    (lf-store/register-client! s {:client-id "C-1" :name "甲野"})
    (lf-store/register-matter! s {:matter-id "M-1" :client-id "C-1" :status :open})
    (testing "the host holds it"
      (is (= "甲野" (get-in @state [:lawfirm/db :clients "C-1" :name]))))
    (testing "and a store rebuilt from that snapshot is the same practice"
      (let [reloaded (lf-store/durable-store {:snapshot (:lawfirm/db @state)
                                              :persist! (fn [_])})]
        (is (= (lf-store/matters s) (lf-store/matters reloaded)))
        (is (= [] (lf-store/transmissions-of reloaded "M-1")))))))

(deftest the-host-drops-a-snapshot-older-than-what-it-holds
  (testing "the practice publishes before it persists, so two accepted writes
            can arrive here out of order; the version stamp is how the host
            declines the stale one"
    (let [state (atom {})
          persist! (versioned-persist state)]
      (persist! {lf-store/version-key 7 :clients {"C-1" {:name "新しい"}}})
      (persist! {lf-store/version-key 3 :clients {"C-1" {:name "古い"}}})
      (is (= "新しい" (get-in @state [:lawfirm/db :clients "C-1" :name])))
      (is (= 7 (get-in @state [:lawfirm/db lf-store/version-key])))
      (testing "and accepts a newer one"
        (persist! {lf-store/version-key 8 :clients {"C-1" {:name "もっと新しい"}}})
        (is (= "もっと新しい" (get-in @state [:lawfirm/db :clients "C-1" :name])))))))

;; ---------------------------------------------------------------------------
;; InboundPort
;; ---------------------------------------------------------------------------

(defn- fake-entry
  "A mailbox entry shaped like `workspace/inbox-mailbox` produces, carrying a
  body so the test can prove the body does not travel."
  [id received-at from subject text]
  (assoc (mailbox/message-entry
          id id
          (:mail.inbound/message
           (inbound/from-parts {:provider :m365-archive
                                :provider-message-id id
                                :from from
                                :to ["local@cloud-itonami.invalid"]
                                :subject subject
                                :text text
                                :received-at received-at}))
          {:received-at received-at :labels #{:inbox} :read? true})
         :sender {:email from :display from}
         :snippet (subs text 0 (min 20 (count text)))
         :available? true))

(defn- fake-port [entries]
  (reify lf-workspace/InboundPort
    (-arrivals [_ since]
      (filterv #(>= (compare (:received-on %) (str since)) 0)
               (sort-by (juxt :received-on :id)
                        (keep #'app-lawfirm/entry->arrival entries))))))

(def ^:private entries
  [(fake-entry "20260728T090000Z_a.eml" "2026-07-28 18:00" "sender@example.jp"
               "契約書の件" "先日の契約書についてご相談があります。")
   (fake-entry "20260730T120000Z_b.eml" "2026-07-30 21:00" "other@example.jp"
               "判決書送達" "判決書が届きましたので控訴を検討しています。")])

(deftest an-arrival-carries-an-identifier-and-never-the-body
  (let [arrivals (lf-workspace/-arrivals (fake-port entries) "1970-01-01")]
    (is (= 2 (count arrivals)))
    (is (= ["2026-07-28" "2026-07-30"] (mapv :received-on arrivals))
        "the date is the archive's JST calendar day, truncated")
    (is (every? #(= :email (:channel %)) arrivals))
    (is (= "sender@example.jp" (:origin (first arrivals))))
    (testing "the digest is the message id, not the snippet"
      (is (= "20260728T090000Z_a.eml" (:digest (first arrivals))))
      (doseq [a arrivals]
        (is (not (str/includes? (str (:digest a)) "契約書についてご相談")))))
    (testing "and no arrival key holds prose at all"
      (doseq [a arrivals, k [:body :text :content :summary :message :prose]]
        (is (nil? (get a k)))))))

(deftest since-is-inclusive-and-filters
  (let [port (fake-port entries)]
    (is (= 2 (count (lf-workspace/-arrivals port "2026-07-28"))) "inclusive")
    (is (= 1 (count (lf-workspace/-arrivals port "2026-07-29"))))
    (is (= 0 (count (lf-workspace/-arrivals port "2026-07-31"))))))

(deftest an-entry-with-no-usable-date-is-dropped-not-guessed
  (let [broken (assoc (first entries) :mailbox.message/received-at nil)]
    (is (nil? (#'app-lawfirm/entry->arrival broken)))))

(deftest arrivals-become-requests-that-still-have-to-clear-the-gate
  (let [requests (lf-workspace/inbound-requests (fake-port entries) "1970-01-01"
                                                {:bengoshi-id "B-1" :client-id "C-1"})]
    (is (every? #(= :record-inbound-transmission (:op %)) requests))
    (is (= ["IN-20260728T090000Z_a.eml" "IN-20260730T120000Z_b.eml"]
           (mapv #(get-in % [:transmission :transmission-id]) requests))
        "derived from the message id, so a replayed queue does not duplicate")))

(deftest an-arrival-runs-through-the-practice-and-lands
  (testing "end to end over a real store and a real graph, with the app's
            record layer standing in for the host's"
    (let [state (atom {})
          practice (lf-store/durable-store {:persist! (versioned-persist state)})]
      (doseq [b (vals demo/counsel)] (lf-store/register-bengoshi! practice b))
      (lf-store/register-client! practice {:client-id "C-1" :name "株式会社甲野商事"})
      (lf-store/register-matter! practice {:matter-id "M-1" :client-id "C-1"
                                           :bengoshi-id "B-1" :status :open
                                           :domain "corporate"})
      (lf-store/register-conflict-check! practice {:check-id "CC-1" :matter-id "M-1"
                                                   :cleared? true :hits []
                                                   :decided-by "B-1"
                                                   :decided-on "2026-07-01"})
      (let [g (lf-actor/build-graph {:store practice})
            request (-> (lf-workspace/inbound-requests (fake-port entries) "1970-01-01"
                                                       {:bengoshi-id "B-1" :client-id "C-1"})
                        first
                        (assoc :matter-id "M-1")
                        (assoc-in [:transmission :matter-id] "M-1"))
            result (lf-actor/run-request! g request {:today demo/today} "t-1")]
        (is (lf-actor/committed? result)
            (pr-str (get-in result [:state :verdict :violations])))
        (is (= ["IN-20260728T090000Z_a.eml"]
               (mapv :transmission-id (lf-store/transmissions-of practice "M-1"))))
        (testing "and it reached the host's durable state"
          (is (= 1 (count (get-in @state [:lawfirm/db :transmissions])))))))))

;; ---------------------------------------------------------------------------
;; The app adds nothing to the numbers
;; ---------------------------------------------------------------------------

(deftest the-summary-is-the-practices-own-projection
  (let [practice (demo/fresh-store)
        mine (assoc (projection/practice-summary practice demo/today)
                    :schema app-lawfirm/schema)]
    (is (= (dissoc mine :schema)
           (projection/practice-summary practice demo/today))
        "this app recomputes nothing — the only thing it adds is a schema tag")))
