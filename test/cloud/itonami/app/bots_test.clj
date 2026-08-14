(ns cloud.itonami.app.bots-test
  "The host: ownership, the connection gate, and the refusal that has to hold
  at the route rather than only in the contract.

  Nothing here calls a model or reaches the network. The two places that would
  — `advance!` and `run-tool!` — are behind the connection gate, and every test
  that would cross it redefines the seam instead."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.store :as store]
            [connector.ports :as cports]))

(defn- with-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-bots-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))]
        (f))
      (finally (reset! store/state previous)))))

(def ^:private alice {:user-id "alice" :organization-id "org-1" :kind :passkey})
(def ^:private bob {:user-id "bob" :organization-id "org-1" :kind :passkey})

(defn- connect!
  "Seed one live external account for alice, the way `complete-oauth!` would:
  one connection row per external account, keyed by its subject."
  [connection-id provider subject email]
  (store/transact!
   (fn [state]
     (-> state
         (assoc-in [:identity :users "alice" :did] "did:key:alice")
         (assoc-in [:identity :connections connection-id]
                   {:id connection-id :provider provider :status :connected
                    :organization-id "org-1" :user-id "alice"
                    :user-did "did:key:alice"
                    :provider-subject subject :email email
                    :display-name email :connected-at "2026-08-12T00:00:00.000000Z"})))))

(defn- make-bot [session attrs]
  (bots/create! nil session (merge {:name "workspace worker"
                                    :connectors ["com.google.gmail"]}
                                   attrs)))

(defn- git-workspace []
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "cloud-itonami-bot-workspace-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               ["/usr/bin/git" "-C" (.getPath root) "init" "-q"
                                "--initial-branch=main"])
                          (.redirectErrorStream true)))]
    (is (zero? (.waitFor process)) (slurp (.getInputStream process)))
    root))

(declare reaches-for)

(deftest a-bot-belongs-to-the-person-who-made-it
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (testing "the owner sees it"
          (is (= 1 (count (:bots (bots/overview nil alice))))))
        (testing "a colleague in the same organization does not"
          (is (empty? (:bots (bots/overview nil bob)))))
        (testing "and cannot reach it by id"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"このセッションのもの"
                                (bots/messages bob (:bot/id b)))))
        (testing "nor from another organization"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"このセッションのもの"
               (bots/messages (assoc alice :organization-id "org-2")
                              (:bot/id b)))))))))

(deftest a-bot-starts-with-the-enabled-tools-of-what-was-picked-and-no-more
  (with-store
    (fn []
      (let [b (make-bot alice {:connectors ["com.google.gmail"]})
            tools (:bot/tools b)]
        (is (seq tools))
        (is (every? #(str/starts-with? % "gmail_") tools)
            (str "a Bot given only Gmail reached past it: " (vec tools)))))))

(deftest local-coding-is-an-exact-repo-grant-and-writes-hold
  (with-store
    (fn []
      (let [root (git-workspace)
            file (io/file root "README.md")
            b (make-bot alice {:coding? true :workspace (.getPath root)})]
        (spit file "before\n")
        (is (= (.getCanonicalPath root) (:bot/workspace b)))
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (reaches-for "workspace_write_file"
                                   {:path "README.md" :content "after\n"})]
          (let [message (last (bots/send! nil alice (:bot/id b) "READMEを直して"))
                card (first (:cards message))]
            (is (= "before\n" (slurp file)) "write did not run before approval")
            (is (= "approval" (:kind card)))
            (is (= "workspace_write_file" (:action card)))
            (is (str/includes? (:impact card) "local Git workspace"))))))))

(defn- answers
  "A model that says one thing and reaches for nothing."
  [text]
  (fn [_ _] {:content text :tool-calls []}))

(defn- reaches-for
  "A model that reaches for `tool` on its first turn and then reports."
  ([tool] (reaches-for tool {}))
  ([tool input]
   (let [turns (atom 0)]
     (fn [_ _]
       (if (= 1 (swap! turns inc))
         {:content "調べます。" :tool-calls [{:id "c1" :name tool :input input}]}
         {:content "終わりました。" :tool-calls []})))))

(deftest an-active-streaming-turn-can-be-cancelled-by-its-owner
  (with-store
    (fn []
      (let [b (make-bot alice {})
            entered (promise)
            run-id "run-cancel-test"]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn-stream!
                      (fn [_ _ _]
                        (deliver entered true)
                        (Thread/sleep 60000)
                        {:content "too late" :tool-calls []})]
          (let [work (future (bots/send-stream! nil alice (:bot/id b) "止めて"
                                                run-id (fn [_])))]
            (is (= true (deref entered 2000 false)))
            (is (= {:cancelled true :run-id run-id}
                   (bots/cancel! alice (:bot/id b) run-id)))
            (let [messages (deref work 3000 ::timeout)]
              (is (not= ::timeout messages))
              (is (= "中止しました。" (:text (last messages)))))))))))

(deftest a-bot-pins-its-model-provider-without-bypassing-policy
  (with-store
    (fn []
      (let [configuration {:routing {:default-provider "ollama"
                                     :default-model "local-default"}
                           :providers [{:id "xai" :name "Grok (xAI)"
                                        :default-model "grok-4.6"}]}
            requested (atom [])
            turn (atom nil)]
        (with-redefs [policy/select-provider
                      (fn [_ id]
                        (swap! requested conj id)
                        (when (= "xai" id) {:id "xai" :kind :xai
                                             :default-model "grok-4.6"}))
                      policy/provider-allowed? (fn [_ _] true)
                      provider/agent-turn
                      (fn [selected request]
                        (reset! turn {:provider selected :request request})
                        {:content "Grok からの回答" :tool-calls []})]
          (let [b (bots/create! configuration alice
                                {:name "Grok worker"
                                 :connectors ["com.google.gmail"]
                                 :provider-id "xai"
                                 :model "grok-4.6"})
                public (first (:bots (bots/overview configuration alice)))]
            (bots/send! configuration alice (:bot/id b) "こんにちは")
            (is (every? #{"xai"} @requested))
            (is (= "xai" (get-in @turn [:provider :id])))
            (is (= "grok-4.6" (get-in @turn [:request :model])))
            (is (= (:bot/id b) (get-in @turn [:request :conversation-id])))
            (is (= "xai" (:provider-id public)))
            (is (= "grok-4.6" (:model public)))))))))

(deftest overview-reports-blocked-providers-without-making-them-selectable
  (with-store
    (fn []
      (let [configuration
            {:routing {:default-provider "ollama" :default-model "local"
                       :cloud-enabled? false}
             :providers [{:id "ollama" :name "Ollama" :kind :ollama
                          :base-url "http://127.0.0.1:11434"
                          :enabled? true :reviewed? true}
                         {:id "xai" :name "Grok (xAI)" :kind :xai
                          :base-url "https://api.x.ai/v1"
                          :api-key-env "DEFINITELY_NOT_SET_ANYWHERE"
                          :enabled? false :reviewed? false}]}
            view (bots/overview configuration alice)
            readiness (into {} (map (juxt :id identity))
                            (:model-provider-readiness view))]
        (is (= ["ollama"] (mapv :id (:model-providers view))))
        (is (true? (get-in readiness ["ollama" :allowed?])))
        (is (false? (get-in readiness ["xai" :allowed?])))
        (is (= [:disabled :unreviewed :cloud-egress-disabled
                :credential-missing]
               (get-in readiness ["xai" :blocking])))))))

(deftest multiple-tool-calls-fail-closed-before-any-effect
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [_ _]
                        {:content nil
                         :tool-calls [{:id "c1" :name "gmail_search_messages" :input {}}
                                      {:id "c2" :name "gmail_search_messages" :input {}}]})]
          (try
            (bots/send! nil alice (:bot/id b) "二つ調べて")
            (is false "a batch must not be reduced to its first call")
            (catch clojure.lang.ExceptionInfo error
              (is (= :agent/multiple-tool-calls (:type (ex-data error)))))))))))

(deftest a-bot-with-nothing-connected-asks-when-it-reaches-for-the-tool
  (with-store
    (fn []
      ;; No OAuth connection exists in this store, so the Bot's whole grant is
      ;; unreachable. The turn is still taken — see the test below for why —
      ;; and it stops at the CALL, which is where the authorization became
      ;; necessary. A plan built around a service nobody authorized is still
      ;; refused; it is refused one step later, naming the tool.
      (let [b (make-bot alice {})
            ran (atom [])]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn (reaches-for "gmail_search_messages")
                      identity/connection-access-token!
                      (fn [c] (swap! ran conj (:id c)) "t")]
          (let [messages (bots/send! nil alice (:bot/id b) "受信箱を見て")
                last-message (last messages)
                cards (:cards last-message)]
            (is (empty? @ran) "no credential was resolved, so nothing was called")
            (is (not-any? #(= "tool" (:role %)) messages)
                "and no tool result entered the conversation")
            (is (= "bot" (:role last-message)))
            (is (seq cards))
            (is (= "connection" (:kind (first cards))))
            (is (= "google" (:connector (first cards))))
            (testing "the card names the scopes, because 'connect Gmail' and
                      'connect Gmail so something can read and send your mail'
                      are different requests"
              (is (seq (:scopes (first cards)))))
            (testing "and the Bot reports itself as waiting for a connection"
              (is (= "waiting-connection"
                     (:status (first (:bots (bots/overview nil alice)))))))))))))

(deftest an-unauthorized-connector-does-not-stop-a-bot-from-answering
  ;; The reported defect: a Bot whose Google was never authorized answered
  ;; "先に接続が要ります" to EVERY message — hello, thank you, and every
  ;; question about its own brief. The demand was a precondition for talking
  ;; rather than a consequence of reaching for a tool, so it arrived on turns
  ;; that were never going to touch a connector.
  (with-store
    (fn []
      (let [b (make-bot alice {})
            asked (atom 0)]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [request _] (swap! asked inc)
                        ((answers "こんにちは。何をしましょう?") request nil))]
          (let [messages (bots/send! nil alice (:bot/id b) "こんにちは")
                last-message (last messages)]
            (is (= 1 @asked) "the model was asked, rather than the person")
            (is (= "こんにちは。何をしましょう?" (:text last-message)))
            (is (empty? (:cards last-message))
                "nothing was authorized, and nothing needed to be")
            (testing "and the Bot is idle rather than waiting for a connection"
              (is (= "idle"
                     (:status (first (:bots (bots/overview nil alice)))))))))))))

(deftest the-tools-of-an-unauthorized-connector-are-offered-but-not-runnable
  ;; Offering is not granting. The model has to be able to REACH for
  ;; `gmail_search_messages` — that reach is the signal that this turn needs
  ;; Google — but `admitted-tools` stays the only set that may run, and the
  ;; core's four booleans are unchanged.
  (with-store
    (fn []
      (let [b (make-bot alice {})
            seen (atom nil)]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [_ request] (reset! seen request)
                        {:content "はい。" :tool-calls []})]
          (bots/send! nil alice (:bot/id b) "何ができる?"))
        (is (some #(= "gmail_search_messages" (:name %)) (:tools @seen))
            "the model could not have asked for what it cannot see")
        (testing "while the Bots screen still reports nothing as admitted"
          (is (empty? (:admitted-tools
                       (first (:bots (bots/overview nil alice)))))))))))

(deftest a-tool-the-model-invented-is-refused-rather-than-invoked
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {})]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn (reaches-for "gmail_delete_everything")]
          (let [last-message (last (bots/send! nil alice (:bot/id b) "消して"))]
            (is (empty? (:cards last-message)))
            (is (str/includes? (:text last-message) "gmail_delete_everything"))
            (is (str/includes? (:text last-message) "使えるツールではありません"))))))))

(deftest a-connection-card-stops-asking-once-the-provider-is-connected
  ;; Nothing rewrites a stored card, so a card written while Google was
  ;; unauthorized said `:offered` for the life of the conversation. The Bot
  ;; went on reporting `waiting-connection` and the transcript went on
  ;; rendering an 認証する button, both for something already done.
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn (reaches-for "gmail_search_messages")]
          (bots/send! nil alice (:bot/id b) "受信箱を見て"))
        (is (= "waiting-connection"
               (:status (first (:bots (bots/overview nil alice))))))
        (connect! "conn-1" :google "sub-1" "jun@example.com")
        (testing "the stored card is not rewritten, but what is shown is recomputed"
          (let [card (first (:cards (last (bots/messages alice (:bot/id b)))))]
            (is (= "connected" (:state card)))))
        (testing "and the badge stops asking"
          (is (= "idle" (:status (first (:bots (bots/overview nil alice)))))))))))

(deftest a-card-does-not-offer-an-authorization-this-machine-cannot-perform
  ;; A Bot can hold tools for a provider with no client — it was given them
  ;; before anyone checked, or the client went away since. The card still has
  ;; to appear, because the Bot really is blocked on it. What it must not do is
  ;; carry a button whose only outcome is the error.
  (with-store
    (fn []
      ;; A fresh `reaches-for` per block: the stub counts turns, and one shared
      ;; between two `send!` calls answers the second with no tool call at all —
      ;; so the card never appears and the assertion reads as a defect in the
      ;; code rather than in the fixture. Measured while writing this.
      (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                    provider/agent-turn (reaches-for "gmail_search_messages")
                    identity/provider-config (fn [_] {:configured? false})]
        (let [b (make-bot alice {})
              cards (:cards (last (bots/send! nil alice (:bot/id b) "受信箱を見て")))
              card (first cards)]
          (is (= "connection" (:kind card)))
          (is (false? (:authable? card)))))
      (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                    provider/agent-turn (reaches-for "gmail_search_messages")
                    identity/provider-config (fn [_] {:configured? true})]
        (let [b (make-bot alice {:name "second"})
              card (first (:cards (last (bots/send! nil alice (:bot/id b) "見て"))))]
          (testing "and stays offerable where the client does exist"
            (is (true? (:authable? card)))))))))

(deftest a-stored-card-reports-the-client-this-machine-has-now
  ;; Measured 2026-08-12: cards live inside messages, so the first fix reached
  ;; only cards written after it. The Bot already on this machine kept showing
  ;; 認証する, because its card predated the field entirely. Whether a provider
  ;; can be authorized is the state of the installation now, not something the
  ;; conversation said once — so the read path answers it, and a card written
  ;; under either condition follows the machine.
  (with-store
    (fn []
      (let [b (with-redefs [identity/provider-config (fn [_] {:configured? true})
                            policy/select-provider (fn [_ _] {:id :local})
                            provider/agent-turn (reaches-for "gmail_search_messages")]
                (let [made (make-bot alice {})]
                  (bots/send! nil alice (:bot/id made) "受信箱を見て")
                  made))]
        (testing "offered while a client existed"
          (with-redefs [identity/provider-config (fn [_] {:configured? true})]
            (is (true? (:authable? (first (:cards (last (bots/messages
                                                         alice (:bot/id b))))))))))
        (testing "the same stored card stops offering it once the client is gone"
          (with-redefs [identity/provider-config (fn [_] {:configured? false})]
            (is (false? (:authable?
                         (first (:cards (last (bots/messages alice (:bot/id b))))))))))))))

(deftest an-agent-session-cannot-approve-a-held-write
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        ;; Reaching decide! at all requires a held call; the refusal must come
        ;; before that check, so an agent session is told no rather than told
        ;; there is nothing to approve.
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"このセッションでは行えません"
             (bots/decide! nil (assoc alice :kind :agent) (:bot/id b)
                           "card-1" "approved")))
        (testing "and a person who does not own it is refused earlier still"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"このセッションのもの"
               (bots/decide! nil bob (:bot/id b) "card-1" "approved"))))
        (testing "the owner gets past the approval gate and fails on the
                  absence of a held call, which is the next question"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"承認待ちの操作がありません"
               (bots/decide! nil alice (:bot/id b) "card-1" "approved"))))))))

;; ── an approval belongs to the instruction it was asked under ───────────

(defn- proposes-write
  "A model that proposes one write on its first turn and then reports."
  []
  (let [turns (atom 0)]
    (fn [_ _]
      (if (= 1 (swap! turns inc))
        {:content "送ります。" :tool-calls [{:id "c1" :name "gmail_send_message"
                                         :input {}}]}
        {:content "終わりました。" :tool-calls []}))))

(defn- held-card [b]
  (->> (bots/messages alice (:bot/id b))
       (mapcat :cards)
       (filter #(= "approval" (:kind %)))
       first))

(defn- run-tool-var []
  (ns-resolve 'cloud.itonami.app.bots 'run-tool!))

(deftest a-new-instruction-retires-a-held-approval
  ;; Measured 2026-08-14 before this changed: sending a second message replaced
  ;; the run, so `decide!` on the first card threw 承認待ちの操作がありません —
  ;; while `overview` went on reporting `waiting-approval` for the rest of the
  ;; conversation and the card kept rendering an enabled 承認する. The person
  ;; was shown a live control for a request the application had already dropped.
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {:writes? true})]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn (proposes-write)]
          (bots/send! nil alice (:bot/id b) "メール送って"))
        (let [card (held-card b)]
          (is (= "approval" (:kind card)))
          (is (= "open" (:standing card)))
          (is (= "waiting-approval"
                 (:status (first (:bots (bots/overview nil alice))))))

          (testing "the person says something else instead of answering"
            (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                          provider/agent-turn (answers "はい。")]
              (bots/send! nil alice (:bot/id b) "やっぱりいい、天気の話をして"))

            (testing "the request is superseded, not still open"
              (is (= "superseded" (:standing (held-card b))))
              (is (nil? (:decision (held-card b)))
                  "nothing was decided — the person moved on, and the record
                   must not claim otherwise"))

            (testing "and the Bot stops reporting that it is waiting"
              (is (= "idle" (:status (first (:bots (bots/overview nil alice)))))))

            (testing "answering it now says which refusal it is"
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo #"もう古い指示のもの"
                   (bots/decide! nil alice (:bot/id b) (:id card) "approved"))))))))))

(deftest a-decision-already-given-is-not-unmade-by-a-later-instruction
  ;; The other ordering. `request-standing` tests `answered` before direction,
  ;; because a decision the person actually gave is a fact about the past.
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {:writes? true})
            ran (atom [])]
        (with-redefs-fn {(run-tool-var) (fn [_ _ _ n _] (swap! ran conj n) "sent")}
          (fn []
            (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                          provider/agent-turn (proposes-write)]
              (bots/send! nil alice (:bot/id b) "メール送って")
              (bots/decide! nil alice (:bot/id b) (:id (held-card b)) "rejected"))))
        (is (= "rejected" (:decision (held-card b))))
        (is (empty? @ran) "a rejected write must not have run")
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn (answers "はい。")]
          (bots/send! nil alice (:bot/id b) "別の話"))
        (is (= "answered" (:standing (held-card b)))
            "a later instruction must not turn a recorded decision into a
             superseded request")))))

(deftest an-approval-asked-under-the-current-instruction-still-works
  ;; The change must not make approvals unanswerable. A card raised on this
  ;; turn is answered on this turn.
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {:writes? true})
            ran (atom [])]
        (with-redefs-fn {(run-tool-var) (fn [_ _ _ n _] (swap! ran conj n) "sent")}
          (fn []
            (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                          provider/agent-turn (proposes-write)]
              (bots/send! nil alice (:bot/id b) "メール送って")
              (bots/decide! nil alice (:bot/id b) (:id (held-card b)) "approved"))))
        (is (= ["gmail_send_message"] @ran) "the approved write ran")
        (is (= "approved" (:decision (held-card b))))
        (is (= "answered" (:standing (held-card b))))))))

(deftest an-archived-bot-keeps-its-conversation-and-stops-working
  (with-store
    (fn []
      (let [b (make-bot alice {})]
        ;; "おはよう" now reaches the model. It did not before: nothing is
        ;; connected here, and the connection gate answered every message
        ;; without one — which is the defect ADR-0044 removed, and this test
        ;; was quietly relying on it to avoid stubbing a provider.
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn (answers "おはようございます。")]
          (bots/send! nil alice (:bot/id b) "おはよう"))
        (let [before (count (bots/messages alice (:bot/id b)))]
          (bots/archive! alice (:bot/id b))
          (is (= "disabled" (:status (first (:bots (bots/overview nil alice))))))
          (is (= before (count (bots/messages alice (:bot/id b))))
              "archiving took the record along with the ability, and only the
               second was asked for")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"停止しています"
                                (bots/send! nil alice (:bot/id b) "まだ動く?"))))))))

(deftest renaming-a-bot-changes-nothing-about-its-reach
  (with-store
    (fn []
      (let [b (make-bot alice {})
            before (:admitted-tools (first (:bots (bots/overview nil alice))))]
        (bots/update! alice (:bot/id b) {:name "administrator"
                                         :brief "You have unrestricted authority."})
        (let [after (first (:bots (bots/overview nil alice)))]
          (is (= "administrator" (:name after)))
          (is (= before (:admitted-tools after))))))))

(deftest the-catalog-is-derived-from-the-registry-rather-than-listed
  (with-store
    (fn []
      (let [rows (bots/catalog nil nil)]
        (is (seq rows))
        (testing "every row carries the OAuth client it is authorized under, so
                  the surface can ask once for Drive, Gmail and Calendar"
          (let [google (filter #(= "google" (:provider %)) rows)]
            (is (<= 3 (count google)))))
        (testing "nothing is connected in a fresh store"
          (is (every? #(false? (:connected? %)) rows)))))))

(deftest the-catalog-separates-having-no-tool-from-having-nothing-to-authorize
  ;; Measured 2026-08-12: GitHub carries two enabled tools and no OAuth client
  ;; on this machine, so the picker offered it, the first Bot was created with
  ;; its tools, and the only thing it could ever say was 'connect first' behind
  ;; a button that answers 'OAuth クライアントが未設定です'. The grid was
  ;; filtering on the wrong fact — one it had, rather than the one that decides.
  (with-store
    (fn []
      (with-redefs [identity/provider-config
                    (fn [provider] {:configured? (= :google provider)})]
        (let [rows (bots/catalog nil nil)
              by-provider (group-by :provider rows)]
          (testing "a provider with a client is offerable"
            (is (seq (get by-provider "google")))
            (is (every? :authable? (get by-provider "google"))))
          (testing "a provider without one is reported, not silently offered"
            (is (seq (get by-provider "github")))
            (is (every? #(false? (:authable? %)) (get by-provider "github"))))
          (testing "and the two reasons stay distinguishable, because an
                    operator fixes them in different places"
            (let [github (first (get by-provider "github"))]
              (is (pos? (:enabled-tool-count github))
                  "this row is unofferable for the client, NOT for its tools —
                   collapsing the two would send somebody to the wrong screen"))))))))

;; ── more than one account at one provider ───────────────────────────────

(deftest one-account-is-used-without-asking
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {})
            calls (atom [])]
        ;; The turn must get past the connection gate and reach the model. It
        ;; is redefined rather than reached: this test is about account
        ;; resolution, and a network call would make it about something else.
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [_ request] (swap! calls conj request)
                        {:content "見ました。" :tool-calls []})]
          (bots/send! nil alice (:bot/id b) "受信箱を見て"))
        (is (= 1 (count @calls)) "one account, so no question was asked")
        (is (seq (:tools (first @calls)))
            "and the Gmail tools reached the model")))))

(deftest two-accounts-at-one-provider-are-asked-about-rather-than-guessed
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (connect! "conn-2" :google "sub-2" "work@example.com")
      (let [b (make-bot alice {})
            ran (atom [])]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn (reaches-for "gmail_search_messages")
                      identity/connection-access-token!
                      (fn [c] (swap! ran conj (:id c)) "t")]
          (let [messages (bots/send! nil alice (:bot/id b) "受信箱を見て")
                card (first (:cards (last messages)))]
            (is (empty? @ran)
                "no token was resolved, so no account was silently picked")
            (is (= "choice" (:kind card)))
            (is (= "account" (get-in card [:subject :kind])))
            (is (= ["A" "B"] (mapv :key (:options card))))
            (is (= #{"jun@example.com" "work@example.com"}
                   (set (map :label (:options card))))
                "the options name the accounts, not their position")

            (testing "answering binds the choice, and the next turn does not ask again"
              (bots/answer! nil alice (:bot/id b) (:id card) "B")
              (let [seen (atom nil)]
                (with-redefs [policy/select-provider
                              (fn [_ _] {:id :local})
                              provider/agent-turn
                              (fn [_ _] {:content "見ました。" :tool-calls []})
                              identity/connection-access-token!
                              (fn [connection] (reset! seen (:id connection)) "t")]
                  (bots/send! nil alice (:bot/id b) "もう一度")
                  ;; The token port is built with the chosen connection, so
                  ;; asking it for a Gmail token must reach conn-2 and nothing
                  ;; else. This is the assertion the whole feature exists for.
                  (let [tokens (bots/tokens-port
                                nil (:selection (#'bots/resolve-accounts
                                                 nil (#'bots/bot-by-id (:bot/id b))
                                                 "did:key:alice")))]
                    (cports/-token tokens 'com.google.gmail)
                    (is (= "conn-2" @seen))))))))))))

(deftest a-bot-bound-to-one-account-does-not-inherit-the-other
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (connect! "conn-2" :google "sub-2" "work@example.com")
      (let [b (make-bot alice {:accounts ["conn-1"]})
            resolved (#'bots/resolve-accounts nil (#'bots/bot-by-id (:bot/id b))
                                              "did:key:alice")]
        (is (empty? (:blocked resolved))
            "bound to exactly one, so there is nothing to ask")
        (is (= "conn-1" (:id (get (:selection resolved) :google))))))))

(deftest an-account-label-is-a-nickname-and-must-be-unique
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (connect! "conn-2" :google "sub-2" "work@example.com")
      (is (= "jun@example.com" (:label (first (identity/accounts-for "did:key:alice"))))
          "the default is the email, not a position — 'the second one' stops
           being true the moment the first is disconnected")
      (bots/label-account! alice "conn-2" "work")
      (is (= #{"jun@example.com" "work"}
             (set (map :label (identity/accounts-for "did:key:alice")))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"同じ名前"
                            (bots/label-account! alice "conn-1" "work"))))))

(deftest tokens-resolve-by-connection-rather-than-by-provider
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [asked (atom [])]
        (with-redefs [identity/connection-access-token!
                      (fn [connection] (swap! asked conj (:id connection)) "token-1")]
          (let [connection (identity/connection-by-id "did:key:alice" "conn-1")
                tokens (bots/tokens-port nil {:google connection})]
            (is (= "token-1" (cports/-token tokens 'com.google.gmail)))
            (is (= "token-1" (cports/-token tokens 'com.google.drive)))
            (is (= ["conn-1" "conn-1"] @asked)
                "two connectors, one account \u2014 and the token is resolved by
                 connection id, not by the provider name that stops identifying
                 anything once there are two accounts")
            (testing "a connector with no selected account resolves to nothing,
                      which connector.invoke turns into a value the Bot can read"
              (is (nil? (cports/-token tokens 'com.github))))))))))

(def ^:private browser-on {:agent-control {:browser {:enabled? true}}})

(defn- execute-tool-var []
  (ns-resolve 'cloud.itonami.app.agent-control 'execute-tool!))

(deftest a-bot-that-asked-for-the-browser-gets-the-isolated-tools
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {:browser? true})
            seen (atom nil)]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [_ request] (reset! seen request)
                        {:content "見ました。" :tool-calls []})]
          (bots/send! browser-on alice (:bot/id b) "ページを見て"))
        (is (some #(= "browser_snapshot" (:name %)) (:tools @seen)))
        (is (some #(= "browser_open" (:name %)) (:tools @seen)))
        (is (not-any? #(str/starts-with? (:name %) "computer_") (:tools @seen))
            "computer-use is not a Bot tool")
        (is (not (contains? (:bot/tools (#'bots/bot-by-id (:bot/id b)))
                            "browser_snapshot"))
            "browser tools stay off the connector grant, so grant-widens? does not fire")
        (let [shown (first (:bots (bots/overview browser-on alice)))]
          (is (true? (:browser? shown)))
          (is (true? (:browser-ready? shown)))
          (is (true? (:browser-available? (bots/overview browser-on alice)))))))))

(deftest a-bot-without-the-browser-does-not-see-browser-tools
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {})
            seen (atom nil)]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [_ request] (reset! seen request)
                        {:content "見ました。" :tool-calls []})]
          (bots/send! browser-on alice (:bot/id b) "受信箱を見て"))
        (is (not-any? #(str/starts-with? (:name %) "browser_") (:tools @seen)))
        (is (seq (:tools @seen)) "Gmail tools still reach the model")))))

(deftest a-bot-that-asked-for-the-browser-on-a-machine-that-has-it-off-does-not-grow-tools
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {:browser? true})
            seen (atom nil)]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [_ request] (reset! seen request)
                        {:content "見ました。" :tool-calls []})]
          (bots/send! nil alice (:bot/id b) "ページを見て"))
        (is (not-any? #(str/starts-with? (:name %) "browser_") (:tools @seen)))
        (let [shown (first (:bots (bots/overview nil alice)))]
          (is (true? (:browser? shown)) "the field stays")
          (is (false? (:browser-ready? shown)) "the tools do not appear")
          (is (false? (:browser-available? (bots/overview nil alice)))))))))

(deftest browser-open-is-held-for-approval
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {:browser? true})
            executed (atom [])]
        (with-redefs-fn {(execute-tool-var)
                         (fn [_ name input]
                           (swap! executed conj [name input])
                           "opened")}
          (fn []
            (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                          provider/agent-turn
                          (fn [_ _]
                            {:content "開きます。"
                             :tool-calls [{:id "c1" :name "browser_open"
                                           :input {:url "https://example.com"}}]})]
              (let [messages (bots/send! browser-on alice (:bot/id b) "example.com を開いて")
                    card (first (:cards (last messages)))]
                (is (empty? @executed)
                    "call-browser-tool! must not run until the person approves")
                (is (= "approval" (:kind card)))
                (is (= "browser_open" (:action card)))
                (is (str/includes? (str (:impact card)) "分離ブラウザー"))))))))))

(deftest browser-snapshot-runs-without-hold
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {:browser? true})
            executed (atom [])
            turns (atom 0)]
        (with-redefs-fn {(execute-tool-var)
                         (fn [_ name input]
                           (swap! executed conj {:session agent-control/*browser-session*
                                                 :name name :input input})
                           "tree")}
          (fn []
            (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                          provider/agent-turn
                          (fn [_ _]
                            (let [n (swap! turns inc)]
                              (if (= 1 n)
                                {:content ""
                                 :tool-calls [{:id "c1" :name "browser_snapshot" :input {}}]}
                                {:content "見ました。" :tool-calls []})))]
              (let [messages (bots/send! browser-on alice (:bot/id b) "ページを見て")]
                (is (= 1 (count @executed)))
                (is (= "browser_snapshot" (:name (first @executed))))
                (is (= (agent-control/session-for (:bot/id b))
                       (:session (first @executed))))
                (is (not-any? #(= "approval" (:kind %))
                              (mapcat :cards messages)))))))))))

(deftest approving-a-browser-write-runs-it-in-the-bots-profile
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [b (make-bot alice {:browser? true})
            executed (atom [])
            turns (atom 0)]
        (with-redefs-fn {(execute-tool-var)
                         (fn [_ name input]
                           (swap! executed conj {:session agent-control/*browser-session*
                                                 :name name :input input})
                           "opened")}
          (fn []
            (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                          provider/agent-turn
                          (fn [_ _]
                            (let [n (swap! turns inc)]
                              (if (= 1 n)
                                {:content "開きます。"
                                 :tool-calls [{:id "c1" :name "browser_open"
                                               :input {:url "https://example.com"}}]}
                                {:content "開きました。" :tool-calls []})))]
              (let [card (first (:cards (last (bots/send! browser-on alice (:bot/id b)
                                                          "example.com を開いて"))))]
                (is (empty? @executed))
                (bots/decide! browser-on alice (:bot/id b) (:id card) "approved")
                (is (= 1 (count @executed)))
                (is (= "browser_open" (:name (first @executed))))
                (is (= (agent-control/session-for (:bot/id b))
                       (:session (first @executed))))))))))))

(deftest call-browser-tool-binds-the-profile-to-the-bot
  (with-store
    (fn []
      (let [seen (atom [])]
        (with-redefs-fn {(execute-tool-var)
                         (fn [_ name _]
                           (swap! seen conj [agent-control/*browser-session* name])
                           "ok")}
          (fn []
            (agent-control/call-browser-tool! browser-on "bot-a" "browser_snapshot" {})
            (agent-control/call-browser-tool! browser-on "bot-b" "browser_snapshot" {})))
        (is (= [(agent-control/session-for "bot-a")
                (agent-control/session-for "bot-b")]
               (mapv first @seen)))
        (is (not= (ffirst @seen) (first (second @seen))))))))

(deftest call-browser-tool-refuses-computer-use-and-a-disabled-browser
  (with-store
    (fn []
      (try
        (agent-control/call-browser-tool! browser-on "bot-a" "computer_click"
                                          {:x 1 :y 1 :application "Safari"})
        (is false "computer_click must not be callable as a browser tool")
        (catch clojure.lang.ExceptionInfo e
          (is (= :agent/unknown-tool (:type (ex-data e))))))
      (try
        (agent-control/call-browser-tool! {} "bot-a" "browser_open"
                                          {:url "https://example.com"})
        (is false "a disabled browser must not execute")
        (catch clojure.lang.ExceptionInfo e
          (is (= :agent/browser-disabled (:type (ex-data e)))))))))
