(ns cloud.itonami.app.bot-bounds-kotoba-parity-test
  "The spend-admission decision (may this Bot start another turn), in .kotoba
  and against the host's rule in bot_bounds.cljc/admit-spend.

  The host's core judgement is:

    no budget            -> unbounded
    (< spent (long budget)) -> within
    else                 -> exhausted

  The `.kotoba` core reproduces exactly this three-way outcome. The BOUNDARY is
  the load-bearing part: host uses STRICTLY less, so a spend exactly AT the
  budget is exhausted, one token UNDER is within. A `<` vs `<=` drift changes
  the boundary answer and nothing else — this test pins the boundary rows
  explicitly so that drift cannot hide."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private ksrc
  (slurp "src/cloud/itonami/app/bot_bounds_core.kotoba"))

(def ^:private req-ty
  "[:record :bounds/request [[:has-budget :bool] [:spent :i64] [:budget :i64]]]")

(defn- guest [has-budget spent budget]
  (let [src (-> ksrc
                (str/replace
                 #"\(:export \[[^\]]+\]\)"
                 "(:export [outcome-unbounded outcome-within outcome-exhausted admit-spend __probe])")
                (str "\n(defn __probe [] :i64 "
                     "(admit-spend (record-new " req-ty " "
                     (if has-budget "true" "false")
                     " " spent " " budget ")))\n"))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (ir/execute kir '__probe [])))

;; host code mapping: 0=unbounded 1=within 2=exhausted
(defn- host-code [has-budget spent budget]
  (cond
    (not has-budget) 0
    (< spent budget) 1
    :else 2))

(deftest kotoba-and-host-agree-on-the-whole-frontier
  ;; has-budget x spent x budget, covering both sides of the strictly-less
  ;; boundary plus unbounded.
  (doseq [has-budget [false true]
          budget [10 100 1000]
          spent [0 (- budget 1) budget (+ budget 1)]]
    (is (= (host-code has-budget spent budget)
           (guest has-budget spent budget))
        (str "admit-spend disagreed on {has-budget " has-budget
             " spent " spent " budget " budget "}"))))

(deftest the-boundary-is-strictly-less
  ;; the "< vs <=" question, pinned to the exact rows
  (is (= 1 (guest true (- 10 1) 10)) "one under budget -> within")
  (is (= 2 (guest true 10 10)) "exactly at budget -> exhausted")
  (is (= 2 (guest true (+ 10 1) 10)) "one over -> exhausted")
  (is (= 0 (guest false 999 10)) "no budget -> unbounded regardless of spend"))