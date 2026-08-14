(ns cloud.itonami.app.bots-test
  "The host: ownership, the connection card, and the refusal that has to hold
  at the route rather than only in the contract.

  Connector OAuth is not a door on the conversation. Tests that would reach a
  model redefine `provider/agent-turn` instead of calling the network."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.messenger :as messenger]
            [cloud.itonami.app.peer :as peer]
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

(defn- with-model [f]
  (with-redefs [policy/select-provider (fn [_ _] {:id :local :name "local"})
                provider/agent-turn (fn [_ _] {:content "はい。" :tool-calls []})]
    (f)))

(deftest a-bot-talks-through-the-model-before-connectors-are-connected
  (with-store
    (fn []
      ;; A named Bot is a conversation partner. Connector OAuth is for tools,
      ;; not for being allowed to speak. The card is still offered so a later
      ;; Gmail call has somewhere to go.
      (let [b (make-bot alice {})
            calls (atom [])]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local :name "local"})
                      provider/agent-turn
                      (fn [_ request] (swap! calls conj request)
                        {:content "受信箱はまだ繋げていません。" :tool-calls []})]
          (let [messages (bots/send! nil alice (:bot/id b) "受信箱を見て")
                last-message (last messages)
                cards (:cards last-message)
                system (get-in (first @calls) [:messages 0 :content])]
            (is (= 1 (count @calls)) "the model ran without a Google grant")
            (is (str/includes? (str system) "workspace worker")
                "the Bot's name is in the system prompt")
            (is (some #(= "bot" (:role %)) messages))
            (is (seq cards))
            (is (= "connection" (:kind (first cards))))
            (is (= "google" (:connector (first cards))))
            (is (seq (:scopes (first cards))))
            (is (= "waiting-connection"
                   (:status (first (:bots (bots/overview nil alice))))))))))))

(deftest a-card-does-not-offer-an-authorization-this-machine-cannot-perform
  ;; A Bot can hold tools for a provider with no client — it was given them
  ;; before anyone checked, or the client went away since. The card still has
  ;; to appear, because the Bot really is blocked on it. What it must not do is
  ;; carry a button whose only outcome is the error.
  (with-store
    (fn []
      (with-model
        (fn []
          (with-redefs [identity/provider-config (fn [_] {:configured? false})]
            (let [b (make-bot alice {})
                  cards (:cards (last (bots/send! nil alice (:bot/id b) "受信箱を見て")))
                  card (first cards)]
              (is (= "connection" (:kind card)))
              (is (false? (:authable? card)))))
          (with-redefs [identity/provider-config (fn [_] {:configured? true})]
            (let [b (make-bot alice {:name "second"})
                  card (first (:cards (last (bots/send! nil alice (:bot/id b) "見て"))))]
              (testing "and stays offerable where the client does exist"
                (is (true? (:authable? card)))))))))))

(deftest a-stored-card-reports-the-client-this-machine-has-now
  ;; Measured 2026-08-12: cards live inside messages, so the first fix reached
  ;; only cards written after it. The Bot already on this machine kept showing
  ;; 認証する, because its card predated the field entirely. Whether a provider
  ;; can be authorized is the state of the installation now, not something the
  ;; conversation said once — so the read path answers it, and a card written
  ;; under either condition follows the machine.
  (with-store
    (fn []
      (with-model
        (fn []
          (let [b (with-redefs [identity/provider-config (fn [_] {:configured? true})]
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
                             (first (:cards (last (bots/messages alice (:bot/id b))))))))))))))))

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

(deftest an-archived-bot-keeps-its-conversation-and-stops-working
  (with-store
    (fn []
      (with-model
        (fn []
          (let [b (make-bot alice {})]
            (bots/send! nil alice (:bot/id b) "おはよう")
            (let [before (count (bots/messages alice (:bot/id b)))]
              (bots/archive! alice (:bot/id b))
              (is (= "disabled" (:status (first (:bots (bots/overview nil alice))))))
              (is (= before (count (bots/messages alice (:bot/id b))))
                  "archiving took the record along with the ability, and only the
                   second was asked for")
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"停止しています"
                                    (bots/send! nil alice (:bot/id b) "まだ動く?"))))))))))

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
            reached (atom 0)]
        (with-redefs [provider/agent-turn
                      (fn [_ _] (swap! reached inc) {:content "" :tool-calls []})]
          (let [messages (bots/send! nil alice (:bot/id b) "受信箱を見て")
                card (first (:cards (last messages)))]
            (is (zero? @reached)
                "the model must not be asked to plan before the account is known")
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
        (is (empty? (:ask resolved))
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
                (is (str/includes? (str (:impact card)) "共有コンピューター"))))))))))

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
                                                 :screen agent-control/*browser-screen*
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
                (is (= (agent-control/computer-for (:user-id alice))
                       (:session (first @executed))))
                (is (= (agent-control/screen-for (:bot/id b))
                       (:screen (first @executed))))
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
                                                 :screen agent-control/*browser-screen*
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
                (is (= (agent-control/computer-for (:user-id alice))
                       (:session (first @executed))))
                (is (= (agent-control/screen-for (:bot/id b))
                       (:screen (first @executed))))))))))))

(deftest call-browser-tool-binds-the-owners-computer-and-the-bots-screen
  (with-store
    (fn []
      (let [seen (atom [])]
        (with-redefs-fn {(execute-tool-var)
                         (fn [_ name _]
                           (swap! seen conj {:session agent-control/*browser-session*
                                             :screen agent-control/*browser-screen*
                                             :name name})
                           "ok")}
          (fn []
            (agent-control/call-browser-tool!
             browser-on {:owner "alice" :bot "bot-a"} "browser_snapshot" {})
            (agent-control/call-browser-tool!
             browser-on {:owner "alice" :bot "bot-b"} "browser_snapshot" {})))
        (is (= (agent-control/computer-for "alice")
               (:session (first @seen))
               (:session (second @seen)))
            "same owner, same computer")
        (is (not= (:screen (first @seen)) (:screen (second @seen)))
            "each Bot has its own screen")
        (is (= [(agent-control/screen-for "bot-a")
                (agent-control/screen-for "bot-b")]
               (mapv :screen @seen)))))))

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

(deftest same-owner-bots-share-a-computer-and-not-memory
  (with-store
    (fn []
      (let [research (make-bot alice {:name "research"})
            review (make-bot alice {:name "review"})]
        (is (peer/computer-shared? research review
                                   {:source-owner "alice" :target-owner "alice"}))
        (is (peer/foreign-memory? research review
                                  {:source-owner "alice" :target-owner "alice"}))
        (bots/remember! alice (:bot/id research) "only the researcher knows this")
        (is (= ["only the researcher knows this"]
               (map :text (bots/memories alice (:bot/id research)))))
        (is (empty? (bots/memories alice (:bot/id review))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"別の Bot の記憶"
                              (bots/memory-of review research)))
        (is (= 1 (count (bots/memory-of research research))))
        (is (.isDirectory (java.io.File. (bots/computer-dir "alice") "files")))))))

(deftest a-peer-message-is-not-a-grant-and-wakes-an-idle-bot
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [research (make-bot alice {:name "research"
                                      :tools ["gmail_search_messages"]})
            review (make-bot alice {:name "review"
                                    :tools ["gmail_search_messages" "gmail_send_message"]})
            turns (atom 0)]
        (is (not= (:bot/tools research) (:bot/tools review)))
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [_ _]
                        (swap! turns inc)
                        {:content "見ました。" :tool-calls []})]
          (let [result (bots/peer-message! nil alice (:bot/id research)
                                           (:bot/id review) "この下書きを見て")]
            (is (true? (:accepted? result)))
            (is (true? (:computer-shared? result)))
            (is (pos? @turns) "the idle reviewer is woken")
            (is (some #(and (= "research" (:from %))
                            (str/includes? (str (:text %)) "research"))
                       (bots/messages alice (:bot/id review)))
                "the person can see the handoff in the reviewer's 1:1")
            (is (some #(str/includes? (str (:text %)) "review へ")
                       (bots/messages alice (:bot/id research)))
                "the sender's 1:1 also records the outbound peer note")
            (let [principals (bots/mailbox-principals "org-1" "alice")
                  overview (messenger/overview "org-1" "alice" principals)
                  group (first (filter #(= "group" (:kind %))
                                       (:conversations overview)))]
              (is (some? group) "the person is a member of the peer group")
              (is (str/includes? (str (:title group)) "↔"))
              (is (= #{ "alice"
                       (str "bot:" (:bot/id research))
                       (str "bot:" (:bot/id review))}
                     (set (:members group))))
              (is (some #(str/includes? (str (:content %)) "この下書きを見て")
                        (:items (messenger/messages "org-1" "alice"
                                                    (:id group) principals)))
                  "the group body is visible to the person, not quarantined"))))
        (is (= #{"gmail_search_messages"} (:bot/tools research))
            "messaging did not copy the sender a send grant")
        (is (contains? (:bot/tools review) "gmail_send_message"))))))

(deftest a-peer-group-is-filed-under-the-messenger-slug
  (with-store
    (fn []
      (store/transact!
       (fn [state]
         (assoc-in state [:identity :organizations "org-record"]
                   {:id "org-record" :organization-id "acme"})))
      (let [session (assoc alice :organization-id "org-record")
            research (make-bot session {:name "research"})
            review (make-bot session {:name "review"})]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn (fn [_ _] {:content "見ました。" :tool-calls []})]
          (bots/peer-message! nil session (:bot/id research)
                              (:bot/id review) "見て"))
        (let [principals (bots/mailbox-principals "org-record" "alice")
              overview (messenger/overview "acme" "alice" principals)]
          (is (some #(= "group" (:kind %)) (:conversations overview))
              "Messenger indexes the slug, so the person can open the group"))))))

(deftest a-peer-message-does-not-cross-owners
  (with-store
    (fn []
      (let [mine (make-bot alice {:name "mine"})
            theirs (make-bot bob {:name "theirs"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"このセッションのもの"
                              (bots/peer-message! nil alice (:bot/id mine)
                                                  (:bot/id theirs) "hello")))
        (is (false? (peer/may-message? mine theirs
                                       {:source-owner "alice"
                                        :target-owner "bob"})))))))

(deftest send-message-tool-talks-to-a-peer
  (with-store
    (fn []
      (connect! "conn-1" :google "sub-1" "jun@example.com")
      (let [research (make-bot alice {:name "research"})
            review (make-bot alice {:name "review"})
            turns (atom 0)]
        (with-redefs [policy/select-provider (fn [_ _] {:id :local})
                      provider/agent-turn
                      (fn [_ request]
                        (let [n (swap! turns inc)]
                          (cond
                            (and (= 1 n)
                                 (some #(= "send_message" (:name %))
                                       (:tools request)))
                            (do
                              (is (str/includes? (str (get-in request [:messages 0 :content]))
                                                 "You are research"))
                              (is (str/includes? (str (get-in request [:messages 0 :content]))
                                                 "review")
                                  "the peer's name is in the sender's context")
                              {:content ""
                               :tool-calls [{:id "c1" :name "send_message"
                                             :input {:to (:bot/id review)
                                                     :text "review this"}}]})
                            :else
                            {:content "done" :tool-calls []})))]
          (bots/send! nil alice (:bot/id research) "レビューして")
          (is (some #(str/includes? (str (:text %)) "メッセージ")
                    (bots/messages alice (:bot/id review)))))))))
