(ns cloud.itonami.app.slo-f64-probe-test
  "What the pinned compiler does with :f64 in the word-typed slice — the type
  bot_slo.clj's latency-points judgement needs.

  Measured (this test): :f64 comparison, if-branch and return lower and run on
  `:wasm32-kotoba-v1`, so a latency score with fractional thresholds CAN be a
  .kotoba core for the parity target. The :x86_64/:aarch64 ISA backends REFUSE
  typed :f64 values (\"typed values currently require ... the qualified native
  one-word string/record/variant/option/result slice\") — f64 is not a native
  one-word type, so a :f64 core is wasm-only, not native-crossable. Both
  directions pinned so the next reader knows not to claim native f64."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private src
  "(ns slo-f64-probe
     (:export [latency-points]))
   (defn latency-points [p90 :f64] :i64
     (if (<= p90 120.0) 20
       (if (<= p90 300.0) 15
         (if (<= p90 600.0) 10
           (if (<= p90 1800.0) 5 0)))))")

(deftest f64-works-on-the-wasm32-parity-target
  (testing :wasm32-kotoba-v1
    (let [{:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
      (is (some? kir) "f64 core must compile for wasm32")
      (is (= 20 (ir/execute kir 'latency-points [50.0])) "<=120 -> 20")
      (is (= 15 (ir/execute kir 'latency-points [200.0])) "300 -> 15")
      (is (= 5 (ir/execute kir 'latency-points [890.0])) "1800 -> 5")
      (is (= 0 (ir/execute kir 'latency-points [2000.0])) "over 1800 -> 0"))))

(deftest f64-is-refused-by-the-native-isa-backends
  ;; The negative boundary: this is NOT a native-crossable core. A :f64 value
  ;; is refused by x86_64/aarch64 with the typed-value one-word-slice
  ;; requirement. Pinning this so nobody assumes native f64.
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (let [thrown (try
                     (compiler/compile-source src target {})
                     nil
                     (catch Exception e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown)
            (str "f64 core must be refused for " (name target)
                 " (native typed-value one-word slice)"))
        (is (re-find #"typed values currently require"
                     (ex-message thrown))
            (str "the native refusal must name the typed-value slice for "
                 (name target)))))))