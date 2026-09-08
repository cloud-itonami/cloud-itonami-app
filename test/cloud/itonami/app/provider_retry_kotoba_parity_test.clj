(ns cloud.itonami.app.provider-retry-kotoba-parity-test
  "What binds `provider_retry_core.kotoba` to `provider_retry/output-budget-exhausted?`.

  `transient-response?` stays cljc because it needs the whole error body (a
  map) and lower-cased error types — neither is on the native slice. The three
  scalar signals of `output-budget-exhausted?` move here.

  The nil rows are the reason this parity test matters more than most: absent
  telemetry must answer false (never throw, never guess), and the cljc's
  `number?` / `true?` guards are exactly the discipline the core reproduces
  with `option-some?` / `option-value`. A change that makes one side treat
  `nil` as present — or a present number as absent — fails this row before it
  reaches production.

  Same caveat as `fleet-core-kotoba-parity-test`: the native compile rows
  assert the core is expressible on native, not that anything runs there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.provider-retry :as retry]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/provider_retry_core.kotoba")
(def ^:private core-source (slurp core-path))

(defn- kotoba-string [s]
  (pr-str (str s)))

;; #:: raw probe: build a call to output-budget-exhausted? with the four args in
;; guest shape. `nil` becomes `(option-none-of ...)`, strings become
;; `(option-some-of ...)`, integers pass through, booleans pass through.
(defn- opt-str [v]
  (cond
    (nil? v) "(option-none-of [:option :string])"
    (string? v) (str "(option-some-of [:option :string] " (kotoba-string v) ")")
    :else (pr-str v)))

(defn- opt-i64 [v]
  (if (nil? v)
    "(option-none-of [:option :i64])"
    (str "(option-some-of [:option :i64] " (long v) ")")))

(defn- call-probe [i fr ct mo jee]
  (str "(defn p" i " [] :bool "
       "(output-budget-exhausted? "
       (opt-str fr) " " (opt-i64 ct) " " (opt-i64 mo) " " (if jee "true" "false") "))"))

(defn- run-probes [cases]
  (let [defs (str/join "\n" (map-indexed (fn [i [fr ct mo jee]]
                                           (call-probe i fr ct mo jee))
                                         cases))
        probes (str/join " " (map (fn [i] (str "p" i)) (range (count cases))))
        src (str (clojure.string/replace-first
                  core-source
                  #"\(:export \[[^\]]+\]\)"
                  (str "(:export [output-budget-exhausted? " probes "])"))
                 "\n" defs "\n")
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map-indexed (fn [i _] [(str "p" i)
                                     (ir/execute kir (symbol (str "p" i)) [])])
                          cases))))

;; All 2^? relevant combinations: finish-reason present/absent/other, token
;; counts present/absent, both-present above/below cap, early-json flag.
(def ^:private exhaustive-cases
  (for [fr [nil "length" "tool_calls"]
        ct [nil 4096 2048]
        mo [nil 2048]
        jee [false true]]
    [fr ct mo jee]))

(deftest output-budget-exhausted-agrees-on-every-combination
  (let [actual (run-probes exhaustive-cases)
        ;; cljc reference — the ORIGINAL judgement, called directly (it now
        ;; re-enters the oracle, so this is a round-trip parity check)
        reference (map (fn [[fr ct mo jee]]
                         (retry/output-budget-exhausted? fr ct mo jee))
                       exhaustive-cases)]
    (doseq [[i c] (map-indexed vector exhaustive-cases)
            :let [expected (nth reference i)]]
      (testing (pr-str c)
        (is (= (boolean expected)
               (boolean (get actual (str "p" i))))
            (str "output-budget-exhausted? disagrees on " (pr-str c)))))))

(deftest answer-false-on-absent-telemetry-not-throw
  ;; The three rows whose whole point is that a missing field is a missing
  ;; field: a bare absent telemetry must be false, and the oracle call must not
  ;; throw. Note the `["length" nil nil false]` row is rightly TRUE — the
  ;; finish-reason alone is sufficient, which is why it is in the obstruction
  ;; set and not here.
  (doseq [c [[nil nil nil false]
             [nil 2048 nil false]
             [nil nil 2048 false]]]
    (is (false? (boolean (retry/output-budget-exhausted? (nth c 0) (nth c 1)
                                                         (nth c 2) (nth c 3))))
        (str "absent telemetry must answer false: " (pr-str c)))))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "provider-retry core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))