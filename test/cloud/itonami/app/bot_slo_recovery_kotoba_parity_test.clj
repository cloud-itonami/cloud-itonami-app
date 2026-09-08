(ns cloud.itonami.app.bot-slo-recovery-kotoba-parity-test
  "The stale-run recovery ladder, in .kotoba and through the host.

  `bot_slo.recovery-points` awards a partition 0–15 stability points from how
  many stale (never-completed) runs it holds. This test pins that the .kotoba
  spelling and the host's private `recovery-points` agree, including the
  bucket boundaries (0/1/2/3) and a negative count — which a `case` spells
  differently from an `if` ladder.

  The host function is private; we reach it through its var so the .kotoba is
  compared against the *actual* rule, not a copy."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.bot-slo :as slo]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private ksrc
  (slurp "src/cloud/itonami/app/bot_slo_recovery_core.kotoba"))

(defn- guest [stale]
  (let [src (-> ksrc
                (str/replace #"\(:export \[[^\]]+\]\)"
                             "(:export [recovery-points __probe])")
                (str "\n(defn __probe [] :f64 (recovery-points " stale "))\n"))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (ir/execute kir '__probe [])))

(defn- host [stale]
  (#'cloud.itonami.app.bot-slo/recovery-points stale))

(deftest kotoba-and-host-agree-on-the-whole-ladder
  (doseq [stale [-1 0 1 2 3 4 5 100]]
    (is (= (host stale) (guest stale))
        (str "recovery-points disagreed at stale=" stale))))

(deftest bucket-boundaries-are-exact
  (is (= 15.0 (guest 0)))
  (is (= 11.0 (guest 1)))
  (is (= 8.0 (guest 2)))
  (is (= 5.0 (guest 3)))
  (is (= 5.0 (guest 100))))