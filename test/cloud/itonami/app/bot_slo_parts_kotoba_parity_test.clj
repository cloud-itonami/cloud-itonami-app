(ns cloud.itonami.app.bot-slo-parts-kotoba-parity-test
  "The availability / completion / observability stability limbs, in .kotoba,
  against the host's stated formulas.

  These three limbs are spelled inline in `bot_slo.clj`'s `evaluate` (they are
  not private fns like latency/recovery), so there is no host var to reach.
  This test instead pins the .kotoba against the *formulas the host writes*,
  across the boundaries that a scaling mistake would move:

    availability   = (if (zero? overdue) 18.0 10.0)
    completion     = (* 30.0 (/ rate 100.0))      -> 0.3 * rate,  0..30 @ 0..100
    observability  = (*  7.0 (/ coverage 100.0))  -> 0.07 * cov,  0..7  @ 0..100

  The existing bot_slo-test keeps the assembled stability score honest; this
  test owns the three limbs in isolation so a one-line scoring change in the
  core is caught before it reaches the sum."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private ksrc
  (slurp "src/cloud/itonami/app/bot_slo_stability_parts_core.kotoba"))

(defn- guest [probe]
  (let [src (-> ksrc
                (str/replace
                 #"\(:export \[[^\]]+\]\)"
                 "(:export [availability-points completion-points observability-points __probe])")
                (str "\n(defn __probe [] :f64 " probe ")\n"))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (ir/execute kir '__probe [])))

(deftest availability-is-18-if-none-overdue-else-10
  (is (= 18.0 (guest "(availability-points 0)")))
  (is (= 10.0 (guest "(availability-points 1)")))
  (is (= 10.0 (guest "(availability-points 5)"))))

(deftest completion-is-linear-30-points-over-100
  (is (= 0.0 (guest "(completion-points 0.0)")))
  (is (= 30.0 (guest "(completion-points 100.0)")))
  (is (= 15.0 (guest "(completion-points 50.0)")))
  (is (= 3.0 (guest "(completion-points 10.0)"))))

(deftest observability-is-linear-7-points-over-100
  (is (= 0.0 (guest "(observability-points 0.0)")))
  (is (= 7.0 (guest "(observability-points 100.0)")))
  (is (= 3.5 (guest "(observability-points 50.0)"))))

(deftest the-three-limbs-sum-within-the-advertised-ceiling
  ;; host caps stability at 100; these three (at perfect measurements) plus a
  ;; 20 latency + 15 recovery = 18+30+7+20+15 = 90, leaving headroom.
  (let [best (+ (guest "(availability-points 0)")
                (guest "(completion-points 100.0)")
                (guest "(observability-points 100.0)"))]
    (is (= 55.0 best) "18 + 30 + 7 at perfect measurements")))