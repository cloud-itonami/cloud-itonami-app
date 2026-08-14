(ns cloud.itonami.app.health-kotoba-parity-test
  "The health route's truth table, and that the core compiles everywhere.

  Since `health.cljc` delegates, there is no second implementation to be in
  parity with. What this file owns is the table itself, asserted over the
  host, and the four-target compiles that keep the core inside the native
  word-typed slice. `kotoba-oracle-test` checks the shipped artifact is
  current. `health-http-test` checks the handler actually reads it."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.health :as health]
            [kotoba.compiler.core :as compiler]))

(def ^:private core-path "src/cloud/itonami/app/health_core.kotoba")

(def ^:private core-source (delay (slurp core-path)))

(deftest the-host-admits-only-get-health
  (is (true? (health/health-route? "GET" "/health")))
  (is (false? (health/health-route? "POST" "/health")))
  (is (false? (health/health-route? "GET" "/")))
  (is (false? (health/health-route? "GET" "/healthz")))
  (is (false? (health/health-route? "get" "/health"))
      "the wire method is uppercase; a downcased spelling is a different request"))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (let [out (compiler/compile-source @core-source target {})]
        (is (some? (:kir out))
            (str "health core no longer compiles for " (name target)
                 " — it has probably grown a map, a set literal or a closure."))))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source @core-source target {})))))))
