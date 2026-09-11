(ns cloud.itonami.app.vf-calibrate-kotoba-parity-test
  "What binds `vf_calibrate_core.kotoba` to the bucket decision inside
  `cloud.itonami.app.vf-calibrate` (`calibration` and `calibrated-model`).

  `vf_calibrate.clj` is the XMILE calibration bridge (ADR-2609111230 slice
  3): a measured event recalibrates the constants its resource is bound to,
  nothing else moves. Most of that file is mechanism the native slice
  refuses — reading the correspondence table (a map), `xmodel/lookup`,
  `Double/parseDouble`, `assoc-in`, assembling report maps — and stays
  host. What moves here is the bucket DECISION:

    which of the three buckets does a correspondence land in?

      measure absent      -> \"unmatched\"  (the honest default, nothing moves)
      measured + leaf     -> \"rebased\"    (the model's operating point becomes
                                            the journal's measured present)
      measured + not-leaf -> \"ignored\"    (a binding onto a COMPUTED variable)

  The host prepares the two facts that are not on the slice (whether the
  inventory carries a measure: a `get-in`; whether the bound constant is a
  leaf: `xmodel/lookup` + `Double/parseDouble`) and routes on the verdict
  as the cljc's `cond` did — the verdict is a total function of the two
  booleans, so this IS the original decision, expressed on the native
  word-typed slice.

  The end-to-end journal→XMILE behavior (event appended, model rebased,
  report rows) is proven by `vf_calibrate_test.clj`, which now runs through
  the oracle path once the shipped KIR is regenerated.

  Same caveat as the sibling suites: the native compile rows assert the core
  is expressible on native, not that anything runs there."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/vf_calibrate_core.kotoba")
(def ^:private core-source (slurp core-path))

(defn- kotoba-string [s]
  (pr-str (str s)))

(defn- bool-lit [b]
  (if b "true" "false"))

;; #:: raw probe: build a zero-arg wrapper around the export under test, with
;; the two booleans in guest shape.
(defn- call-probe [i has-measure? is-leaf?]
  (str "(defn p" i " [] :string "
       "(calibration-kind " (bool-lit has-measure?) " " (bool-lit is-leaf?) "))"))

(defn- run-probes [cases]
  (let [defs (str/join "\n" (map-indexed (fn [i [hm il]] (call-probe i hm il))
                                         cases))
        probes (str/join " " (map (fn [i] (str "p" i)) (range (count cases))))
        src (str (str/replace-first
                  core-source
                  #"\(:export \[[^\]]+\]\)"
                  (str "(:export [calibration-kind " probes "])"))
                 "\n" defs "\n")
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map-indexed (fn [i _] [(str "p" i)
                                     (ir/execute kir (symbol (str "p" i)) [])])
                          cases))))

;; The ORIGINAL decision table, written out independently of the new oracle
;; calls: the cljc's `(cond (nil? measure) :unmatched (leaf-constant? ...)
;; :rebased :else :ignored)` is exactly this function of the two booleans.
(defn- original-bucket [has-measure? is-leaf?]
  (cond
    (not has-measure?) "unmatched"
    is-leaf? "rebased"
    :else "ignored"))

(def ^:private combos
  (for [hm [false true] il [false true]] [hm il]))

(deftest calibration-kind-agrees-with-the-original-cond
  (let [actual (run-probes combos)]
    (doseq [[i [hm il]] (map-indexed vector combos)]
      (testing (str "has-measure?=" hm " is-leaf?=" il)
        (is (= (original-bucket hm il) (get actual (str "p" i)))
            (str "calibration-kind disagrees on has-measure?=" hm
                 " is-leaf?=" il))))))

(deftest absent-measure-answers-unmatched-not-throw
  ;; The contract row: a correspondence with no measurement must be
  ;; \"unmatched\" (nothing moves) on both sides — the absent-measure limb
  ;; fires first, exactly like the cljc's `(nil? measure)` first branch.
  (is (= "unmatched" (get (run-probes [[false false] [false true]]) "p0")))
  (is (= "unmatched" (get (run-probes [[false false] [false true]]) "p1")))
  (is (= "unmatched" (original-bucket false false)))
  (is (= "unmatched" (original-bucket false true))))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "vf-calibrate core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))