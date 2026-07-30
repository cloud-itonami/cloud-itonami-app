(ns cloud.itonami.app.test-runner
  (:require [clojure.test :as test]
            [cloud.itonami.app.core-test]
            [cloud.itonami.app.fleet-test]))

(def ^:private namespaces
  '[cloud.itonami.app.core-test
    cloud.itonami.app.fleet-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply test/run-tests namespaces)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
