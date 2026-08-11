(ns cloud.itonami.app.domain-verification-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.domain-verification :as verification]
            [cloud.itonami.app.store :as store]))

(defn- fixture []
  (let [now (store/now)]
    {:state
     (assoc (store/initial-state)
            :identity
            {:organizations
             {"org-a" {:id "org-a" :organization-id "acme" :name "Acme"
                       :status :active}
              "org-b" {:id "org-b" :organization-id "other" :name "Other"
                       :status :active}}
             :users {"user-a" {:id "user-a" :passkey-enrolled? true}
                     "user-b" {:id "user-b" :passkey-enrolled? true}}
             :memberships
             {"membership-a" {:id "membership-a" :organization-id "org-a"
                              :user-id "user-a" :role :owner :created-at now}
              "membership-b" {:id "membership-b" :organization-id "org-b"
                              :user-id "user-b" :role :owner :created-at now}}})
     :session-a {:id "session-a" :kind :passkey :issued-via :passkey
                 :authn-level :phishing-resistant :user-id "user-a"
                 :membership-id "membership-a" :organization-id "org-a"}
     :session-b {:id "session-b" :kind :passkey :issued-via :passkey
                 :authn-level :phishing-resistant :user-id "user-b"
                 :membership-id "membership-b" :organization-id "org-b"}}))

(defn- with-state [run]
  (let [previous @store/state
        f (fixture)]
    (try
      (reset! store/state (:state f))
      (run f)
      (finally (reset! store/state previous)))))

(deftest domains-are-canonical-and-service-owned-names-are-refused
  (is (= "xn--r8jz45g.jp" (verification/normalize-domain "例え.JP.")))
  (is (nil? (verification/normalize-domain "https://example.com/path")))
  (is (nil? (verification/normalize-domain "com")))
  (with-state
    (fn [f]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"サービス管理"
                            (verification/start! (:session-a f)
                                                 {:domain "team.itonami.cloud"}))))))

(deftest a-human-owner-proves-one-exact-txt-record
  (with-state (fn [f]
    (let [started (verification/start! (:session-a f)
                                       {:domain "Example.COM."})]
      (is (= :pending (:status started)))
      (is (= "_itonami-verification.example.com" (:record-name started)))
      (is (re-matches #"itonami-domain-verification=[A-Za-z0-9_-]{43}"
                      (:record-value started)))
      (testing "absence is recorded but never treated as proof"
        (binding [verification/*txt-resolver* (constantly [])]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"まだ確認"
                                (verification/verify!
                                 (:session-a f)
                                 {:verification-id (:id started)})))))
      (testing "the exact value binds the domain to the active organization"
        (binding [verification/*txt-resolver*
                  (fn [owner]
                    (is (= (:record-name started) owner))
                    ["unrelated=value" (:record-value started)])]
          (let [verified (verification/verify!
                          (:session-a f) {:verification-id (:id started)})]
            (is (= :verified (:status verified)))
            (is (= verified
                   (verification/verify!
                    (:session-a f) {:verification-id (:id started)}))
                "retrying a successful confirmation is idempotent")
            (is (= "example.com"
                   (get-in (store/snapshot)
                           [:identity :organizations "org-a" :verified-domain]))))))))))

(deftest ownership-is-organization-scoped-exclusive-and-human-only
  (with-state (fn [f]
    (let [started (verification/start! (:session-a f) {:domain "example.com"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"別の Organization"
                            (verification/verify!
                             (:session-b f) {:verification-id (:id started)})))
      (binding [verification/*txt-resolver*
                (constantly [(:record-value started)])]
        (verification/verify! (:session-a f) {:verification-id (:id started)}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"別の Organization"
                            (verification/start! (:session-b f)
                                                 {:domain "example.com"})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Passkey session"
           (verification/start!
            (assoc (:session-a f) :kind :agent :issued-via :local-ownership)
            {:domain "agent.example"})))))))
