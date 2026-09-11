(ns cloud.itonami.app.pure-head-zeroarg-probe-test
  "Probe the ONE pure-head shape the shipped probe does not cover: a
  zero-argument `lam` applied in head position via `app` — the exact shape
  `route-scope` / `auxiliary-route`'s sentinel selection would use. The word-
  typed slice keeps everything scalar, so the question is purely whether
  `(app (lam [] ...))` compiles and runs on the pinned compiler."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private src
  "(ns pure-head-zeroarg
     (:export [pick]))
   (defn pick-a [] :i64 7)
   (defn pick-b [] :i64 9)
   (defn pick [flag :i64] :i64
     (app (lam [] (if (= flag 1) (pick-a) (pick-b)))))")

(deftest zero-arg-lam-in-head-applies-and-runs
  (let [{:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (is (some? kir) "zero-arg lam must compile for wasm32")
    (is (= 7 (ir/execute kir 'pick [1]))
        "winner = pick-a")
    (is (= 9 (ir/execute kir 'pick [0]))
        "otherwise = pick-b")))

(deftest zero-arg-lam-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source src target {})))
          (str "zero-arg lam must compile for " (name target))))))