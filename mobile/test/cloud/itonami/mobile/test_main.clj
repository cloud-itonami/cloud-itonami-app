(ns cloud.itonami.mobile.test-main
  "Runs the mobile view suite and exits non-zero on failure.

  A test runner that exits 0 on failure is the same defect the suite itself is
  about, one level up."
  (:require [clojure.test :as t]
            [cloud.itonami.mobile.view-test]))

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (t/run-tests 'cloud.itonami.mobile.view-test)]
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
