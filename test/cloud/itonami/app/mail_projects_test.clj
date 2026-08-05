(ns cloud.itonami.app.mail-projects-test
  "Filing mail against local projects.

  The behaviours worth pinning are the refusals and the arithmetic, not the
  happy path: a filing system is trusted in proportion to how loudly it admits
  what it did not file."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.mail-projects :as mail-projects]
            [cloud.itonami.app.project-repository :as projects]
            [cloud.itonami.app.store :as store]))

(def ^:private organization "org-mail-test")

(defn- message [id from subject & [labels]]
  {:id id :from-email from :from from :subject subject
   :received-at (str "2026-08-0" (inc (mod (count id) 9)) "T00:00:00Z")
   :labels (set (or labels []))})

(defn- seed-messages! [& messages]
  (store/transact! assoc-in [:mail :messages]
                   (into {} (map (juxt :id identity)) messages)))

(defn- reset! []
  (store/transact!
   (fn [state]
     (-> state
         (update :mail dissoc :messages :project-rules :project-assignments)
         (dissoc :chat-projects :project-workspaces :drive-artifacts)))))

(use-fixtures :each (fn [run] (reset!) (run) (reset!)))

(defn- project! [id]
  (projects/create-project! {:organization-id organization
                             :user-id "user-1" :project-id id}
                            {:title id}))

;; ---------------------------------------------------------------------------
;; rules

(deftest a-rule-cannot-name-a-project-that-does-not-exist
  (testing "a typo would otherwise file mail into a project nobody can open,
            and it would look like it worked"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"project がありません"
         (mail-projects/add-rule! organization
                                  {:project "alpah"
                                   :match {:from-domain "example.com"}})))
    (is (empty? (mail-projects/rules organization)))))

(deftest a-rule-must-say-something
  (project! "alpha")
  (testing "a rule with no clauses would match every message ever received"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"条件を1つ以上"
         (mail-projects/add-rule! organization {:project "alpha" :match {}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"条件を1つ以上"
         (mail-projects/add-rule! organization
                                  {:project "alpha"
                                   :match {:from-domain "   "}})))))

(deftest a-domain-clause-is-anchored
  (project! "alpha")
  (let [rule (:rule (mail-projects/add-rule!
                     organization
                     {:project "alpha" :match {:from-domain "example.com"}}))]
    (testing "subdomains match"
      (is (mail-projects/matches? rule (message "1" "a@mail.example.com" "x")))
      (is (mail-projects/matches? rule (message "2" "a@example.com" "x"))))
    (testing "a domain that merely ends with those characters does not"
      (is (not (mail-projects/matches? rule (message "3" "a@notexample.com" "x"))))
      (is (not (mail-projects/matches? rule (message "4" "a@example.com.cn" "x")))))))

(deftest every-clause-must-hold
  (project! "alpha")
  (let [rule (:rule (mail-projects/add-rule!
                     organization
                     {:project "alpha"
                      :match {:from-domain "example.com"
                              :subject-contains "invoice"}}))]
    (testing "narrowing is what writing a second clause is for"
      (is (mail-projects/matches? rule (message "1" "a@example.com" "Your INVOICE")))
      (is (not (mail-projects/matches? rule (message "2" "a@example.com" "hello"))))
      (is (not (mail-projects/matches? rule (message "3" "a@other.com" "invoice")))))))

(deftest a-label-clause-reads-what-classify-derived
  (project! "alpha")
  (let [rule (:rule (mail-projects/add-rule! organization
                                             {:project "alpha"
                                              :match {:label "finance"}}))]
    (is (mail-projects/matches? rule (message "1" "a@x.com" "hi" [:finance])))
    (is (not (mail-projects/matches? rule (message "2" "a@x.com" "hi" [:newsletter]))))))

(deftest the-first-matching-rule-wins
  (project! "alpha")
  (project! "beta")
  (mail-projects/add-rule! organization
                           {:project "alpha" :match {:from-domain "example.com"}})
  (mail-projects/add-rule! organization
                           {:project "beta" :match {:from-domain "example.com"}})
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/apply-rules! organization)
  (is (= "alpha" (:project-id (get (mail-projects/assignments organization) "m1")))
      "order is the organization's own and must be visible in the outcome"))

(deftest a-removed-rule-is-named-when-it-was-not-there
  (project! "alpha")
  (let [rule (:rule (mail-projects/add-rule! organization
                                             {:project "alpha"
                                              :match {:label "finance"}}))]
    (is (:ok? (mail-projects/remove-rule! organization (:rule/id rule))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rule がありません"
                          (mail-projects/remove-rule! organization "rule-nope")))))

;; ---------------------------------------------------------------------------
;; applying

(deftest applying-reports-what-it-did-not-file
  (project! "alpha")
  (mail-projects/add-rule! organization
                           {:project "alpha" :match {:from-domain "example.com"}})
  (seed-messages! (message "m1" "a@example.com" "x")
                  (message "m2" "b@example.com" "y")
                  (message "m3" "c@elsewhere.jp" "z"))
  (let [result (mail-projects/apply-rules! organization)]
    (is (= 3 (:considered result)))
    (is (= 2 (:assigned result)))
    (is (= 1 (:unmatched result))
        "the pile the rules do not catch is the number worth reading")
    (is (= 2 (:changed result))))

  (testing "running it again changes nothing"
    (let [again (mail-projects/apply-rules! organization)]
      (is (= 2 (:assigned again)))
      (is (= 0 (:changed again))))))

(deftest a-rule-never-undoes-a-human-decision
  (project! "alpha")
  (project! "beta")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/assign! organization "m1" "beta" "user-1")
  (mail-projects/add-rule! organization
                           {:project "alpha" :match {:from-domain "example.com"}})
  (let [result (mail-projects/apply-rules! organization)]
    (testing "filing something by hand would otherwise last until the next sync"
      (is (= "beta" (:project-id (get (mail-projects/assignments organization) "m1"))))
      (is (= 0 (:considered result)))
      (is (= 1 (:manual result))))))

;; ---------------------------------------------------------------------------
;; manual assignment

(deftest assignment-refuses-what-it-cannot-resolve
  (project! "alpha")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"そのメールはありません"
                        (mail-projects/assign! organization "nope" "alpha" "user-1")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"project がありません"
                        (mail-projects/assign! organization "m1" "nope" "user-1"))))

(deftest unassigning-does-not-delete-the-message
  (project! "alpha")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/assign! organization "m1" "alpha" "user-1")
  (mail-projects/unassign! organization "m1")
  (is (empty? (mail-projects/assignments organization)))
  (is (some? (get-in (store/snapshot) [:mail :messages "m1"]))
      "it returns to the inbox it never left"))

;; ---------------------------------------------------------------------------
;; reading

(deftest the-overview-counts-both-sides
  (project! "alpha")
  (mail-projects/add-rule! organization
                           {:project "alpha" :match {:from-domain "example.com"}})
  (seed-messages! (message "m1" "a@example.com" "x")
                  (message "m2" "c@elsewhere.jp" "z"))
  (mail-projects/apply-rules! organization)
  (let [overview (mail-projects/overview organization)]
    (is (= 2 (:messages overview)))
    (is (= 1 (:assigned overview)))
    (is (= 1 (:unassigned overview)))
    (is (= [{:project-id "alpha" :count 1 :manual 0}] (:projects overview)))))

(deftest project-mail-lists-only-that-project
  (project! "alpha")
  (project! "beta")
  (seed-messages! (message "m1" "a@example.com" "one")
                  (message "m2" "b@example.com" "two"))
  (mail-projects/assign! organization "m1" "alpha" "user-1")
  (mail-projects/assign! organization "m2" "beta" "user-1")
  (let [alpha (mail-projects/project-mail organization "alpha")]
    (is (= 1 (count (:items alpha))))
    (is (= "one" (:subject (first (:items alpha)))))
    (is (= "manual" (:assigned-by (first (:items alpha)))))))

(deftest unassigned-groups-the-senders-worth-a-rule
  (project! "alpha")
  (seed-messages! (message "m1" "a@loud.example" "x")
                  (message "m2" "b@loud.example" "y")
                  (message "m3" "c@quiet.jp" "z"))
  (let [loose (mail-projects/unassigned organization)]
    (is (= 3 (:count loose)))
    (is (= {:from-domain "loud.example" :count 2} (first (:senders loose)))
        "the useful next action is one more rule for the domain that keeps
         appearing, so the senders are ranked")))

(deftest one-organization-does-not-file-into-another
  (project! "alpha")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/assign! organization "m1" "alpha" "user-1")
  (is (empty? (mail-projects/assignments "org-other")))
  (is (= 0 (:assigned (mail-projects/overview "org-other")))))
