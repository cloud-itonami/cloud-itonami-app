(ns cloud.itonami.app.pure-head-probe-test
  "The pinned compiler admits the pure S-expression heads on the word-typed
  slice.

  ## What is actually at risk

  The `lam` / `app` / `ref` heads lower in the compiler frontend only since
  kotoba-sema `fcd4e35` (2026-09-06, \"frontend: admit ref and perform...\"),
  which reached this repository when the `:test` override advanced the
  compiler from amu `1644b32` (kotoba-sema `1b83233`, predates the heads) to
  amu `78cdc2e` (kotoba-sema `3378b1d3`). Before that pin, a decision core
  written in the pure S-expr form did not compile; the migration
  documentation (`90-docs/kotoba-migration-progress.md`) records that all 21
  shipped `.kotoba` files were deliberately clojure-shaped precisely because
  the compiler could not yet admit these heads.

  This test pins that the claim now *executes*. `step` is `(app (ref inc1))`
  and `round-trip` is `(app (lam ...))` — `ref` names a top-level definition,
  which is the only way the grammar admits it (`:ambient-forbidden` on
  locals); `perform` is deliberately not used because it is the host-answered
  capability effect, which this probe has no business wiring. Both stay on
  the `:i64` word-typed slice, so the claim transfers to the native slice
  that the other decision cores self-restrict to.
  "
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/pure_head_probe.kotoba")

(def ^:private core-source (slurp core-path))

(deftest pure-head-probe-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "pure-head core no longer compiles for " (name target)
               " — the lam/app/ref heads were refused by the compiler "
               "frontend. The compiler override (deps.edn :test) must keep "
               "kotoba-sema at or past 3378b1d3.")))))

(deftest pure-head-probe-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (let [out (compiler/compile-source core-source target {})]
        (is (some? (:kir out))
            (str "pure-head core no longer compiles for " (name target)
                 "-- the word-typed slice rejected lam/app/ref"))))))

(deftest ref-app-executes
  ;; `step = (app (ref inc1))` must run, and must be the arithmetic it reads
  ;; like. 40 → 41. If `ref`/`app` produced a result but an incorrect one,
  ;; this catches it.
  (let [{:keys [kir]} (compiler/compile-source core-source :wasm32-kotoba-v1 {})]
    (is (= 41 (ir/execute kir 'step [40])))
    (is (= 2 (ir/execute kir 'step [1])))))

(deftest lam-app-executes
  ;; `round-trip = (app (lam [x] (+ x x)))`, the closure form `ref` cannot
  ;; express (STM: `ref` on a local is `:ambient-forbidden`). 21 → 42.
  (let [{:keys [kir]} (compiler/compile-source core-source :wasm32-kotoba-v1 {})]
    (is (= 42 (ir/execute kir 'round-trip [21])))))