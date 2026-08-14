(ns cloud.itonami.app.kotoba-oracle-test
  "What keeps the shipped artifact honest, now that it is what runs.

  The three `*-kotoba-parity-test` namespaces compile the `.kotoba` fresh and
  compare it to the host. That was the whole check while the host had its own
  copy of each rule. It is not the whole check any more, because the host no
  longer computes anything — it reads
  `resources/cloud/itonami/app/oracle/*.kir.edn`, and a fresh compile is not
  that file. Two things have to hold that did not have to before:

    1. the shipped artifact IS the current source, compiled
    2. the host actually reads it, rather than having quietly kept a copy

  The second is the one that is easy to lose and impossible to see: a
  delegation that fell back to a host implementation would pass every parity
  test ever written, because a host copy is exactly what those tests compare
  against."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.kotoba-oracle-gen :as gen]
            [cloud.itonami.app.health :as health]
            [cloud.itonami.app.oauth-resource :as oauth-resource]
            [cloud.itonami.app.policy :as policy]
            [kotoba.compiler.core :as compiler]))

(deftest the-shipped-artifact-is-the-current-source-compiled
  (doseq [[id source] (sort-by key oracle/cores)]
    (testing (str id " <- " source)
      (let [shipped (edn/read-string (slurp (io/resource (oracle/resource-path id))))
            fresh (:kir (compiler/compile-source (slurp (io/file "src" source))
                                                 gen/target {}))]
        (is (= fresh shipped)
            (str "shipped KIR for " id " is stale — run `clojure -M:test:gen`"))))))

(deftest every-declared-core-actually-ships
  (doseq [id (keys oracle/cores)]
    (is (some? (io/resource (oracle/resource-path id)))
        (str "no artifact for " id))
    (is (some? (oracle/kir id)))))

(deftest a-missing-artifact-throws-rather-than-deciding-anything
  ;; The seam's one refusal. If it fell back instead, the first thing anyone
  ;; would notice is that a decision quietly stopped being the shipped one.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shipped decision core is missing"
                        (oracle/kir :not-a-core))))

(deftest the-host-reads-the-artifact-rather-than-keeping-a-copy
  ;; Swap in a core that answers the OPPOSITE, and require the host to follow.
  ;; A host that had kept its own `contains?` over the three loopback spellings
  ;; would not, and nothing else in this repository would say so.
  (let [inverted (:kir (compiler/compile-source
                        (str "(ns cloud.itonami.app.policy"
                             "  (:export [loopback-host?]))"
                             "(defn loopback-host? [host :string] :bool"
                             "  (if (string=? host \"127.0.0.1\") false true))")
                        gen/target {}))]
    (is (true? (policy/loopback-host? "127.0.0.1")) "the shipped answer")
    (try
      (oracle/register-kir! :policy inverted)
      (is (false? (policy/loopback-host? "127.0.0.1"))
          "the host followed the artifact")
      (is (true? (policy/loopback-host? "example.com"))
          "and followed it in both directions")
      (finally (oracle/deregister-kir! :policy)))
    (is (true? (policy/loopback-host? "127.0.0.1")) "restored")))

(deftest the-health-host-reads-the-artifact-rather-than-keeping-a-copy
  ;; Same shape as the policy invert above. The HTTP handler is a separate
  ;; check (`health-http-test`): this one is the host half, so a copy of the
  ;; two equals inside `health.cljc` would fail here before a request ran.
  (let [inverted (:kir (compiler/compile-source
                        (str "(ns cloud.itonami.app.health"
                             "  (:export [health-route?]))"
                             "(defn health-route? [method :string path :string] :bool"
                             "  (if (and (string=? method \"GET\") (string=? path \"/health\"))"
                             "    false true))")
                        gen/target {}))]
    (is (true? (health/health-route? "GET" "/health")) "the shipped answer")
    (try
      (oracle/register-kir! :health inverted)
      (is (false? (health/health-route? "GET" "/health"))
          "the host followed the artifact")
      (is (true? (health/health-route? "POST" "/health"))
          "and followed it in both directions")
      (finally (oracle/deregister-kir! :health)))
    (is (true? (health/health-route? "GET" "/health")) "restored")))

(deftest the-oauth-resource-host-reads-the-artifact-rather-than-keeping-a-copy
  (let [path "/.well-known/oauth-protected-resource/mcp"
        inverted (:kir (compiler/compile-source
                        (str "(ns cloud.itonami.app.oauth-resource"
                             "  (:export [oauth-resource-route?]))"
                             "(defn oauth-resource-route? [method :string path :string] :bool"
                             "  (if (and (string=? method \"GET\") (string=? path \"" path "\"))"
                             "    false true))")
                        gen/target {}))]
    (is (true? (oauth-resource/oauth-resource-route? "GET" path)) "the shipped answer")
    (try
      (oracle/register-kir! :oauth-resource inverted)
      (is (false? (oauth-resource/oauth-resource-route? "GET" path))
          "the host followed the artifact")
      (is (true? (oauth-resource/oauth-resource-route? "POST" path))
          "and followed it in both directions")
      (finally (oracle/deregister-kir! :oauth-resource)))
    (is (true? (oauth-resource/oauth-resource-route? "GET" path)) "restored")))

(deftest a-record-crosses-the-entry-boundary
  ;; `policy-kotoba-parity-test` used to say it could not, and built zero-arg
  ;; probe wrappers around every case because of it. Measured false at these
  ;; pins on 2026-08-11 — and it has to be false, because the production call
  ;; path cannot recompile the way a test can. Pinned here so that if a pin
  ;; advance ever makes it true again, this says so before the application
  ;; finds out.
  ;; Inputs follow the security-first shape (ADR-2608130100); what this pins is
  ;; unchanged — a record reaching the guest through the production seam, with
  ;; two rows that differ only in a field carried INSIDE that record.
  (is (false? (policy/provider-allowed?
               {:routing {:cloud-enabled? true}}
               {:enabled? true :reviewed? false
                :base-url "https://cloud.example.com/v1" :api-key-env "PATH"})))
  (is (true? (policy/provider-allowed?
              {:routing {:cloud-enabled? true}}
              {:enabled? true :reviewed? true
               :base-url "https://cloud.example.com/v1" :api-key-env "PATH"}))))
