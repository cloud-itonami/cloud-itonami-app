(ns cloud.itonami.app.approval-kotoba-parity-test
  "Approval eligibility and the tally, in cljc and in .kotoba.

  ## What is actually at risk

  This rule decides whether a work item has human authority behind it. Two
  properties carry that, and each is one comparison from being lost:

  - a decision bound to DIFFERENT content must not count. Approving revision A
    must not approve revision B.
  - a veto must beat a satisfied minimum. Reversing the tally's two branches
    approves something that was rejected.

  Both are asserted as properties here, not only as agreement with the cljc --
  agreement would still hold if both sides were wrong together.

  ## Eligibility is exhausted, the tally is not

  Eligibility is ten booleans: 1024 combinations, cheap, and every one is
  checked against `work-governance`'s own predicate driven through
  `approval-state`. A conjunction is exactly the shape where sampling misses a
  dropped term.

  The tally is 2 x counts x counts, so its corpus is chosen: the boundaries
  around `minimum`, zero and one rejection, and both veto modes.

  ## Why the cljc side goes through approval-state

  `eligible-decision?` is private and stays private. The test builds a policy,
  an item, a performer, an assignment and one decision so that
  `approval-state` reports the decision as eligible or ignored, which is the
  observable the namespace actually exposes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.work-governance :as wg]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/cloud/itonami/app/approval_core.kotoba"))

(def ^:private export-prefix
  (str "status-pending status-approved status-rejected "
       "verification-satisfied? separation-satisfied? eligible? "
       "minimum-met? veto-triggered? status main"))

(def ^:private eligibility-ty
  (str "[:record :approval/eligibility [[:same-organization :bool] "
       "[:same-capability :bool] [:same-work-item :bool] "
       "[:same-content-hash :bool] [:actor-is-person :bool] "
       "[:has-eligible-role :bool] [:requires-user-verification :bool] "
       "[:user-verified :bool] [:separation-of-duties :bool] "
       "[:actor-is-submitter :bool]]]"))

(def ^:private tally-ty
  (str "[:record :approval/tally [[:veto-mode :bool] [:rejected-count :i64] "
       "[:approved-count :i64] [:minimum :i64]]]"))

(defn- run-probes [probes result-type]
  (let [defs (for [[name body] probes]
               (str "(defn " name " [] " result-type " " body ")"))
        src (-> core-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " "
                      (str/join " " (map first probes)) "])"))
                (str "\n" (str/join "\n" defs)))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _]] [n (ir/execute kir (symbol n) [])]) probes))))

;; ── the cljc side, driven through its public door ───────────────────────────

(def ^:private fields
  [:same-organization :same-capability :same-work-item :same-content-hash
   :actor-is-person :has-eligible-role :requires-user-verification
   :user-verified :separation-of-duties :actor-is-submitter])

(defn- cljc-eligible?
  "Whether work-governance counts one decision, built so each boolean above is
  expressed in the values it actually reads."
  [f]
  (let [org "org-1" other-org "org-2"
        cap :capability/ship
        actor "actor-1" submitter (if (:actor-is-submitter f) "actor-1" "actor-2")
        policy {:approval.policy/id "p1"
                :approval.policy/organization org
                :approval.policy/capability cap
                :approval.policy/eligible-roles #{:reviewer}
                :approval.policy/minimum 1
                :approval.policy/requires-user-verification?
                (boolean (:requires-user-verification f))
                :approval.policy/separation-of-duties?
                (boolean (:separation-of-duties f))
                :approval.policy/rejection-mode :threshold}
        item {:work.item/id "w1"
              :work.item/organization (if (:same-organization f) org other-org)
              :work.item/project "proj"
              :work.item/title "t"
              :work.item/capability (if (:same-capability f) cap :capability/other)
              :work.item/yakuwari :role/dev
              :work.item/content-hash "hash-a"
              :work.item/submitted-by submitter}
        performers [{:performer/id actor
                     :performer/organization org
                     :performer/kind (if (:actor-is-person f) :person :system)
                     :performer/name "A"}]
        ;; `assignment-for` matches on :org.assignment/performer and requires
        ;; :active; the id and position are validator requirements. Getting any
        ;; of these wrong makes every row ineligible, which agrees with a broken
        ;; core on 512 of 1024 rows -- so the fixture is asserted below rather
        ;; than trusted.
        assignments [{:org.assignment/id "a1"
                      :org.assignment/organization org
                      :org.assignment/performer actor
                      :org.assignment/position "reviewer"
                      :org.assignment/status :active
                      :org.assignment/roles (if (:has-eligible-role f)
                                              #{:reviewer} #{:other})}]
        decision {:approval.decision/actor actor
                  :approval.decision/work-item (if (:same-work-item f) "w1" "w9")
                  :approval.decision/content-hash (if (:same-content-hash f)
                                                    "hash-a" "hash-b")
                  :approval.decision/decision :approved
                  :approval.decision/user-verified? (boolean (:user-verified f))}
        state (wg/approval-state policy item performers assignments [decision])]
    (empty? (:approval/ignored state))))

(defn- literal [f]
  (str "(record-new " eligibility-ty " "
       (str/join " " (map #(boolean (get f %)) fields)) ")"))

(def ^:private all-combinations
  (map (fn [n]
         (into {} (map-indexed (fn [i k] [k (bit-test n i)]) fields)))
       (range 1024)))

(deftest the-fixture-can-actually-produce-an-eligible-decision
  ;; Without this, a fixture missing a required field throws or is ignored for
  ;; every row, and "both sides say ineligible" looks like agreement.
  (is (true? (cljc-eligible? (assoc (zipmap fields (repeat true))
                                    :actor-is-submitter false)))
      "some row must be eligible, or every comparison below is false = false")
  (is (false? (cljc-eligible? (zipmap fields (repeat false))))
      "and some row must not be")
  ;; All-true is NOT eligible, and that is the separation-of-duties rule
  ;; working: the row has :separation-of-duties AND :actor-is-submitter, so the
  ;; submitter is refused their own approval. The first version of this test
  ;; asserted the opposite -- its label said "minus the submitter conflict"
  ;; while the value kept it.
  (is (false? (cljc-eligible? (zipmap fields (repeat true))))
      "the submitter may not approve their own item under separation of duties"))

(deftest eligibility-agrees-over-all-1024-combinations
  ;; Batched, because one compile per row would be a thousand compiles.
  (doseq [batch (partition-all 64 (map-indexed vector all-combinations))]
    (let [probes (into {} (map (fn [[i f]] [(str "e" i) (str "(eligible? " (literal f) ")")])
                               batch))
          actual (run-probes probes ":bool")]
      (doseq [[i f] batch]
        (testing (pr-str (into (sorted-map) (filter val f)))
          (is (= (cljc-eligible? f) (get actual (str "e" i)))))))))

(deftest a-decision-bound-to-other-content-never-counts
  ;; Stated as a property. Every combination with a mismatched content hash is
  ;; ineligible, whatever else is true.
  (let [rows (filter #(not (:same-content-hash %)) all-combinations)]
    (is (= 512 (count rows)))
    (doseq [batch (partition-all 64 (map-indexed vector rows))]
      (let [probes (into {} (map (fn [[i f]] [(str "c" i) (str "(eligible? " (literal f) ")")])
                                 batch))
            actual (run-probes probes ":bool")]
        (doseq [[i _] batch]
          (is (false? (get actual (str "c" i)))
              "a decision about other content is never eligible"))))))

(def ^:private tally-corpus
  (for [veto [true false] rejected [0 1 3] approved [0 1 2 3] minimum [1 2]]
    {:veto-mode veto :rejected-count rejected
     :approved-count approved :minimum minimum}))

(defn- cljc-status [{:keys [veto-mode rejected-count approved-count minimum]}]
  ;; The same three-way choice work-governance makes, kept next to the corpus
  ;; so the ordering under test is visible rather than buried in a `cond`.
  (cond (and veto-mode (pos? rejected-count)) :rejected
        (>= approved-count minimum) :approved
        :else :pending))

(deftest the-tally-agrees-and-a-veto-outranks-a-satisfied-minimum
  (let [codes {:pending 0 :approved 1 :rejected 2}
        probes (into {} (map-indexed
                         (fn [i t]
                           [(str "s" i)
                            (str "(status (record-new " tally-ty " "
                                 (:veto-mode t) " " (:rejected-count t) " "
                                 (:approved-count t) " " (:minimum t) "))")])
                         tally-corpus))
        actual (run-probes probes ":i64")]
    (is (= 48 (count tally-corpus)))
    (is (= #{0 1 2} (set (vals actual))) "the corpus reaches all three statuses")
    (doseq [[i t] (map-indexed vector tally-corpus)]
      (testing (pr-str t)
        (is (= (codes (cljc-status t)) (get actual (str "s" i))))))
    ;; The property, not just the agreement: under veto, a rejection wins even
    ;; when the approvals would otherwise satisfy the minimum.
    (doseq [[i t] (map-indexed vector tally-corpus)
            :when (and (:veto-mode t) (pos? (:rejected-count t))
                       (>= (:approved-count t) (:minimum t)))]
      (is (= 2 (get actual (str "s" i)))
          (str "a veto must outrank a satisfied minimum: " (pr-str t))))))

(deftest the-core-compiles-for-every-target-it-claims
  (doseq [target [:wasm32-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (compiler/compile-source core-source target {}))))))
