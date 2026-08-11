(ns cloud.itonami.app.work-transitions-kotoba-parity-test
  "The Kanban transition table, in cljc and in .kotoba, over the whole 10x10.

  ## Why the whole product

  `work-governance/work-transitions` is a safety rule, not bookkeeping.
  `done`, `rejected` and `cancelled` are terminal, and one wrong entry lets a
  finished work item be leased and run again. A table is also the shape where
  sampling is worthless: every cell is independent, so a corpus that checks
  \"the interesting ones\" checks exactly the ones someone already thought of.
  100 cells is small enough to check all of them, plus the codes outside the
  declared range on both sides.

  ## What crosses the boundary

  Status CODES, not keywords. The keyword-to-code mapping is the host's and
  stays here; the core answers from scalars. The code order is the order
  `work-governance/work-statuses` declares, and `status-order` below is
  asserted against that set rather than assumed to match it -- if a status is
  added there and not here, this test says so instead of quietly renumbering
  the table."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.work-governance :as wg]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/cloud/itonami/app/work_transitions_core.kotoba"))

(def ^:private export-prefix
  (str "status-backlog status-ready status-leased status-running status-held "
       "status-review status-done status-failed status-rejected "
       "status-cancelled status-count status-known? terminal? "
       "transition-legal?"))

(def ^:private status-order
  "Code -> keyword. The core's numbering, written once."
  [:backlog :ready :leased :running :held :review :done :failed
   :rejected :cancelled])

(def ^:private code-of (into {} (map-indexed (fn [i k] [k i]) status-order)))

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

(deftest the-code-order-still-matches-the-declared-statuses
  ;; The one assumption this file makes about the other. If a status is added
  ;; to work-governance and not here, the table below would silently test the
  ;; wrong cells.
  (is (= (set status-order) wg/work-statuses)
      "status-order must name exactly the statuses work-governance declares")
  (is (= (count status-order) (get (run-probes {"n" "(status-count)"} ":i64") "n"))
      "the core's status-count must match the number of statuses"))

(deftest every-cell-of-the-table-agrees
  (let [pairs (for [from status-order to status-order] [from to])
        probes (into {} (map (fn [[from to]]
                               [(str "t" (code-of from) "_" (code-of to))
                                (str "(transition-legal? " (code-of from) " " (code-of to) ")")])
                             pairs))
        actual (run-probes probes ":bool")]
    (is (= 100 (count pairs)) "10 x 10, exhausted")
    (doseq [[from to] pairs]
      (testing (str from " -> " to)
        (is (= (contains? (get wg/work-transitions from #{}) to)
               (get actual (str "t" (code-of from) "_" (code-of to)))))))
    ;; The corpus has to contain both answers, or agreement says nothing.
    (is (= #{true false} (set (vals actual)))
        "the table must have both legal and illegal cells")))

(deftest nothing-leaves-a-terminal-state
  (let [terminals [:done :rejected :cancelled]
        probes (merge
                (into {} (map (fn [k] [(str "term" (code-of k))
                                       (str "(terminal? " (code-of k) ")")])
                              status-order))
                (into {} (for [from terminals to status-order]
                           [(str "x" (code-of from) "_" (code-of to))
                            (str "(transition-legal? " (code-of from) " " (code-of to) ")")])))
        bools (run-probes probes ":bool")]
    (doseq [k status-order]
      (is (= (contains? (set terminals) k) (get bools (str "term" (code-of k))))
          (str k " terminality")))
    (doseq [from terminals to status-order]
      (is (false? (get bools (str "x" (code-of from) "_" (code-of to))))
          (str "no move may leave " from ", and " to " is not an exception")))
    ;; And the cljc agrees that they are dead ends, so this is not a property
    ;; only the core believes.
    (doseq [from terminals]
      (is (empty? (get wg/work-transitions from #{}))
          (str from " must have no outgoing transitions in work-governance")))))

(deftest an-undeclared-status-has-no-moves-in-or-out
  (let [outside [-1 10 99]
        probes (merge
                (into {} (map (fn [c] [(str "k" (Math/abs (int c)))
                                       (str "(status-known? " c ")")]) outside))
                (into {} (for [c outside k [:backlog :ready :running]]
                           [(str "o" (Math/abs (int c)) "_" (code-of k))
                            (str "(transition-legal? " c " " (code-of k) ")")]))
                (into {} (for [c outside k [:backlog :ready :running]]
                           [(str "i" (Math/abs (int c)) "_" (code-of k))
                            (str "(transition-legal? " (code-of k) " " c ")")])))
        bools (run-probes probes ":bool")]
    (doseq [c outside]
      (is (false? (get bools (str "k" (Math/abs (int c))))) (str c " is not a status"))
      (doseq [k [:backlog :ready :running]]
        (is (false? (get bools (str "o" (Math/abs (int c)) "_" (code-of k))))
            (str "nothing moves out of the undeclared code " c))
        (is (false? (get bools (str "i" (Math/abs (int c)) "_" (code-of k))))
            (str "nothing moves into the undeclared code " c))))))

(deftest the-core-compiles-for-every-target-it-claims
  (doseq [target [:wasm32-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (compiler/compile-source core-source target {}))))))
