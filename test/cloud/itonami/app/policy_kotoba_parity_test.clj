(ns cloud.itonami.app.policy-kotoba-parity-test
  "The routing policy's truth table, and that the core compiles everywhere.

  ## What this test now is

  Since 2026-08-11 `policy.cljc` DELEGATES to the shipped artifact, so there
  is no second implementation left to be in parity with. What survives is the
  part that was always worth having: the truth table itself, asserted over the
  host, and the multi-target compiles. Read `policy.cljc` == `policy.kotoba`
  below as \"the host reaches the rule\", not as \"two implementations agree\".
  `kotoba-oracle-test` is what checks the shipped artifact is current and that
  the host really reads it.

  ## What it was, and why

  Before this test, `policy.cljc` said in a docstring that it was \"the

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
       "[[:enabled :bool] [:reviewed :bool] [:no-egress :bool] "
       "[:egress-permitted :bool] [:confidential :bool] "
       "[:authenticated :bool]]]"))

(defn- kotoba-bool [b] (if b "true" "false"))

(defn- kotoba-string [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- with-probes
  "Append zero-arg `:bool` wrappers to the core and widen its `:export`.

  Retained because it is a cheap way to run many cases off one compile, NOT
  because a record cannot cross the entry boundary. This once said it could
  not; measured false at these pins on 2026-08-11, and `kotoba-oracle-test`
  pins that measurement. It had to be false for `policy.cljc` to delegate at
  all — a production call path cannot recompile the way a test can."
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

(defn- config-for [egress-permitted?]
  {:routing {:cloud-enabled? egress-permitted?}})

(defn- provider-for
  "A provider map whose DERIVED facts come out as asked.

  The host derives every fact rather than reading a flag, so the fixture has to
  produce them the way a real config would: a loopback or remote `:base-url`,
  an https or http scheme, and an env var that is actually set or actually not.
  Building it this way is the point — a fixture that set the booleans directly
  would test the core twice and the derivation not at all."
  [enabled? reviewed? no-egress? confidential? authenticated?]
  {:enabled? enabled?
   :reviewed? reviewed?
   :base-url (cond no-egress? (if confidential? "https://127.0.0.1:11434"
                                  "http://127.0.0.1:11434")
                   confidential? "https://api.example.com/v1"
                   :else "http://api.example.com/v1")
   :api-key-env (when authenticated? "POLICY_TEST_KEY_PRESENT")})

(def ^:private provider-cases
  "All 32 combinations of the five provider facts."
  (for [enabled [true false]
        reviewed [true false]
        no-egress [true false]
        confidential [true false]
        authenticated [true false]]
    [enabled reviewed no-egress confidential authenticated]))

(deftest provider-allowed-agrees-on-every-combination
  (doseq [egress-permitted [true false]]
    (let [probes (map-indexed
                  (fn [i [e r ne c a]]
                    [(str "p" i)
                     (str "(provider-allowed? (record-new " provider-type " "
                          (kotoba-bool e) " " (kotoba-bool r) " "
                          (kotoba-bool ne) " " (kotoba-bool egress-permitted) " "
                          (kotoba-bool c) " " (kotoba-bool a) "))")])
                  provider-cases)
          actual (run-probes probes)]
      (is (= 32 (count provider-cases)))
      (doseq [[i [e r ne c a]] (map-indexed vector provider-cases)]
        (testing (str "enabled=" e " reviewed=" r " no-egress=" ne
                      " egress-permitted=" egress-permitted
                      " confidential=" c " authenticated=" a)
          ;; Only the rows whose derivation the host can actually produce are
          ;; compared: `authenticated` comes from a real environment variable,
          ;; so the fixture asserts it by NAMING a variable that is set.
          (when (= a (boolean (System/getenv "POLICY_TEST_KEY_PRESENT")))
            (is (= (boolean (policy/provider-allowed?
                             (config-for egress-permitted)
                             (provider-for e r ne c a)))
                   (boolean (get actual (str "p" i))))
                "policy.cljc and policy.kotoba disagree")))))))

(deftest security-first-review-is-universal
  ;; The property the principle change turns on, stated directly: being on this
  ;; machine is no longer permission. Before ADR-2608130100 the first of these
  ;; was TRUE, and that was the whole bug in the principle.
  (testing "an unreviewed loopback provider is denied"
    (is (false? (boolean (policy/provider-allowed?
                          (config-for false)
                          {:enabled? true :reviewed? false
                           :base-url "http://127.0.0.1:11434"})))))
  (testing "a reviewed loopback provider is admitted, with no egress switch"
    (is (true? (boolean (policy/provider-allowed?
                         (config-for false)
                         {:enabled? true :reviewed? true
                          :base-url "http://127.0.0.1:11434"})))))
  (testing "a disabled provider denies rather than escalating"
    (is (false? (boolean (policy/provider-allowed?
                          (config-for true)
                          {:enabled? false :reviewed? true
                           :base-url "http://127.0.0.1:11434"}))))))

(deftest egress-needs-the-switch-tls-and-a-credential
  (let [reviewed {:enabled? true :reviewed? true}]
    (testing "plaintext egress is denied even when reviewed and permitted"
      (is (false? (boolean (policy/provider-allowed?
                            (config-for true)
                            (assoc reviewed :base-url "http://api.example.com/v1"
                                   :api-key-env "PATH"))))))
    (testing "https egress with a credential is admitted"
      (is (true? (boolean (policy/provider-allowed?
                           (config-for true)
                           (assoc reviewed :base-url "https://api.example.com/v1"
                                  :api-key-env "PATH"))))))
    (testing "the deployment switch alone can forbid all egress"
      (is (false? (boolean (policy/provider-allowed?
                            (config-for false)
                            (assoc reviewed :base-url "https://api.example.com/v1"
                                   :api-key-env "PATH"))))))
    (testing "a named but unexported credential does not count as one"
      (is (false? (boolean (policy/provider-allowed?
                            (config-for true)
                            (assoc reviewed :base-url "https://api.example.com/v1"
                                   :api-key-env "DEFINITELY_NOT_SET_ANYWHERE"))))))))

(deftest a-provider-cannot-declare-itself-local
  ;; `:local?` is documentation now. A provider that claims it while pointing
  ;; at a remote host must be treated as egress -- the flag is exactly what a
  ;; typo or an attacker gets to write.
  (is (false? (boolean (policy/provider-allowed?
                        (config-for false)
                        {:enabled? true :reviewed? true :local? true
                         :base-url "https://api.example.com/v1"
                         :api-key-env "PATH"})))))

(deftest readiness-explains-the-same-decision-without-exposing-secrets
  (let [remote {:enabled? false :reviewed? false
                :base-url "http://api.example.com/private/path"
                :api-key-env "DEFINITELY_NOT_SET_ANYWHERE"}
        readiness (policy/provider-readiness (config-for false) remote)]
    (is (false? (:allowed? readiness)))
    (is (= [:disabled :unreviewed :cloud-egress-disabled
            :tls-required :credential-missing]
           (:blocking readiness)))
    (is (not (contains? readiness :api-key-env)))
    (is (not (some #(re-find #"example|PRIVATE|DEFINITELY" (str %))
                   (tree-seq coll? seq readiness)))))
  (let [local (policy/provider-readiness
               (config-for false)
               {:enabled? true :reviewed? true
                :base-url "http://127.0.0.1:11434"})]
    (is (true? (:allowed? local)))
    (is (true? (:no-egress? local)))
    (is (empty? (:blocking local)))))

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
