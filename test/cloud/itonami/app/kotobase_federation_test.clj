(ns cloud.itonami.app.kotobase-federation-test
  (:require [cacao.core :as cacao]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.kotobase-federation :as federation]
            [cloud.itonami.app.store :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.time Instant]))

(deftest assertion-is-short-scoped-and-separates-principal-from-controller
  (let [dir (.toFile (Files/createTempDirectory
                      "itonami-kotobase"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        principal "urn:kotoba:principal:018f4d6c-29bf-7f80-9a21-111111111111"
        subject "did:key:zDnaeZpasskeySubject"]
    (with-redefs [config/data-dir (constantly dir)
                  store/snapshot (constantly
                                  {:identity {:users {"u1" {:principal-id principal
                                                            :did "did:key:zUser"}}}})]
      (let [issued (federation/mint-assertion
                    {:user-id "u1" :kind :passkey :active-did subject}
                    (Instant/parse "2026-08-04T00:00:00Z"))
            verified (cacao/verify (:cacao_b64 issued)
                                   {:now "2026-08-04T00:01:00Z"})]
        (is (:valid? verified))
        (is (= federation/audience (get-in verified [:payload :aud])))
        (is (= "kotobase" (:target issued)))
        (is (= "https://auth.kotobase.net/v1/federation/session"
               (:exchange_url issued)))
        (is (= "2026-08-04T00:02:00Z" (:expires_at issued)))
        (is (= #{federation/session-resource
                 federation/datomic-query-resource
                 federation/git-read-resource
                 (federation/subject-resource principal)
                 (federation/controller-resource subject)}
               (set (get-in verified [:payload :resources]))))))))

(deftest each-target-is-a-distinct-rp-for-the-same-principal
  (let [dir (.toFile (Files/createTempDirectory
                      "itonami-murakumo"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        principal "urn:kotoba:principal:018f4d6c-29bf-7f80-9a21-222222222222"
        controller "did:key:zMurakumoPasskeyController"
        session {:user-id "u2" :kind :federated
                 :issued-via :itonami-cloud
                 :authn-provider :itonami-cloud
                 :authn-level :phishing-resistant
                 :authn-decision :authenticated
                 :authn-factors [:webauthn]
                 :active-did controller}]
    (with-redefs [config/data-dir (constantly dir)
                  store/snapshot (constantly
                                  {:identity {:users {"u2" {:principal-id principal
                                                            :did "did:key:zUser"}}}})]
      (let [issued (federation/mint-assertion
                    session :murakumo
                    (Instant/parse "2026-08-28T00:00:00Z"))
            verified (cacao/verify (:cacao_b64 issued)
                                   {:now "2026-08-28T00:01:00Z"})]
        (is (:valid? verified))
        (is (= principal (:subject issued)))
        (is (= controller (:controller issued)))
        (is (= "murakumo" (:target issued)))
        (is (= "https://auth.murakumo.cloud"
               (get-in verified [:payload :aud])))
        (is (= "https://auth.murakumo.cloud/v1/federation/session"
               (:exchange_url issued)))
        (is (str/starts-with? (:return_to issued)
                              "https://auth.murakumo.cloud/sign-in"))))))

(deftest unlisted-target-is-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"許可されていません"
                        (federation/mint-assertion
                         {:user-id "u1" :kind :passkey}
                         :untrusted
                         (Instant/parse "2026-08-28T00:00:00Z")))))

(deftest non-passkey-session-cannot-federate
  (testing "email and agent sessions are not upgraded into passkey assertions"
    (doseq [kind [:email :agent]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Passkey session"
                            (federation/mint-assertion
                             {:user-id "u1" :kind kind})))))
  (testing "ordinary federated SSO is not upgraded"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Passkey session"
                          (federation/mint-assertion
                           {:user-id "u1" :kind :federated
                            :issued-via :itonami-cloud
                            :authn-provider :google
                            :authn-level :single-factor
                            :authn-decision :authenticated
                            :authn-factors [:google]})))))
