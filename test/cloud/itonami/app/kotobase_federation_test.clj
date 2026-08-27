(ns cloud.itonami.app.kotobase-federation-test
  (:require [cacao.core :as cacao]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.kotobase-federation :as federation]
            [cloud.itonami.app.store :as store]
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
        (is (= "2026-08-04T00:02:00Z" (:expires_at issued)))
        (is (= #{federation/session-resource
                 federation/datomic-query-resource
                 federation/git-read-resource
                 (federation/subject-resource principal)
                 (federation/controller-resource subject)}
               (set (get-in verified [:payload :resources]))))))))

(deftest non-passkey-session-cannot-federate
  (testing "email and agent sessions are not upgraded into passkey assertions"
    (doseq [kind [:email :agent]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Passkey session"
                            (federation/mint-assertion
                             {:user-id "u1" :kind kind}))))))
