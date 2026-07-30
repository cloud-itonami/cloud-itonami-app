(ns cloud.itonami.app.passkey-test
  "The guards on the consent gate, which had no test.

  `cloud.itonami.app.passkey` is one of the two gates the whole authority design rests
  on -- 'did the subject agree' -- and the other one (the actor's Governor) is tested in
  four repositories. This file was missing.

  What is testable here without an authenticator is exactly what matters, because
  `active-transaction!` runs BEFORE any WebAuthn verification: the transaction's kind,
  its expiry, its single use, and the fact that the operation context comes from this
  server's own record rather than from the client. Signature checking is Yubico's and is
  not re-implemented or re-tested here.

  Four properties, each with a named failure mode from the source's own comments:

    kind        a plain sign-in assertion presented to finish-authorization! would make
                an operation 'approved' by a user who only logged in
    single-use  the transaction is consumed BEFORE verification, so a captured response
                cannot be replayed
    expiry      a transaction older than transaction-seconds is not completable
    context     bound server-side, because a client-supplied context could be swapped
                after the user consented

  Every expectation was measured from the namespace first."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.store :as store]))

(def ^:private rp-id "localhost")
(def ^:private origin "http://localhost:8080")

(def ^:private user
  {:id "u-1" :email "subject@example.com" :display-name "Subject"
   ;; base64url, which is what webauthn-bytes parses
   :user-handle "AAAAAAAAAAAAAAAAAAAAAA"})

(defn- with-clean-store [f]
  (store/transact! (constantly (store/initial-state)))
  (store/transact! assoc-in [:identity :users (:id user)] user)
  (f))

(use-fixtures :each with-clean-store)

(defn- transaction [id]
  (get-in (store/snapshot) [:identity :webauthn-transactions id]))

(defn- ex-type
  "The :type of an ex-info, or nil. Deliberately narrow: an assertion expecting
  :passkey/invalid-transaction should FAIL, not pass, if something else was raised."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- threw?
  "Whether f raised anything at all. Needed where the raiser is Yubico's parser rather
  than this code -- a bogus credential response comes back as a Jackson
  ValueInstantiationException, which ex-type above would (correctly) let through."
  [f]
  (try (f) false (catch Throwable _ true)))

(defn- start-authorization []
  (:transaction-id
   (passkey/start-authorization! (:id user) {:digest "abc" :proposal "p-1"}
                                 rp-id origin)))

;; ---------------------------------------------------------------------------
;; the operation context is this server's record, not the client's word
;; ---------------------------------------------------------------------------

(deftest the-authorization-context-is-stored-server-side
  (testing "binding it here rather than trusting the client to echo it back is the
            whole point -- a client-supplied context could be swapped after the user
            consented"
    (let [tid (start-authorization)
          t (transaction tid)]
      (is (= {:digest "abc" :proposal "p-1"} (:authorization-context t)))
      (is (= (:id user) (:expected-user-id t))
          "and the user it was started for, so another signed-in user cannot complete it")
      (is (= :authorization (:kind t)))
      (is (false? (:used? t)))
      (is (some? (:expires-at t))))))

(deftest a-plain-sign-in-carries-no-context-and-no-expected-user
  (testing "which is why the kinds must not be interchangeable: completing an
            authorization with one of these would approve an operation with nothing
            bound to it"
    (let [tid (:transaction-id (passkey/start-authentication! rp-id origin))
          t (transaction tid)]
      (is (= :assertion (:kind t)))
      (is (nil? (:authorization-context t)))
      (is (nil? (:expected-user-id t))))))

;; ---------------------------------------------------------------------------
;; kinds are not interchangeable
;; ---------------------------------------------------------------------------

(deftest a-sign-in-transaction-cannot-be-completed-as-an-authorization
  (testing "the failure mode the source names: an operation would appear approved by
            a user who only logged in"
    (let [tid (:transaction-id (passkey/start-authentication! rp-id origin))]
      (is (= :passkey/invalid-transaction
             (ex-type #(passkey/finish-authorization! tid {:bogus true})))))))

(deftest an-authorization-transaction-cannot-be-completed-as-a-sign-in
  (let [tid (start-authorization)]
    (is (= :passkey/invalid-transaction
           (ex-type #(passkey/finish-authentication! tid {:bogus true}))))))

(deftest a-signing-transaction-is-its-own-kind-in-both-directions
  (testing "an esign assertion's signed bytes ARE the commitment, so it must not be
            usable where a context-bound-by-record assertion is expected, or the
            reverse"
    (let [tid (:transaction-id
               (passkey/start-signing! (:id user) (byte-array 32) {:envelope "e-1"}
                                       rp-id origin))]
      (is (= :esign (:kind (transaction tid))))
      (is (= :passkey/invalid-transaction
             (ex-type #(passkey/finish-authorization! tid {:bogus true}))))
      (is (= :passkey/invalid-transaction
             (ex-type #(passkey/finish-authentication! tid {:bogus true})))))
    (let [tid (start-authorization)]
      (is (= :passkey/invalid-transaction
             (ex-type #(passkey/finish-signing! tid {:bogus true})))))))

(deftest a-rejected-kind-does-not-burn-the-transaction
  (testing "measured, and it matters: active-transaction! throws BEFORE consuming, so
            presenting a pending approval to the wrong endpoint cannot invalidate it.
            If it consumed first, anyone able to call the wrong endpoint could cancel
            an approval the subject is about to give."
    (let [tid (start-authorization)]
      (threw? #(passkey/finish-authentication! tid {:bogus true}))
      (is (false? (:used? (transaction tid)))
          "still completable by the endpoint it was started for"))))

;; ---------------------------------------------------------------------------
;; single use, consumed before verification
;; ---------------------------------------------------------------------------

(deftest a-transaction-is-consumed-before-verification-so-a-response-cannot-be-replayed
  (let [tid (start-authorization)]
    (testing "the first attempt fails on the bogus response -- after marking it used"
      (is (threw? #(passkey/finish-authorization! tid {:bogus true}))
          "some failure, whatever Yubico's parser raises")
      (is (true? (:used? (transaction tid)))
          "consumed even though verification did not succeed, which is what stops a
           captured response being replayed"))
    (testing "and the second attempt is refused as a transaction, not as a signature"
      (is (= :passkey/invalid-transaction
             (ex-type #(passkey/finish-authorization! tid {:bogus true})))))))

;; ---------------------------------------------------------------------------
;; expiry, and unknown ids
;; ---------------------------------------------------------------------------

(deftest an-expired-transaction-is-not-completable
  (store/transact! assoc-in [:identity :webauthn-transactions "t-expired"]
                   {:kind :authorization :used? false
                    :expires-at "2020-01-01T00:00:00Z"
                    :request-json "{}" :rp-id rp-id :origin origin})
  (is (= :passkey/invalid-transaction
         (ex-type #(passkey/finish-authorization! "t-expired" {:bogus true})))))

(deftest an-unknown-transaction-id-is-refused-rather-than-treated-as-fresh
  (is (= :passkey/invalid-transaction
         (ex-type #(passkey/finish-authorization! "no-such-transaction" {:bogus true}))))
  (is (= :passkey/invalid-transaction
         (ex-type #(passkey/finish-authentication! "" {:bogus true})))))

(deftest an-already-used-transaction-stays-used
  (store/transact! assoc-in [:identity :webauthn-transactions "t-used"]
                   {:kind :authorization :used? true
                    :expires-at "2099-01-01T00:00:00Z"
                    :request-json "{}" :rp-id rp-id :origin origin})
  (is (= :passkey/invalid-transaction
         (ex-type #(passkey/finish-authorization! "t-used" {:bogus true})))
      "a far-future expiry does not rescue a used transaction"))

(deftest the-transaction-window-is-five-minutes-and-the-stored-expiry-reflects-it
  (is (= 300 passkey/transaction-seconds))
  (let [t (transaction (start-authorization))
        expires (java.time.Instant/parse (:expires-at t))
        now (java.time.Instant/now)
        seconds (.between java.time.temporal.ChronoUnit/SECONDS now expires)]
    (is (<= 240 seconds 300)
        (str "expiry is about transaction-seconds away, got " seconds))))

;; ---------------------------------------------------------------------------
;; the signing challenge is the commitment digest, and its length is checked
;; ---------------------------------------------------------------------------

(deftest a-signing-challenge-that-is-not-a-sha-256-is-refused
  (testing "the signed bytes are supposed to BE the commitment digest, so a wrong
            length means the caller is signing something else"
    (doseq [n [0 16 31 33 64]]
      (let [d (try (passkey/start-signing! (:id user) (byte-array n) {} rp-id origin)
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :esign/invalid-challenge (:type d)) (str n " bytes"))
        (is (= n (:byte-count d))
            "and it reports the length it got, so the caller can see what it sent"))))
  (testing "32 bytes is accepted"
    (is (some? (:transaction-id
                (passkey/start-signing! (:id user) (byte-array 32) {} rp-id origin))))))

(deftest a-refused-signing-challenge-creates-no-transaction
  (testing "the guard runs before the ceremony starts, so a bad challenge leaves
            nothing half-open for someone to complete"
    (let [before (count (:webauthn-transactions (:identity (store/snapshot))))]
      (threw? #(passkey/start-signing! (:id user) (byte-array 31) {} rp-id origin))
      (is (= before (count (:webauthn-transactions (:identity (store/snapshot)))))))))
