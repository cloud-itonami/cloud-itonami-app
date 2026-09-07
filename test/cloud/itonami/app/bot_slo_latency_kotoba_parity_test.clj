(ns cloud.itonami.app.bot-slo-latency-kotoba-parity-test
  "The p90-latency ladder, in .kotoba and through the host.

  `bot_slo.latency-points` awards a partition 0–20 stability points from its
  90th-percentile response time. The judgement is a threshold ladder over one
  f64; this test pins that the .kotoba spelling and the host's private
  `latency-points` agree on the boundary cases — including exactly-on-the-edge
  values, which is where a `<=` vs `<` mistake hides.

  The host function is private in bot_slo; we reach it through its var
  (#'…/latency-points) so the .kotoba is compared against the *actual* rule,
  not a copy."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.bot-slo :as slo]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private ksrc
  (slurp "src/cloud/itonami/app/bot_slo_latency_core.kotoba"))

(defn- guest [p90]
  (let [src (-> ksrc
                (str/replace #"\(:export \[[^\]]+\]\)"
                             "(:export [latency-points __probe])")
                (str "\n(defn __probe [] :f64 (latency-points " p90 "))\n"))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (ir/execute kir '__probe [])))

(defn- host [p90]
  ;; the real private rule, reached through its var
  (#'cloud.itonami.app.bot-slo/latency-points p90))

;; Both exact boundaries and the sensors between them — a <= / < mistake
;; changes a boundary answer and nothing between it and the next one.
(def ^:private boundary-corpus
  [100.0 119.9 120.0 120.1     ;; interactive boundary
   299.9 300.0 300.1           ;; 300
   599.9 600.0 600.1           ;; 600
   1799.9 1800.0 1800.1        ;; 1800
   5000.0                      ;; far over
   123.456])                   ;; a fraction only f64 carries

(deftest kotoba-and-host-agree-on-the-whole-ladder
  (doseq [p90 boundary-corpus]
    (is (= (host p90) (guest p90))
        (str "latency-points disagreed at p90=" p90))))

(deftest boundary-answers-are-the-step-not-adjacent
  ;; The boundary must award the step it lands on, not the one below or above.
  ;; Uncouples the corpus rows so an agreement-on-everything run cannot mask a
  ;; ladder where both sides shifted the same boundary.
  (is (= 20.0 (guest 120.0)))
  (is (= 15.0 (guest 300.0)))
  (is (= 10.0 (guest 600.0)))
  (is (= 5.0 (guest 1800.0)))
  (is (= 0.0 (guest 1800.1))))