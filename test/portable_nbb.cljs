(ns portable-nbb
  "The ClojureScript half of the portable test suite. Run by
  `bin/test-portable-cljs`.

  `oracle_cases_nbb.cljs` does this for the shipped decision cores. This does it
  for the `.cljc` namespaces beside them — the domain-name, mail-record and
  certificate judgements that were written into `.clj` files because that is
  where the DNS and the KeyStore were, and moved out because they never needed
  either.

  Without this runner the extension would be the only ClojureScript thing about
  them. A `.cljc` file that one runtime ever executes is a `.clj` file with a
  longer name, and this repository has already been bitten by exactly that: the
  JVM suite was green through the whole period the ClojureScript surface could
  not execute a single decision core."
  (:require [cljs.test :as t]
            [cloud.itonami.app.domain-name-test]
            [cloud.itonami.app.config-policy-test]
            [cloud.itonami.app.host-bounds-test]
            [cloud.itonami.app.issue-comment-test]
            [cloud.itonami.app.mail-authentication-test]
            [cloud.itonami.app.mail-domain-records-test]
            [cloud.itonami.app.provider-retry-test]
            [cloud.itonami.app.store-core-test]
            [cloud.itonami.app.tls-binding-test]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (println "\nportable cljc —" (:test m) "tests,"
           (+ (:pass m) (:fail m) (:error m)) "assertions,"
           (:fail m) "failures," (:error m) "errors, on ClojureScript")
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'cloud.itonami.app.domain-name-test
             'cloud.itonami.app.config-policy-test
             'cloud.itonami.app.host-bounds-test
             'cloud.itonami.app.issue-comment-test
             'cloud.itonami.app.mail-authentication-test
             'cloud.itonami.app.mail-domain-records-test
             'cloud.itonami.app.provider-retry-test
             'cloud.itonami.app.store-core-test
             'cloud.itonami.app.tls-binding-test)
