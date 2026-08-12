(ns cloud.itonami.app.bots-test
  "The host: ownership, the connection gate, and the refusal that has to hold
  at the route rather than only in the contract.

  Nothing here calls a model or reaches the network. The two places that would
  — `advance!` and `run-tool!` — are behind the connection gate, and every test
  that would cross it redefines the seam instead."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest a-bot-with-nothing-connected-asks-before-it-plans
  (with-store
    (fn []
      ;; No OAuth connection exists in this store, so the Bot's whole grant is
      ;; unreachable. The turn must stop at the connection card — a model call
      ;; here would produce a plan whose every step is fiction.
      (let [b (make-bot alice {})
            messages (bots/send! nil alice (:bot/id b) "受信箱を見て")
            last-message (last messages)
            cards (:cards last-message)]
        (is (= "bot" (:role last-message)))
        (is (seq cards))
        (is (= "connection" (:kind (first cards))))
        (is (= "google" (:connector (first cards))))
        (testing "the card names the scopes, because 'connect Gmail' and
                  'connect Gmail so something can read and send your mail' are
                  different requests"
          (is (seq (:scopes (first cards)))))
        (testing "and the Bot reports itself as waiting for a connection"
          (is (= "waiting-connection"
                 (:status (first (:bots (bots/overview nil alice)))))))))))

(deftest a-card-does-not-offer-an-authorization-this-machine-cannot-perform
  ;; A Bot can hold tools for a provider with no client — it was given them
  ;; before anyone checked, or the client went away since. The card still has
  ;; to appear, because the Bot really is blocked on it. What it must not do is
  ;; carry a button whose only outcome is the error.
  (with-store
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

(deftest an-archived-bot-keeps-its-conversation-and-stops-working
  (with-store
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
