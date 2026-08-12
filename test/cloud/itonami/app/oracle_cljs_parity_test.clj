(ns cloud.itonami.app.oracle-cljs-parity-test
  "The JVM half of the two-runtime gate over `oracle-cases`.

  The ClojureScript half is `test/oracle_cases_nbb.cljs`, run by
  `bin/test-oracle-cljs`. Both halves execute the SAME table, so a case cannot
  be answered on one runtime and quietly skipped on the other, and neither can
  drift into testing a different thing than the other one does.

  This namespace is not where the interesting failure is — the JVM was green
  through the entire period the ClojureScript surface was broken, which is
  exactly the point. It is here so that the table has a home on the runtime
  that CI already runs, and so `uncovered` is enforced even when nobody runs
  the nbb half."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.oracle-cases :as cases]))

(deftest every-shipped-export-has-a-case
  (testing "a new (:export …) cannot land unexercised"
    (is (= [] (cases/uncovered))
        "these exports ship with no case in oracle-cases/cases")))

(deftest the-table-is-not-empty
  ;; A filter bug that emptied `cases` would make every other assertion here
  ;; vacuously true, including the mutation check the fix was proven with.
  (is (<= 70 (count cases/cases))
      "the case table shrank — a gate that exercises nothing passes everything"))

(deftest shipped-cores-answer-on-the-jvm
  (doseq [{:keys [oracle export] :as c} cases/cases]
    (let [{:keys [ok? actual]} (cases/run-case c)]
      (is ok? (str oracle "/" export " gave " (pr-str actual)
                   ", expected " (pr-str (:expect c)))))))
