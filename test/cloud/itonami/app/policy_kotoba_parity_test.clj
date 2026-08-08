(ns cloud.itonami.app.policy-kotoba-parity-test
  "What binds `policy.kotoba` to `policy.cljc`.

  Before this test, `policy.cljc` said in a docstring that it was \"the
  host-side mirror of policy.kotoba\", and nothing checked it. The two files
  did not share a function: the `.kotoba` exported a `select-provider-tier`
  with no caller anywhere in the repository, and `provider-allowed?` had no
  counterpart on the other side. A claimed mirror that nothing checks is two
  implementations, and only one of them gets fixed.

  So the claim is executed instead of written. Both implementations answer the
  same questions over the same inputs, and `provider-allowed?` is exercised
  over ALL sixteen combinations of its four booleans rather than a chosen few —
  the interesting cases here are the ones where a reader expects a fallback
  (an enabled cloud provider with only one of the two gates open) and there
  must not be one.

  The last two deftests are a different kind of check: they compile the core
  for the two native ISAs and the two portable targets. That is what keeps the
  decision core a decision core — reach for a set literal or a map and the
  compile refuses, which is the whole reason the file is written the way it is
  (see its header, and ADR-2608650000).

  Measured honestly, 2026-08-08: at the pinned compiler, every construct tried
  that native refused, the portable targets refused too — a set literal with
  `contains?`, `string-contains?`, and a `let`-bound closure all failed on
  `:wasm32-kotoba-v1` and `:x86_64-kotoba-v1` alike. So the native rows are not
  today catching anything the portable rows would miss. They are here for the
  claim they make directly — the decision core is expressible on native — and
  they will start discriminating when the portable targets move ahead of the
  native slice, which is the direction of travel."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.policy :as policy]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/policy.kotoba")

(def ^:private core-source (slurp core-path))

(def ^:private provider-type
  "The record descriptor, spelled exactly as `policy.kotoba` declares it.

  Written out rather than read back out of the source on purpose: if the
  schema in the source changes shape, these wrappers stop compiling and the
  test fails loudly, instead of silently following the change."
  (str "[:record :policy/provider "
       "[[:enabled :bool] [:local :bool] "
       "[:cloud-enabled :bool] [:cloud-reviewed :bool]]]"))

(defn- kotoba-bool [b] (if b "true" "false"))

(defn- kotoba-string [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- with-probes
  "Append zero-arg `:bool` wrappers to the core and widen its `:export`.

  Every case is a wrapper rather than a direct call with host arguments,
  because a record cannot be handed across the entry boundary as a literal —
  it is constructed inside the guest. This is the shape
  `murakumo/test/murakumo/infer_join_kotoba_parity_test.clj` uses, for the
  same reason."
  [probes]
  (let [names (str/join " " (map first probes))
        defs (str/join "\n" (map (fn [[n body]]
                                   (str "(defn " n " [] :bool " body ")"))
                                 probes))]
    (str (str/replace-first core-source
                            #"\(:export \[[^\]]+\]\)"
                            (str "(:export [loopback-host? provider-allowed? " names "])"))
         "\n" defs "\n")))

(defn- run-probes
  "Compile once, then execute every wrapper. One compile, not one per case."
  [probes]
  (let [{:keys [kir]} (compiler/compile-source (with-probes probes)
                                               :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _]] [n (ir/execute kir (symbol n) [])]) probes))))

;; ---------------------------------------------------------------------------
;; loopback-host?

(def ^:private hosts
  ["127.0.0.1" "localhost" "::1"
   ;; not loopback, and each one is here because it is a near miss
   "127.0.0.2" "localhost.example.com" "LOCALHOST" "::2" "" "0.0.0.0"])

(deftest loopback-host-agrees
  (let [probes (map-indexed (fn [i h]
                              [(str "h" i)
                               (str "(loopback-host? " (kotoba-string h) ")")])
                            hosts)
        actual (run-probes probes)]
    (doseq [[i h] (map-indexed vector hosts)]
      (testing (str "host " (pr-str h))
        (is (= (boolean (policy/loopback-host? h))
               (boolean (get actual (str "h" i))))
            (str "policy.cljc and policy.kotoba disagree on " (pr-str h)))))))

(deftest loopback-host-is-case-sensitive
  ;; Pinned rather than assumed: `contains?` over a set of strings and
  ;; `string=?` are both exact, and neither side may quietly start folding case
  ;; without the other following.
  (is (false? (boolean (policy/loopback-host? "LOCALHOST"))))
  (is (true? (boolean (policy/loopback-host? "localhost")))))

;; ---------------------------------------------------------------------------
;; provider-allowed?

(defn- config-for [cloud-enabled? cloud-reviewed?]
  {:routing {:cloud-enabled? cloud-enabled?}
   :privacy {:allow-cloud-without-review? cloud-reviewed?}})

(def ^:private provider-cases
  "All sixteen combinations of [enabled local cloud-enabled cloud-reviewed]."
  (for [enabled [true false]
        local [true false]
        cloud-enabled [true false]
        cloud-reviewed [true false]]
    [enabled local cloud-enabled cloud-reviewed]))

(deftest provider-allowed-agrees-on-every-combination
  (let [probes (map-indexed
                (fn [i [e l ce cr]]
                  [(str "p" i)
                   (str "(provider-allowed? (record-new " provider-type " "
                        (kotoba-bool e) " " (kotoba-bool l) " "
                        (kotoba-bool ce) " " (kotoba-bool cr) "))")])
                provider-cases)
        actual (run-probes probes)]
    (is (= 16 (count provider-cases)))
    (doseq [[i [e l ce cr]] (map-indexed vector provider-cases)]
      (testing (str "enabled=" e " local=" l
                    " cloud-enabled=" ce " cloud-reviewed=" cr)
        (is (= (boolean (policy/provider-allowed? (config-for ce cr)
                                                  {:enabled? e :local? l}))
               (boolean (get actual (str "p" i))))
            "policy.cljc and policy.kotoba disagree")))))

(deftest cloud-needs-both-gates-and-local-never-escalates
  ;; The two properties the combination table exists to protect, stated
  ;; directly so a reader does not have to reconstruct them from 16 rows.
  (testing "one gate alone does not admit a cloud provider"
    (is (false? (boolean (policy/provider-allowed? (config-for true false)
                                                   {:enabled? true :local? false}))))
    (is (false? (boolean (policy/provider-allowed? (config-for false true)
                                                   {:enabled? true :local? false})))))
  (testing "a disabled local provider denies rather than escalating to cloud"
    (is (false? (boolean (policy/provider-allowed? (config-for true true)
                                                   {:enabled? false :local? true}))))))

;; ---------------------------------------------------------------------------
;; the core stays a core

(deftest decision-core-compiles-for-both-native-isas
  ;; This is the gate that keeps the file inside the native word-typed slice.
  ;; It is not a claim that the app RUNS natively — nothing here links or
  ;; executes a native artifact, and no capability kit is qualified for
  ;; `:native-aot` yet (all eight are `:pending`). It is the narrower and
  ;; checkable claim that the decision core is expressible there today.
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (let [out (compiler/compile-source core-source target {})]
        (is (some? (:kir out))
            (str "decision core no longer compiles for " (name target)
                 " — it has probably grown a map, a set literal or a closure,"
                 " none of which the native word-typed slice admits yet."
                 " `and`/`or` are fine: they desugar to nested let/if and were"
                 " measured compiling on both ISAs."))))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))
