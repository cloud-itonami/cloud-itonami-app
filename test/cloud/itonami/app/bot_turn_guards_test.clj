(ns cloud.itonami.app.bot-turn-guards-test
  "Two ways a bounded turn keeps spending without getting anywhere.

  `oracle-cases` already checks the judgements against the shipped core, and
  nothing here re-checks them. What is checked here is the part a decision core
  cannot see: that the LOOP asks, and that what a person is left with afterwards
  says which of the two happened.

  ## Why each of these was a defect and not a nicety

  - an empty turn was recorded as COMPLETED and appended an empty message. A run
    that did nothing and a run that finished were then the same row — in the
    audit trail, and in the one-line preview the Bot picker shows — and no later
    reader could separate them. This is the shape CLAUDE.md names: an execution
    that could not answer returning the value of one that answered.
  - a repeated call ran until the tool budget ended it, and the budget then
    reported `:continuation-budget-exhausted`, which sends a person to raise a
    limit that was never the problem.

  Both are asserted by their RESULT — the state and the error type — rather than
  by the run merely having stopped. Both stopped before, too; stopping is not
  the change.

  The model seam is redefined rather than reached, the way `routines-test` does
  it. Nothing here calls a model or reaches the network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.store :as store]))

(defn- with-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-turn-guards-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))]
        (f))
      (finally (reset! store/state previous)))))

(def ^:private alice {:user-id "alice" :organization-id "org-1" :kind :passkey})

(defn- private-fn [name]
  (some-> (ns-resolve 'cloud.itonami.app.bots name) deref))

(defn- make-bot []
  (bots/create! nil alice {:name "worker" :connectors ["com.google.gmail"]}))

(defn- with-model
  "Run `f` with `provider/agent-turn` answering from `answers`, one per call.

  A vector rather than a constant so a test can say 'empty, then empty again'
  and 'empty, then a real answer' — the two cases the nudge is between. Past
  the end the last answer repeats, which is what a model stuck in one of these
  two states actually does."
  [answers f]
  (let [remaining (atom (vec answers))]
    (with-redefs [policy/select-provider (fn [_ _] {:id :test :local? true
                                                    :default-model "test-model"})
                  provider/agent-turn
                  (fn [_ _]
                    (let [answer (or (first @remaining) (last answers))]
                      (swap! remaining #(vec (rest %)))
                      answer))]
      (f))))

(defn- advance!
  "One turn loop, with what it finished as captured.

  Driven at `advance!` rather than through `send!` because `send!` refuses
  before the loop when the Bot has no admitted tools, and these guards are
  inside the loop. `on-finish` is the same callback the real callers pass, so
  what is asserted below is the record a person would have seen."
  [b run]
  (let [finished (atom nil)]
    ((private-fn 'advance!) nil b
     (merge {:id "run-1" :context-id "context-1" :tools [] :runnable #{}
             :tool-provider {} :blocked {} :turn-count 0 :tool-count 0}
            run)
     {:on-finish (fn [record] (reset! finished record))})
    @finished))

(defn- said [bot-id]
  (->> (bots/messages alice bot-id)
       (filter #(= "bot" (:role %)))
       (map :text)))

;; ── an empty answer ──────────────────────────────────────────────────

(deftest an-empty-turn-is-asked-once-more-before-it-is-refused
  ;; A dropped response is usually not repeated, so the first one must not
  ;; become an error a person has to act on.
  (with-store
    (fn []
      (let [b (make-bot)]
        (with-model [{:content "" :tool-calls []}
                     {:content "できました。" :tool-calls []}]
          (fn []
            (let [record (advance! b {})
                  spoken (said (:bot/id b))]
              (is (= :completed (:turn/state record))
                  "the second answer arrived, so the turn did finish")
              (is (= "できました。" (:turn/result record))
                  "and what it finally said is what the person is left with")
              (is (not-any? str/blank? spoken)
                  "the empty first turn must not have been appended as a message"))))))))

(deftest a-turn-that-stays-empty-fails-by-that-name
  ;; Pinned to `:provider/empty-answer`, not to the run merely having stopped:
  ;; it stopped before this guard existed too, as `completed`. A refusal for
  ;; some other reason is not this guard working, and `thrown?`-shaped
  ;; assertions would count it as one.
  (with-store
    (fn []
      (let [b (make-bot)]
        (with-model [{:content "" :tool-calls []}
                     {:content "   " :tool-calls []}
                     {:content "" :tool-calls []}]
          (fn []
            (let [record (advance! b {})]
              (is (= :failed (:turn/state record))
                  "a turn that produced nothing did not complete")
              (is (= :provider/empty-answer (:turn/error-type record))
                  "and says which of the ways to produce nothing it was"))))))))

(deftest whitespace-is-not-content
  ;; The reason `answer-empty?` trims. A turn whose whole answer is a space has
  ;; said nothing, and appending it puts a blank row in the conversation and a
  ;; blank preview in the picker.
  (with-store
    (fn []
      (let [b (make-bot)]
        (with-model [{:content "  \n " :tool-calls []}
                     {:content "\t" :tool-calls []}]
          (fn []
            (is (= :provider/empty-answer (:turn/error-type (advance! b {})))
                "whitespace-only answers are empty answers")))))))

(deftest a-turn-with-prose-still-completes
  ;; The control. If this failed the guard would be refusing ordinary turns,
  ;; and every assertion above would still pass.
  (with-store
    (fn []
      (let [b (make-bot)]
        (with-model [{:content "終わりました。" :tool-calls []}]
          (fn []
            (let [record (advance! b {})]
              (is (= :completed (:turn/state record)))
              (is (nil? (:turn/error-type record))))))))))

;; ── the same call, over and over ─────────────────────────────────────

(def ^:private repeated-call
  {:id "call-1" :name "gmail.messages.list" :input {:query "is:unread"}})

(deftest repeating-one-call-stops-by-that-name-rather-than-on-the-budget
  ;; The distinction this exists for. Before, the loop ran the same call until
  ;; the tool budget ended it and then reported a budget failure — a true
  ;; statement that points at the wrong thing.
  (with-store
    (fn []
      (let [b (make-bot)]
        (with-model [{:content "" :tool-calls [repeated-call]}]
          (fn []
            (let [record (advance! b {:tools [{:name "gmail.messages.list"}]
                                      :runnable #{"gmail.messages.list"}})]
              (is (= :failed (:turn/state record)))
              (is (= :provider/repeating (:turn/error-type record))
                  "not :continuation-budget-exhausted, which was the old answer")
              (is (< (:turn/tool-count record 99) bots/max-tool-calls)
                  "and it stopped before spending the whole tool budget"))))))))

(deftest a-durable-goal-checkpoints-and-breaks-the-repetition-chain
  ;; Resident work is a durable Goal split into execution slices. Repeating a
  ;; read is a reason to end one slice and change course, not to throw away the
  ;; Goal and count the whole resident tick as failed.
  (with-store
    (fn []
      (let [b (make-bot)]
        (with-model [{:content "" :tool-calls [repeated-call]}]
          (fn []
            (let [record (advance! b {:goal? true
                                      :messages []
                                      :tools [{:name "gmail.messages.list"}]
                                      :runnable #{"gmail.messages.list"}})
                  saved (get-in @store/state [:bots :runs (:bot/id b)])
                  counted (private-fn 'identical-call-count)]
              (is (= :checkpointed (:turn/state record)))
              (is (nil? (:turn/error-type record))
                  "the repetition ended a slice; it did not fail the Goal")
              (is (= "tool" (:role (nth (:messages saved) (- (count (:messages saved)) 3))))
                  "the suppressed proposal has a tool result, so provider history is valid")
              (is (= 1 (counted saved repeated-call))
                  "an assistant recovery barrier lets the resumed model choose afresh"))))))))

(deftest the-count-resets-when-something-else-is-proposed
  ;; A Bot alternating between two tools is making progress. If an interleaved
  ;; call did not reset the count, this guard would refuse ordinary work — the
  ;; failure mode that matters more than the one it prevents.
  (with-store
    (fn []
      (let [b (make-bot)
            signature (private-fn 'call-signature)
            counted (private-fn 'identical-call-count)
            other {:id "call-2" :name "gmail.messages.get" :input {:id "x"}}
            assistant (fn [call] {:role "assistant" :content "" :tool-calls [call]})]
        (is (some? counted) "the counting is the part under test here")
        (testing "an unbroken run counts up, including the call about to run"
          (is (= 3 (counted {:messages [(assistant repeated-call)
                                        (assistant repeated-call)]}
                            repeated-call))))
        (testing "a different call in between resets it"
          (is (= 1 (counted {:messages [(assistant repeated-call)
                                        (assistant other)]}
                            repeated-call))))
        (testing "tool results between two proposals are not a break"
          (is (= 2 (counted {:messages [(assistant repeated-call)
                                        {:role "tool" :tool-call-id "call-1"
                                         :name "gmail.messages.list" :content "ok"}]}
                            repeated-call))))
        (testing "argument order is not identity"
          ;; A model reissuing a call is not obliged to serialise its keys the
          ;; same way twice, and reading a reordering as a new call is how this
          ;; guard would quietly never fire.
          (is (= (signature {:name "t" :input {:a 1 :b 2}})
                 (signature {:name "t" :input {:b 2 :a 1}}))))
        (testing "and the call id is not identity either"
          (is (= (signature (assoc repeated-call :id "call-9"))
                 (signature repeated-call))))))))
