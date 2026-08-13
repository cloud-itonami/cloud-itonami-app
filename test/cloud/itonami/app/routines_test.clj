(ns cloud.itonami.app.routines-test
  "The host half of routines and handoffs: what is stored, what is refused, and
  the one property that has to hold at the route rather than only in the core.

  `routine_kotoba_parity_test` and `handoff_kotoba_parity_test` already check
  the judgements exhaustively against the shipped cores. Nothing here re-checks
  them. What is checked here is the part a decision core cannot see: that the
  demonstration comes from calls that RAN, that the address is the content, and
  that a handoff arriving at a Bot does not change what that Bot may do.

  The model seam is redefined rather than reached. `advance!` is private, so it
  is stopped one level down at `provider/agent-turn` — a turn with no tool calls
  ends the loop, which is enough for every assertion here and reaches no
  network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.store :as store]))

(defn- with-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-routines-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))]
        (f))
      (finally (reset! store/state previous)))))

(def ^:private alice {:user-id "alice" :organization-id "org-1" :kind :passkey})
(def ^:private bob {:user-id "bob" :organization-id "org-1" :kind :passkey})

(defn- make-bot [session attrs]
  (bots/create! nil session (merge {:name "worker"
                                    :connectors ["com.google.gmail"]}
                                   attrs)))

(defn- traced!
  "Seed the record of calls that executed, the way `trace!` writes it."
  [bot-id tools]
  (store/transact!
   (fn [state]
     (assoc-in state [:bots :traces bot-id]
               (mapv (fn [t] {:trace/tool t :trace/effect :read
                              :trace/at "2026-08-13T00:00:00.000000Z"})
                     tools)))))

(defn- quiet-model
  "A turn that calls no tool, which ends `advance!` after one pass."
  [f]
  (with-redefs [policy/select-provider (fn [_ _] {:id :test :local? true})
                provider/agent-turn (fn [_ _] {:content "done" :tool-calls []})]
    (f)))

;; ── routines ─────────────────────────────────────────────────────────

(deftest a-routine-cannot-be-recorded-from-nothing
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"まだ何も実行していない"
             (bots/record-routine! nil alice (:bot/id b) {:name "morning"})))))))

(deftest a-routine-is-built-from-the-calls-that-ran
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (traced! (:bot/id b) ["gmail.messages.list" "gmail.messages.get"])
        (let [r (bots/record-routine! nil alice (:bot/id b)
                                      {:name "morning" :intent "受信箱を確認する"})]
          (is (= ["gmail.messages.list" "gmail.messages.get"]
                 (mapv :tool (:steps r))))
          (is (= "受信箱を確認する" (:intent (first (:steps r)))))
          (testing "and it is listed for its Bot"
            (is (= [(:id r)] (mapv :id (bots/routines nil alice (:bot/id b)))))))))))

(deftest the-address-is-the-content
  (with-store
    (fn []
      (let [one (make-bot alice {:name "one"})
            two (make-bot alice {:name "two"})
            three (make-bot alice {:name "three"})]
        (traced! (:bot/id one) ["a" "b"])
        (traced! (:bot/id two) ["a" "b"])
        (traced! (:bot/id three) ["a" "c"])
        (let [ra (bots/record-routine! nil alice (:bot/id one)
                                       {:name "x" :intent "same"})
              rb (bots/record-routine! nil alice (:bot/id two)
                                       {:name "DIFFERENT NAME" :intent "same"})
              rc (bots/record-routine! nil alice (:bot/id three)
                                       {:name "x" :intent "same"})]
          (testing "the same workflow is one address, whatever it was called"
            (is (= (:address ra) (:address rb))))
          (testing "a different workflow is a different address"
            (is (not= (:address ra) (:address rc))))
          (testing "and it is a sha256, not an id"
            (is (str/starts-with? (:address ra) "sha256:"))))))))

(deftest recording-consumes-the-demonstration
  ;; Otherwise the next routine recorded on this Bot silently begins with the
  ;; steps of the previous one.
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (traced! (:bot/id b) ["a" "b"])
        (bots/record-routine! nil alice (:bot/id b) {:name "first" :intent "i"})
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"まだ何も実行していない"
             (bots/record-routine! nil alice (:bot/id b) {:name "second"})))))))

(deftest a-routine-belongs-to-its-bots-owner
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (traced! (:bot/id b) ["a"])
        (let [r (bots/record-routine! nil alice (:bot/id b) {:name "x" :intent "i"})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"このセッションのもの"
                                (bots/routines nil bob (:bot/id b))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"このセッションのもの"
                                (bots/start-routine! nil bob (:bot/id b) (:id r)))))))))

(deftest a-routine-whose-tools-are-gone-refuses-rather-than-running-short
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        ;; A tool no catalogue row will ever match: admitted-steps drops it,
        ;; so recorded > admitted, which is exactly staleness.
        (traced! (:bot/id b) ["no.such.tool"])
        (let [r (bots/record-routine! nil alice (:bot/id b) {:name "x" :intent "i"})]
          (is (true? (:stale? r)))
          (is (= "stale" (:status r)))
          (is (false? (:may-start? r)))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"いま使えないツール"
               (bots/start-routine! nil alice (:bot/id b) (:id r)))))))))

(deftest a-schedule-has-a-floor-and-a-shape
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (traced! (:bot/id b) ["a"])
        (let [r (bots/record-routine! nil alice (:bot/id b) {:name "x" :intent "i"})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"分以上"
               (bots/update-routine! nil alice (:bot/id b) (:id r)
                                     {:schedule {:kind :every-minutes
                                                 :every-minutes 1}})))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"対応していない schedule"
               (bots/update-routine! nil alice (:bot/id b) (:id r)
                                     {:schedule {:kind :cron :every-minutes 60}})))
          (testing "a valid one is kept"
            (let [updated (bots/update-routine! nil alice (:bot/id b) (:id r)
                                                {:schedule {:kind :every-minutes
                                                            :every-minutes 30}})]
              (is (= 30 (:schedule/every-minutes (:schedule updated)))))))))))

(deftest steps-are-not-editable-in-place
  ;; A routine whose steps changed is a different routine — it has a different
  ;; address — and a schedule pointing at the old one must not silently follow.
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (traced! (:bot/id b) ["a"])
        (let [r (bots/record-routine! nil alice (:bot/id b) {:name "x" :intent "i"})
              after (bots/update-routine! nil alice (:bot/id b) (:id r)
                                          {:name "renamed"
                                           :steps [{:tool "b" :effect "read"}]})]
          (is (= "renamed" (:name after)))
          (is (= ["a"] (mapv :tool (:steps after))))
          (is (= (:address r) (:address after))))))))

(deftest an-unscheduled-routine-never-fires
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (traced! (:bot/id b) ["a"])
        (bots/record-routine! nil alice (:bot/id b) {:name "x" :intent "i"})
        (is (= {:started [] :skipped []}
               (quiet-model #(bots/fire-due! nil alice (store/now)))))))))

;; ── handoff ──────────────────────────────────────────────────────────

(deftest work-does-not-cross-between-two-peoples-bots
  (with-store
    (fn []
      (let [mine (make-bot alice {:name "mine"})
            theirs (make-bot bob {:name "theirs"})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"このセッションのもの"
             (quiet-model
              #(bots/hand-off! nil alice (:bot/id mine) (:bot/id theirs)
                               {:task "調べて"}))))))))

(deftest a-bot-cannot-hand-work-to-itself
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"自分自身"
             (quiet-model
              #(bots/hand-off! nil alice (:bot/id b) (:bot/id b) {:task "調べて"}))))))))

(deftest a-chain-stops-at-the-ceiling
  (with-store
    (fn []
      (let [a (make-bot alice {:name "a"})
            b (make-bot alice {:name "b"})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"上限"
             (quiet-model
              #(bots/hand-off! nil alice (:bot/id a) (:bot/id b)
                               {:task "調べて" :depth 4}))))))))

(deftest a-handoff-arrives-attributed-and-carries-no-authority
  ;; The property the whole design turns on. The sender holds a tool the target
  ;; does not; after the handoff the target's grant is still its own.
  (with-store
    (fn []
      (let [sender (make-bot alice {:name "研究"
                                    :tools ["gmail.messages.list" "gmail.messages.send"]
                                    :writes? true})
            target (make-bot alice {:name "作文" :tools ["gmail.messages.list"]})
            before (:bot/tools target)
            result (quiet-model
                    #(bots/hand-off! nil alice (:bot/id sender) (:bot/id target)
                                     {:task "まとめて"}))
            after (->> (:bots (bots/overview nil alice))
                       (filter #(= (:bot/id target) (:id %)))
                       first)]
        (testing "it is written into the target's conversation, saying who sent it"
          (is (some #(and (str/includes? (str (:text %)) "研究")
                          (str/includes? (str (:text %)) "まとめて"))
                    (:messages result))))
        (testing "the target's grant is unchanged"
          (is (= #{"gmail.messages.list"} before))
          (is (= ["gmail.messages.list"] (:tools after))))
        (testing "and the sender's extra tool did not become the target's"
          (is (not (contains? (set (:tools after)) "gmail.messages.send"))))
        (testing "the chain position is recorded so the ceiling can be reached"
          (is (= 1 (:depth (:handoff result)))))))))

(deftest an-empty-handoff-is-refused
  (with-store
    (fn []
      (let [a (make-bot alice {:name "a"})
            b (make-bot alice {:name "b"})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"引き継ぐ内容が空"
             (quiet-model
              #(bots/hand-off! nil alice (:bot/id a) (:bot/id b) {:task "  "}))))))))
