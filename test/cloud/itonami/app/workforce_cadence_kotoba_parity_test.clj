(ns cloud.itonami.app.workforce-cadence-kotoba-parity-test
  "The self-adjusting cadence table, asserted through the shipped oracle.

  `kotoba-oracle-test` checks the artifact is current; this file owns the
  table itself and the four-target compiles that keep the core inside the
  native word-typed slice.

  The case that matters is 5: a run that could not execute must NOT be read as
  a run that looked and found nothing. They take different branches and reach
  different ceilings, because only one of them measured anything."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [kotoba.compiler.core :as compiler]))

(def ^:private core-path "src/cloud/itonami/app/workforce_cadence_core.kotoba")
(def ^:private core-source (delay (slurp core-path)))

(defn- next-interval [floor ceiling retry-ceiling current outcome]
  (oracle/call :workforce-cadence 'next-interval-minutes
               [floor ceiling retry-ceiling current outcome]))

(def ^:private produced-change 0)
(def ^:private no-op 1)
(def ^:private unavailable 2)

(deftest a-run-that-changed-something-returns-to-the-floor
  (testing "however far it had backed off"
    (is (= 15 (next-interval 15 1440 60 15 produced-change)))
    (is (= 15 (next-interval 15 1440 60 240 produced-change)))
    (is (= 15 (next-interval 15 1440 60 1440 produced-change)))))

(deftest a-no-op-doubles-up-to-the-long-ceiling
  (is (= 30 (next-interval 15 1440 60 15 no-op)))
  (is (= 60 (next-interval 15 1440 60 30 no-op)))
  (is (= 1440 (next-interval 15 1440 60 960 no-op))
      "doubling 960 would be 1920; the ceiling holds")
  (is (= 1440 (next-interval 15 1440 60 1440 no-op))
      "already at the ceiling and still finding nothing: stay, do not grow"))

(deftest a-run-that-never-executed-is-not-a-no-op
  (testing "an unavailable provider backs off only to the retry ceiling"
    (is (= 30 (next-interval 15 1440 60 15 unavailable)))
    (is (= 60 (next-interval 15 1440 60 45 unavailable))
        "doubling 45 would be 90; the RETRY ceiling holds, not the long one"))
  (testing "the two branches separate at the same current interval"
    (is (= 120 (next-interval 15 1440 60 60 no-op)))
    (is (= 60 (next-interval 15 1440 60 60 unavailable))
        "same input, different answer -- this is the whole point of the third code"))
  (testing "an outcome code this core does not know takes the retry branch"
    (is (= 60 (next-interval 15 1440 60 45 9))
        "unknown must not earn the long back-off that only `no-op` earns")
    (is (= 60 (next-interval 15 1440 60 45 -1)))))

(deftest degenerate-inputs-clamp-rather-than-refuse
  (testing "a schedule must still get a next time"
    (is (= 2 (next-interval 0 1440 60 0 no-op)) "floor below 1 becomes 1")
    (is (= 15 (next-interval 15 5 60 15 no-op))
        "a ceiling under the floor cannot push the interval below the floor")
    (is (= 15 (next-interval 15 1440 1 15 unavailable))
        "a retry ceiling under the floor cannot either")
    (is (= 15 (next-interval 15 1440 60 1 produced-change))
        "a current interval under the floor is raised before use")))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source @core-source target {})))
          (str "workforce-cadence core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure.")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source @core-source target {})))))))
