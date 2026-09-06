(ns cloud.itonami.mobile.terminal-test
  "What a typed line means, asserted on the JVM.

  `terminal/plan` is pure, so every outcome is reachable here without a device.
  What this file is for is the distinction the phone screen is built around:
  a command this client refused, a command that does not exist, and a command
  that exists and is withheld are three different facts, and a suite that only
  asserted 'it did not produce a request' would pass for all three while the
  screen showed the wrong sentence for two of them.

  Each assertion therefore pins the REASON and not only the shape. ADR-2608136000
  §6: a negative test that asserts only the result counts an execution that
  failed for another cause as a discrimination it did not make."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.commands :as commands]
            [cloud.itonami.mobile.terminal :as terminal]))

;; The JVM reads the tables off the classpath, which `../resources` puts there.
;; Installing them explicitly anyway keeps this suite honest about the fact
;; that the BUNDLE has no classpath and is handed the same text -- if the
;; resources ever stop resolving here, this fails loudly instead of the suite
;; quietly testing a registry that came from somewhere else.
(use-fixtures :once
  (fn [f]
    (commands/install-sources!
     {commands/resource-name (slurp (io/resource commands/resource-name))
      commands/alias-resource-name (slurp (io/resource commands/alias-resource-name))})
    (f)))

(deftest tables-are-actually-loaded
  ;; The floor. Every assertion below is meaningless if the registry is empty,
  ;; and an empty registry would make every command "unknown" -- which is a
  ;; PASS for two of the tests below if they only checked the outcome.
  (is (pos? (count (commands/all)))
      "command registry is empty; the rest of this suite would test nothing")
  (is (pos? (count (commands/alias-commands)))
      "alias table is empty"))

(deftest a-blank-line-is-not-a-command
  (is (= :empty (:outcome (terminal/plan ""))))
  (is (= :empty (:outcome (terminal/plan "   ")))))

(deftest a-read-becomes-a-request
  (let [{:keys [outcome request label]} (terminal/plan "bots list")]
    (is (= :request outcome))
    (is (= :get (:method request)))
    (is (= "/api/agent-bots" (:path request)))
    (is (= "bots list" label))))

(deftest quotes-survive-a-phone-keyboard
  ;; A brief is a sentence, and a phone is where someone types one. Without
  ;; quote handling the text becomes three argv words and the command means
  ;; something else.
  (is (= ["bots" "task" "--text" "進捗を 教えて"]
         (terminal/split-line "bots task --text \"進捗を 教えて\"")))
  (testing "an apostrophe is not a delimiter"
    (is (= ["echo" "don't"] (terminal/split-line "echo don't"))))
  (testing "an unterminated quote yields the words, not an error"
    (is (= ["bots" "task" "--text" "半分"]
           (terminal/split-line "bots task --text \"半分")))))

(deftest a-missing-flag-is-a-refusal-this-client-made
  ;; `bots task` needs --id. The server is never asked, so this must not reach
  ;; the screen as a failure to connect.
  (let [{:keys [outcome reason message]} (terminal/plan "bots task --text hi")]
    (is (= :refused outcome))
    ;; The UPSTREAM literal, not a local translation of it. `bots task` is an
    ;; alias, and the alias path refuses with :commands/missing-argument; the
    ;; registry path uses :commands/missing-parameter. Pinning the literal is
    ;; what makes a rename upstream break here instead of silently changing
    ;; which sentence the phone shows.
    (is (= :commands/missing-argument reason)
        "the reason is pinned: a refusal for any other cause is a different bug")
    (is (re-find #"--id" (str message))
        "the refusal names the flag it wants")))

(deftest the-registry-path-refuses-with-its-own-literal
  ;; The other half of the pair above. Two code paths, two names, and a suite
  ;; that only saw one of them would not notice the other regressing.
  (let [{:keys [outcome reason]} (terminal/plan "esign envelopes show")]
    (is (= :refused outcome))
    (is (= :commands/missing-parameter reason))))

(deftest a-command-that-does-not-exist-is-unavailable-not-refused
  (let [{:keys [outcome reason]} (terminal/plan "definitely-not-a-command")]
    (is (= :unavailable outcome))
    (is (= :no-such-command reason))))

(deftest a-withheld-write-says-it-was-withheld-here
  ;; `bots decide` exists, resolves, and builds. This surface does not offer it,
  ;; and the distinction from `:no-such-command` is the whole point: one means
  ;; the command is not real, the other means it is real and this screen will
  ;; not issue it. A 404 from the ingress would say the first about the second.
  (let [{:keys [outcome reason]}
        (terminal/plan "bots decide --id b --card c --decision approve")]
    (is (= :unavailable outcome))
    (is (= :not-offered-on-this-surface reason)
        "withheld-here and no-such-command must not collapse")))

(deftest the-withheld-set-is-not-empty-and-not-everything
  ;; A floor on the policy itself. `offered-writes` growing to contain every
  ;; template, or `offered-methods` growing to contain :post, would make the
  ;; test above pass for a reason that no longer holds -- so state the shape.
  (is (= #{:get} terminal/offered-methods)
      "widening the offered methods is a decision that belongs in a diff")
  (is (contains? terminal/offered-writes "/api/agent-bots/{id}/messages")
      "the conversation itself must be offered or the surface is read-only")
  (is (not (contains? terminal/offered-writes "/api/agent-bots/{id}/cards/{card}/decide"))
      "approval must not be offered to a device that presented no Passkey"))

(deftest the-transcript-is-bounded-and-drops-the-oldest
  (let [full (reduce (fn [t i] (terminal/append t (terminal/entry :sent (str i))))
                     []
                     (range (+ terminal/max-entries 5)))]
    (is (= terminal/max-entries (count full)))
    (is (= "5" (:text (first full))) "the oldest are dropped, not the newest")
    (is (= (str (dec (+ terminal/max-entries 5))) (:text (last full))))))

;; ---------------------------------------------------------------------------
;; the two gates must not drift apart
;; ---------------------------------------------------------------------------

(def ^:private ingress-source "../services/agent-edge/src/index.js")

(defn- ingress-writes
  "The write templates the ingress Worker carries, read out of its own table.

  Parsed from the source rather than duplicated here: a second copy of the list
  is a third gate that can drift from both of the first two."
  []
  (->> (re-seq (re-pattern "\\[\"POST\", \"([^\"]+)\"\\]")
               (slurp ingress-source))
       (map second)
       set))

(deftest the-client-and-the-ingress-offer-the-same-writes
  ;; Two enforcement points, one rule. They are deliberately separate -- the
  ;; ingress is the authority and answers 404, the client says the true
  ;; sentence about WHY -- but they must not disagree about which writes exist,
  ;; or a command the phone offers is one the door refuses and the operator is
  ;; told "no such command" about something that is real.
  (is (.exists (io/file ingress-source))
      "the ingress Worker is not where this test looks; drift cannot be checked")
  (is (= terminal/offered-writes (ingress-writes))
      "the client's offered writes and the ingress table have drifted apart"))
