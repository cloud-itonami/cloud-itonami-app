(ns cloud.itonami.app.test-runner
  "The suite is an explicit list, and an explicit list can be incomplete
  silently — a new `*_test.clj` that nobody adds here does not fail, it simply
  never runs, and the build stays green while the tests in it never execute.
  (Measured: `storj_test.clj` was added and the count did not move.) So the
  list stays explicit, and `-main` refuses to run a suite that does not cover
  every test file on the path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [cloud.itonami.app.authority-test]
            [cloud.itonami.app.business-test]
            [cloud.itonami.app.agent-session-test]
            [cloud.itonami.app.business-http-test]
            [cloud.itonami.app.canvas-test]
            [cloud.itonami.app.loops-test]
            [cloud.itonami.app.lawfirm-test]
            [cloud.itonami.app.fax-test]
            [cloud.itonami.app.metrics-test]
            [cloud.itonami.app.portfolio-test]
            [cloud.itonami.app.repos-test]
            [cloud.itonami.app.mailbox-test]
            [cloud.itonami.app.mailbox-http-test]
            [cloud.itonami.app.scheduler-test]
            [cloud.itonami.app.scheduler-http-test]
            [cloud.itonami.app.authority.adapters-test]
            [cloud.itonami.app.authority.api-test]
            [cloud.itonami.app.authority.payment-test]
            [cloud.itonami.app.authority.posture-test]
            [cloud.itonami.app.authority-http-test]
            [cloud.itonami.app.authority.transport-test]
            [cloud.itonami.app.config-test]
            [cloud.itonami.app.contracts-test]
            [cloud.itonami.app.core-test]
            [cloud.itonami.app.credential-http-test]
            [cloud.itonami.app.credential-rdf-test]
            [cloud.itonami.app.presentation-request-test]
            [cloud.itonami.app.credential-sd-jwt-test]
            [cloud.itonami.app.credential-test]
            [cloud.itonami.app.credential-trust-test]
            [cloud.itonami.app.credential-assurance-test]
            [cloud.itonami.app.data-isolation-test]
            [cloud.itonami.app.did-test]
            [cloud.itonami.app.email-login-test]
            [cloud.itonami.app.documents-test]
            [cloud.itonami.app.workspace-reachability-test]
            [cloud.itonami.app.web-script-test]
            [cloud.itonami.app.esign-test]
            [cloud.itonami.app.esign-retention-test]
            [cloud.itonami.app.filecoin-test]
            [cloud.itonami.app.fleet-test]
            [cloud.itonami.app.operator-test]
            [cloud.itonami.app.funding-test]
            [cloud.itonami.app.mcp-test]
            [cloud.itonami.app.namespaces-test]
            [cloud.itonami.app.fleet-tools-test]
            [cloud.itonami.app.openai-compat-test]
            [cloud.itonami.app.passkey-test]
            [cloud.itonami.app.payment-settlement-actor-test]
            [cloud.itonami.app.payment-tools-test]
            [cloud.itonami.app.paypay-bank-test]
            [cloud.itonami.app.storj-node-test]
            [cloud.itonami.app.store-test]
            [cloud.itonami.app.storj-test]
            [cloud.itonami.app.worker-http-test]))

(def ^:private namespaces
  '[cloud.itonami.app.authority-test
    cloud.itonami.app.business-test
    cloud.itonami.app.agent-session-test
    cloud.itonami.app.business-http-test
    cloud.itonami.app.canvas-test
    cloud.itonami.app.loops-test
    cloud.itonami.app.lawfirm-test
    cloud.itonami.app.fax-test
    cloud.itonami.app.metrics-test
    cloud.itonami.app.portfolio-test
    cloud.itonami.app.repos-test
    cloud.itonami.app.authority.adapters-test
    cloud.itonami.app.authority.api-test
    cloud.itonami.app.authority.payment-test
    cloud.itonami.app.authority.posture-test
    cloud.itonami.app.authority-http-test
    cloud.itonami.app.authority.transport-test
    cloud.itonami.app.config-test
    cloud.itonami.app.contracts-test
    cloud.itonami.app.core-test
    cloud.itonami.app.credential-http-test
    cloud.itonami.app.credential-rdf-test
    cloud.itonami.app.presentation-request-test
    cloud.itonami.app.credential-sd-jwt-test
    cloud.itonami.app.credential-test
    cloud.itonami.app.credential-trust-test
    cloud.itonami.app.credential-assurance-test
    cloud.itonami.app.data-isolation-test
    cloud.itonami.app.did-test
    cloud.itonami.app.email-login-test
    cloud.itonami.app.documents-test
    cloud.itonami.app.workspace-reachability-test
    cloud.itonami.app.web-script-test
    cloud.itonami.app.esign-test
    cloud.itonami.app.esign-retention-test
    cloud.itonami.app.filecoin-test
    cloud.itonami.app.fleet-test
    cloud.itonami.app.operator-test
    cloud.itonami.app.funding-test
    cloud.itonami.app.mcp-test
    cloud.itonami.app.namespaces-test
    cloud.itonami.app.fleet-tools-test
    cloud.itonami.app.openai-compat-test
    cloud.itonami.app.passkey-test
    cloud.itonami.app.payment-settlement-actor-test
    cloud.itonami.app.mailbox-test
    cloud.itonami.app.mailbox-http-test
    cloud.itonami.app.scheduler-test
    cloud.itonami.app.scheduler-http-test
    cloud.itonami.app.payment-tools-test
    cloud.itonami.app.paypay-bank-test
    cloud.itonami.app.storj-node-test
    cloud.itonami.app.store-test
    cloud.itonami.app.storj-test
    cloud.itonami.app.worker-http-test])

(defn- test-namespaces-on-disk
  "Every `*_test.clj` under test/, as the namespace symbol it declares."
  []
  (->> (file-seq (io/file "test"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % "_test.clj"))
       (map #(-> %
                 (str/replace #"^test/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "_" "-")
                 (str/replace "/" ".")
                 symbol))
       set))

(defn- unlisted []
  (let [on-disk (test-namespaces-on-disk)]
    ;; only meaningful when run from the project root, where test/ exists
    (when (seq on-disk)
      (sort (remove (set namespaces) on-disk)))))

(defn -main [& _]
  (when-let [missing (seq (unlisted))]
    (println "test-runner: these test namespaces exist but are not in the list:")
    (doseq [n missing] (println "  " n))
    (println "Add them to cloud.itonami.app.test-runner, or they do not run.")
    (System/exit 1))
  (let [{:keys [fail error]} (apply test/run-tests namespaces)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
