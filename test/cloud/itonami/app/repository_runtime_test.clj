(ns cloud.itonami.app.repository-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.repository-runtime :as runtime]))

(deftest preflight-fails-closed-without-exposing-environment-values
  (binding [runtime/*environment* (constantly nil)]
    (let [result (runtime/preflight!)
          rendered (pr-str result)]
      (is (false? (:qualified? result)))
      (is (contains? (set (:missing result)) :storage-owner))
      (is (contains? (set (:missing result)) :kotobase-token))
      (is (every? #(contains? % :ready?) (:checks result)))
      (is (not (.contains rendered "CLOUD_ITONAMI_KOTOBASE_TOKEN"))))))
