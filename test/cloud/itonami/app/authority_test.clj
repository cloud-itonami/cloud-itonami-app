(ns cloud.itonami.app.authority-test
  "The spine's four properties, each tested as a property rather than as a
  happy path: order, content binding, the two gates not substituting, and
  single use."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.store :as store]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def other-session {:user-id "user-2" :organization-id "org-1"})

(defn- reset-proposals! []
  (store/transact! assoc :authority {:proposals {}}))

;; A fixture domain. `:authority/pre-check` refuses a request over the limit --
;; the deterministic, no-model, no-network kind of check the spine requires.
(defn- fixture-domain
  [& {:keys [commit-ok? refusal] :or {commit-ok? true}}]
  {:authority/key :fixture
   :authority/context-type (fn [op] (keyword "fixture" (name (or op :unknown))))
   :authority/pre-check
   (fn [_config _session {:keys [amount]}]
     (when (> (or amount 0) 100)
       (throw (ex-info "over the fixture limit" {:type :fixture/over-limit})))
     {:amount amount})
   :authority/material (fn [v] (str "fixture/" (:amount v)))
   :authority/commit!
   (fn [_config _session p]
     (if commit-ok?
       {:authority/ok? true :authority/record {:recorded (:id p)}}
       {:authority/ok? false
        :authority/refusal (or refusal {:rule :governor-held})}))})

(defn- review! [& {:keys [domain amount sess]
                   :or {amount 10}}]
  (authority/review! (or domain (fixture-domain)) {} (or sess session)
                     {:op :transfer :amount amount}))

;; ---------------------------------------------------------------------------

(deftest a-domain-must-supply-the-whole-contract
  (is (authority/valid-domain? (fixture-domain)))
  (doseq [k [:authority/key :authority/context-type :authority/pre-check
             :authority/material :authority/commit!]]
    (is (not (authority/valid-domain? (dissoc (fixture-domain) k)))
        (str "a domain missing " k " must be rejected")))
  (testing "and review! refuses an invalid domain rather than half-running"
    (reset-proposals!)
    (is (thrown? clojure.lang.ExceptionInfo
                 (authority/review! (dissoc (fixture-domain) :authority/material)
                                    {} session {:op :transfer :amount 1})))))

(deftest review-requires-an-authenticated-user
  (reset-proposals!)
  (is (thrown? clojure.lang.ExceptionInfo
               (authority/review! (fixture-domain) {} {} {:op :transfer :amount 1}))))

(deftest a-refused-pre-check-stores-nothing-and-requests-no-consent
  (reset-proposals!)
  (is (thrown? clojure.lang.ExceptionInfo (review! :amount 1000)))
  (is (empty? (authority/proposals session))
      "a proposal that cannot proceed must never reach the store, because a
       stored proposal is exactly what start-approval! will accept"))

(deftest review-records-a-digest-of-the-domains-own-material
  (reset-proposals!)
  (let [p (review! :amount 10)]
    (is (= :awaiting-passkey (:status p)))
    (is (= :fixture (:authority p)))
    (is (string? (:digest p)))
    (is (nil? (:user-id p)) "ownership fields must not leak to the caller"))
  (testing "the digest is content-dependent"
    (let [a (review! :amount 10) b (review! :amount 11)]
      (is (not= (:digest a) (:digest b)))))
  (testing "and stable for equal content"
    (is (= (:digest (review! :amount 42)) (:digest (review! :amount 42))))))

(deftest digest-requires-a-string
  (testing "hashing a map would make the digest depend on map iteration order,
            which this workspace has already been bitten by"
    (is (thrown? clojure.lang.ExceptionInfo (authority/digest {:a 1})))
    (is (thrown? clojure.lang.ExceptionInfo (authority/digest nil))))
  (is (string? (authority/digest "x")))
  (is (= (authority/digest "x") (authority/digest "x")))
  (is (not= (authority/digest "x") (authority/digest "y"))))

;; --- property 1: ORDER ------------------------------------------------------

(deftest consent-cannot-be-started-for-something-not-pre-checked
  (reset-proposals!)
  (testing "an unknown proposal id is refused before passkey is reached"
    (let [e (try (authority/start-approval! (fixture-domain) session
                                            "no-such-id" "localhost" "http://localhost:1338")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :authority/proposal-not-found (:type (ex-data e))))))
  (testing "and so is another session's proposal"
    (let [p (review! :sess other-session)
          e (try (authority/start-approval! (fixture-domain) session (:id p)
                                            "localhost" "http://localhost:1338")
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :authority/proposal-not-found (:type (ex-data e)))))))

(deftest commit-requires-an-approved-proposal
  (reset-proposals!)
  (let [p (review!)
        e (try (authority/commit! (fixture-domain) {} session (:id p))
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :authority/proposal-not-found (:type (ex-data e))))
    (is (= :awaiting-passkey (:status (authority/proposal (:id p))))
        "a commit attempt must not advance an unapproved proposal")))

;; --- property 2: CONTENT BINDING -------------------------------------------

(defn- match-args [& {:keys [ctx-digest proposal-digest ctx-type result-user]
                      :or {ctx-digest "D" proposal-digest "D"
                           ctx-type :fixture/transfer result-user "user-1"}}]
  {:expected-type :fixture/transfer
   :expected-authority :fixture
   :expected-proposal-id "p1"
   :session-user-id "user-1"
   :proposal {:user-id "user-1" :digest proposal-digest}
   :result {:user-id result-user}
   :context {:type ctx-type :authority :fixture
             :proposal-id "p1" :digest ctx-digest}})

(deftest a-matching-assertion-has-no-issues
  (is (empty? (authority/approval-match-issues (match-args)))))

(deftest a-substituted-proposal-fails-only-the-digest-check
  (testing "everything else about the assertion is still valid -- this is the
            check that makes editing a proposal after consent useless"
    (let [issues (authority/approval-match-issues
                  (match-args :proposal-digest "EDITED"))]
      (is (= [:context/digest-mismatch] (mapv :authority/issue issues))))))

(deftest an-absent-digest-is-refused-not-treated-as-matching
  (let [issues (authority/approval-match-issues
                (match-args :ctx-digest nil :proposal-digest nil))]
    (is (contains? (set (mapv :authority/issue issues)) :context/digest-absent)
        "two nils must not read as a match")))

(deftest each-other-mismatch-names-itself
  (is (= [:context/type-mismatch]
         (mapv :authority/issue (authority/approval-match-issues
                                 (match-args :ctx-type :fixture/other)))))
  (is (= [:user/assertion-mismatch]
         (mapv :authority/issue (authority/approval-match-issues
                                 (match-args :result-user "user-9")))))
  (testing "a wrong authority in the context is refused even with the right type
            and digest -- one domain's consent must not authorise another's"
    (let [args (assoc-in (match-args) [:context :authority] :other)
          issues (authority/approval-match-issues args)]
      (is (= [:context/authority-mismatch] (mapv :authority/issue issues)))))
  (testing "a wrong proposal id in the context is refused"
    (let [args (assoc-in (match-args) [:context :proposal-id] "p2")
          issues (authority/approval-match-issues args)]
      (is (= [:context/proposal-mismatch] (mapv :authority/issue issues))))))

;; --- property 3: THE TWO GATES DO NOT SUBSTITUTE ---------------------------

(defn- approved! [domain & {:keys [amount] :or {amount 10}}]
  (let [p (authority/review! domain {} session {:op :transfer :amount amount})]
    ;; Stand in for the Passkey stage: the consent path is covered by
    ;; approval-match-issues above; here the point is what happens AFTER consent.
    (store/transact! assoc-in [:authority :proposals (:id p) :status] :approved)
    (authority/proposal (:id p))))

(deftest an-authority-refusal-is-recorded-as-a-refusal-not-an-approval
  (reset-proposals!)
  (let [d (fixture-domain :commit-ok? false :refusal {:rule :sanctions-hold})
        p (approved! d)
        out (authority/commit! d {} session (:id p))]
    (is (= :authority-refused (:status out))
        "Passkey consent is not a licence -- the governor decided independently")
    (is (= {:rule :sanctions-hold} (:authority-refusal out)))
    (is (nil? (:authority-record out)))
    (is (authority/terminal? out))))

(deftest a-cleared-commit-records-the-authoritys-own-record
  (reset-proposals!)
  (let [d (fixture-domain)
        p (approved! d)
        out (authority/commit! d {} session (:id p))]
    (is (= :committed (:status out)))
    (is (= {:recorded (:id p)} (:authority-record out)))
    (is (nil? (:authority-refusal out)))))

;; --- property 4: SINGLE USE ------------------------------------------------

(deftest an-approved-proposal-commits-at-most-once
  (reset-proposals!)
  (let [d (fixture-domain)
        p (approved! d)]
    (is (= :committed (:status (authority/commit! d {} session (:id p)))))
    (let [e (try (authority/commit! d {} session (:id p))
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :authority/proposal-not-found (:type (ex-data e)))
          "replaying a consent must be refused"))))

(deftest a-refused-proposal-cannot-be-retried-either
  (reset-proposals!)
  (let [d (fixture-domain :commit-ok? false)
        p (approved! d)]
    (authority/commit! d {} session (:id p))
    (is (thrown? clojure.lang.ExceptionInfo (authority/commit! d {} session (:id p)))
        "both terminal statuses are terminal")))

;; --- rejection and read models ---------------------------------------------

(deftest a-human-can-decline
  (reset-proposals!)
  (let [p (review!)
        out (authority/reject! session (:id p))]
    (is (= :rejected (:status out)))
    (is (authority/terminal? out))
    (is (thrown? clojure.lang.ExceptionInfo (authority/reject! session (:id p))))))

(deftest proposals-are-scoped-to-their-session
  (reset-proposals!)
  (review! :sess session)
  (review! :sess other-session)
  (is (= 1 (count (authority/proposals session))))
  (is (= 1 (count (authority/proposals other-session))))
  (testing "and filterable by authority"
    (is (= 1 (count (authority/proposals session :fixture))))
    (is (zero? (count (authority/proposals session :esim))))))

(deftest status-predicates
  (is (authority/pending? {:status :awaiting-passkey}))
  (is (not (authority/pending? {:status :approved})))
  (doseq [s [:committed :authority-refused :rejected]]
    (is (authority/terminal? {:status s})))
  (doseq [s [:awaiting-passkey :approved]]
    (is (not (authority/terminal? {:status s}))))
  (testing "every status the spine can set is a declared one"
    (is (every? authority/statuses
                [:awaiting-passkey :approved :committed :authority-refused :rejected]))))
