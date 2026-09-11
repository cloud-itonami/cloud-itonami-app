(ns cloud.itonami.app.domain-binding-kotoba-parity-test
  "The domain-binding truth table through the host seam, and that the core
  compiles everywhere (ADR-0043).

  `oracle-cases` already runs this core's exports on both runtimes. What this
  file owns is the part the case table cannot express: that the host half turns
  an `:i64` back into the state keyword the store writes, that it refuses a fact
  map with a hole in it rather than crossing `false` for the missing entry, and
  the four-target compiles that keep the core inside the native word-typed
  slice."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.domain-binding :as binding]
            [kotoba.compiler.core :as compiler]))

(def ^:private core-path "src/cloud/itonami/app/domain_binding_core.kotoba")

(def ^:private core-source (delay (slurp core-path)))

(def ^:private claimed
  {:owner-authorized true :txt-observed true :claim-exclusive true
   :probe-answered false :probe-confidential false :probe-fresh false
   :name-is-service-owned false :previously-live false})

(def ^:private live
  (assoc claimed :probe-answered true :probe-confidential true :probe-fresh true))

(deftest the-host-reads-back-a-state-and-not-a-number
  (testing "each i64 the core can return has a name on this side"
    (is (= {0 :pending 1 :claimed 2 :live 3 :lapsed} @binding/states)))
  (is (= :pending (binding/binding-state (assoc claimed :txt-observed false))))
  (is (= :claimed (binding/binding-state claimed)))
  (is (= :live (binding/binding-state live)))
  (is (= :lapsed (binding/binding-state (assoc claimed :previously-live true)))))

(deftest a-proven-claim-is-not-a-live-name
  (testing "the naming right alone never names the tenant"
    (is (true? (binding/claim-holds? claimed)))
    (is (false? (binding/name-holds? claimed))))
  (testing "and the resolution fact alone never does either"
    (let [answering-but-unproven (assoc live :txt-observed false)]
      (is (false? (binding/claim-holds? answering-but-unproven)))
      (is (false? (binding/name-holds? answering-but-unproven))))))

(deftest a-lapse-is-reversible-and-outranks-the-claim-it-still-holds
  ;; Both directions of the one ordering in the core. A binding that was live
  ;; keeps its TXT record, so `:claimed` is a TRUE description of it and the
  ;; wrong answer — it would leave the tenant named by an address that stopped
  ;; answering. And a lapse must not be permanent, or a tenant could never
  ;; repoint DNS back.
  (is (= :lapsed (binding/binding-state (assoc claimed :previously-live true))))
  (is (= :live (binding/binding-state (assoc live :previously-live true)))))

(deftest an-incomplete-fact-map-is-refused-rather-than-defaulted
  ;; A missing key crossing as `false` is the failure this guard exists for:
  ;; `claim-exclusive false` reads as "another tenant holds this name" and
  ;; `previously-live false` silently turns a lapse into a claim. Both are
  ;; answers, and neither is the one the caller failed to establish.
  (doseq [k binding/fact-keys]
    (testing (str "missing " k)
      (let [thrown (try (binding/binding-state (dissoc live k))
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :domain-binding/incomplete-facts (:type thrown)))
        (is (= [k] (:missing thrown)))))))

(deftest a-service-owned-name-is-refused-at-every-gate
  ;; The guard this replaced was the literal `"itonami.cloud"` while the suffix
  ;; this deployment actually issues shipped as `cloud-itonami.app` — so it
  ;; protected a name the deployment does not hand out and left the ones it does
  ;; unprotected. Whether a name is the deployment's own is an argument now.
  (let [own (assoc live :name-is-service-owned true)]
    (is (false? (binding/may-start? own)))
    (is (false? (binding/claim-holds? own)))
    (is (false? (binding/name-holds? own)))
    (is (= :lapsed (binding/binding-state (assoc own :previously-live true)))
        "a name that becomes service-owned is taken away, not left live")))

(deftest starting-a-challenge-needs-an-owner-and-an-unclaimed-name
  (is (true? (binding/may-start? claimed)))
  (is (false? (binding/may-start? (assoc claimed :owner-authorized false))))
  (is (false? (binding/may-start? (assoc claimed :claim-exclusive false)))
      "being told to publish a record that could never count is not a refusal"))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (let [out (compiler/compile-source @core-source target {})]
        (is (some? (:kir out))
            (str "domain-binding core no longer compiles for " (name target)
                 " — it has probably grown a map, a set literal or a closure."))))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source @core-source target {})))))))
