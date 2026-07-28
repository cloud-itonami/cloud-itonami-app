(ns cloud.itonami.app.test-runner
  (:require [clojure.test :as test]
            [cloud.itonami.app.core-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'cloud.itonami.app.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
