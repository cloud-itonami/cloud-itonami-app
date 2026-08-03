(ns cloud.itonami.app.repository-invariants-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.repository-invariants :as invariants]))

(deftest current-build-proves-all-source-local-invariants
  (let [result (invariants/verify)]
    (is (:qualified? result) (pr-str result))
    (is (every? true? (vals (dissoc result :qualified?))))))
