(ns cloud.itonami.app.authority.transport-test
  "How this app reads an actor's answer, driven against a real socket.

  `interpret` is private, and testing it directly would test a function rather than
  the contract: what matters is that a JSON body an actor really sends over a real
  connection lands as the right spine outcome. So these stand up a stub actor.

  The case that motivated the file: cloud-itonami-card-issuing can perform a real
  outward act, so it answers `approved-not-actuated` when its operator approved and
  Stripe declined. Before this was understood here, that fell through to
  :transport-failed -- telling a human the actor was unreachable when the truth was
  that their own operator had approved and the provider said no."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority.transport :as transport])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn- stub-actor
  "An actor that answers every request with `payload`. Returns [endpoint stop!]."
  [payload]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (let [body (.getBytes (json/write-str payload) StandardCharsets/UTF_8)]
           (.set (.getResponseHeaders ^HttpExchange exchange)
                 "Content-Type" "application/json")
           (.sendResponseHeaders ^HttpExchange exchange 200 (alength body))
           (with-open [out (.getResponseBody ^HttpExchange exchange)]
             (.write out body))
           (.close ^HttpExchange exchange)))))
    (.setExecutor server nil)
    (.start server)
    [(str "http://127.0.0.1:" (.getPort (.getAddress server)))
     #(.stop server 0)]))

(defn- commit-against
  "Commit a proposal to a stub actor answering `payload`, and return the outcome."
  [authority-key payload]
  (let [[endpoint stop!] (stub-actor payload)]
    (try
      ((transport/commit-fn authority-key)
       {:authorities {authority-key {:enabled? true :endpoint endpoint}}}
       {} {:id "p-1" :authority (name authority-key) :op "card/issue"})
      (finally (stop!)))))

;; ---------------------------------------------------------------------------

(deftest a-committed-answer-is-ok-and-carries-the-record
  (let [out (commit-against :card {:status "committed" :record {:reference "r-1"}})]
    (is (true? (:authority/ok? out)))
    (is (= {:reference "r-1"} (:authority/record out)))))

(deftest a-held-answer-keeps-the-governors-own-rule
  (let [out (commit-against :card {:status "held"
                                   :refusal {:rule "kyc-incomplete"}})]
    (is (false? (:authority/ok? out)))
    (is (= "kyc-incomplete" (get-in out [:authority/refusal :rule]))
        "the actor's rule is preserved, not replaced with a generic one")))

(deftest a-pending-answer-is-neither-ok-nor-refused
  (let [out (commit-against :card {:status "pending" :reference "r-2"})]
    (is (false? (:authority/ok? out)))
    (is (true? (:authority/pending? out)))
    (is (= "r-2" (:authority/reference out)))
    (is (nil? (:authority/refusal out))
        "a proposal awaiting the actor's operator has not been refused by anyone")))

(deftest approved-but-not-actuated-is-not-a-transport-failure
  (testing "the operator approved and Stripe declined -- a human who read
            :transport-failed would go ask why the actor was down, when the answer
            is with their card provider"
    (let [out (commit-against
               :card {:status "approved-not-actuated"
                      :approval-recorded true
                      :decided-by "operator@example"
                      :refusal {:rule "stripe-error" :code "card_declined"}})]
      (is (false? (:authority/ok? out)) "no card exists")
      (is (not (:authority/pending? out)) "nobody is deciding")
      (is (not= :transport-failed (get-in out [:authority/refusal :rule])))
      (is (= "stripe-error" (get-in out [:authority/refusal :rule]))
          "the provider's own rule survives")
      (testing "and the approval that really happened is carried, not dropped"
        (is (true? (:authority/approval-recorded? out)))
        (is (true? (get-in out [:authority/refusal :approval-recorded])))
        (is (= "operator@example" (get-in out [:authority/refusal :decided-by])))))))

(deftest approved-but-not-actuated-with-no-refusal-still-names-a-rule
  (testing "an actor that reports the state without a reason must not produce a
            refusal with a nil rule"
    (let [out (commit-against :card {:status "approved-not-actuated"})]
      (is (= :actuation-failed (get-in out [:authority/refusal :rule])))
      (is (true? (:authority/approval-recorded? out))))))

(deftest an-unknown-reference-is-not-recorded-as-a-governor-refusal
  (let [out (commit-against :card {:status "unknown" :detail "no record"})]
    (is (true? (:authority/unknown? out)))
    (is (= :reference-unknown (get-in out [:authority/refusal :rule])))))

(deftest an-unrecognised-status-is-a-transport-failure-that-names-what-it-got
  (testing "fail closed on a word this app does not know, rather than guessing
            which of the four it resembles"
    (let [out (commit-against :card {:status "issued"})]
      (is (false? (:authority/ok? out)))
      (is (= :transport-failed (get-in out [:authority/refusal :rule])))
      (is (re-find #"issued" (str (get-in out [:authority/refusal :detail])))))))

(deftest denwabans-g7-refusal-arrives-as-a-refusal-and-not-as-unreachable
  (testing "being told no is different from not being able to ask -- which is what
            the voice authority answered while denwaban had no surface at all"
    (let [out (commit-against :voice {:status "held"
                                      :refusal {:rule "g7-outward-gate"
                                                :gate "G7"}})]
      (is (false? (:authority/ok? out)))
      (is (= "g7-outward-gate" (get-in out [:authority/refusal :rule])))
      (is (not= :endpoint-not-configured (get-in out [:authority/refusal :rule]))))))
