(ns cloud.itonami.app.humanity-trust-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.humanity-trust :as humanity]
            [identity.adapters.eas :as eas]
            [identity.adapters.human-passport :as passport]
            [identity.trust-profile :as trust-profile]))

(def uid (str "0x" (apply str (repeat 64 "1"))))
(def recipient (str "0x" (apply str (repeat 40 "b"))))
(def now 1800000000)
(def schema {:uid trust-profile/human-passport-schema-uid
             :schema passport/score-schema :revocable? true})
(def attestation
  {:uid uid :schema-uid trust-profile/human-passport-schema-uid
   :attester trust-profile/human-passport-attester
   :recipient recipient :time (- now 100) :expiration-time 0
   :revocation-time 0 :revocable? true :data "0xencoded"})
(def reader
  (eas/static-reader
   {:schemas {trust-profile/human-passport-schema-uid schema}
    :attestations {uid attestation}}))
(def decoded
  {:passing-score true :score-decimals 4 :scorer-id 335
   :score 350000 :threshold 200000
   :stamps [{:provider "BrightID" :score 10000}]})

(deftest accepted-evidence-is-principal-bound-and-capability-free
  (let [persisted (atom nil)
        result (humanity/verify-with!
                {:reader reader :decoder (passport/static-decoder decoded)
                 :now now :persist! (fn [bundle decision]
                                      (reset! persisted [bundle decision]))}
                {:principal-id "urn:kotoba:principal:alice"
                 :recipient recipient}
                uid)]
    (is (:verified result))
    (is (= "evidence-only" (:effect result)))
    (is (false? (:grants-capability result)))
    (is (= "urn:kotoba:principal:alice" (:subject-principal result)))
    (is (some? @persisted))))

(deftest another-wallet-cannot-contribute-the-principals-evidence
  (testing "a valid attestation for another recipient fails after EAS verification"
    (is (= :trust/subject-binding-failed
           (try
             (humanity/verify-with!
              {:reader reader :decoder (passport/static-decoder decoded)
               :now now :persist! (fn [& _] (throw (Exception. "must not persist")))}
              {:principal-id "urn:kotoba:principal:alice"
               :recipient "0x0000000000000000000000000000000000000001"}
              uid)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest stale-or-revoked-evidence-never-reaches-persistence
  (doseq [[label changed]
          [[:stale (assoc attestation :time
                          (- now (inc passport/max-score-age-seconds)))]
           [:revoked (assoc attestation :revoked? true :revocation-time 1)]]]
    (let [persisted? (atom false)
          changed-reader
          (eas/static-reader
           {:schemas {trust-profile/human-passport-schema-uid schema}
            :attestations {uid changed}})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (humanity/verify-with!
                    {:reader changed-reader
                     :decoder (passport/static-decoder decoded)
                     :now now
                     :persist! (fn [& _] (reset! persisted? true))}
                    {:principal-id "urn:kotoba:principal:alice"
                     :recipient recipient}
                    uid))
          (name label))
      (is (false? @persisted?) (name label)))))
