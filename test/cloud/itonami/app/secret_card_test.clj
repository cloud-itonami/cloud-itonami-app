(ns cloud.itonami.app.secret-card-test
  "The card, end to end: it is offered when a credential is missing, the value
  goes in by one door, and the conversation never carries it.

  The last assertion is the one that matters and the one that is easiest to
  write vacuously. `transcript-mentions?` walks the whole stored conversation —
  every message, every card, every value in every card — and looks for the
  literal token. It is asserted TRUE against a deliberately leaked message
  first, so that the negative assertions afterwards are known to be capable of
  failing."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.cloudflare :as cloudflare]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.domain-tools :as domain-tools]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.secret-request :as secret-request]
            [cloud.itonami.app.secret-store :as secret-store]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.web :as web]
            [cloud.itonami.app.workspace-tools :as workspace-tools]
            [clojure.java.io :as io]))

(def ^:private alice {:user-id "alice" :organization-id "org-1" :kind :passkey})
(def ^:private agent-session
  {:user-id "alice" :organization-id "org-1" :kind :agent})

(def ^:private token "abcdefghij0123456789_-ABCDEFGHIJKLMNOPQR")
(def ^:private account "0123456789abcdef0123456789abcdef")

(defn- with-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-secret-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))
                    store/transact! (fn [f & args]
                                      (apply swap! store/state f args))]
        (f))
      (finally (reset! store/state previous)))))

(defn- domain-steward-entry []
  {:key "cloud-itonami/domain-steward"
   :business {:id :cloud-itonami :name "Cloud Itonami"}
   :role {:id :domain-steward :name "Domain Steward" :job :operations}
   :objective "Inspect the domain portfolio."
   :responsibilities ["Never bypass Passkey"]
   :capabilities [{:capability :domain.read :decision :autonomous}]
   :workspace "orgs/cloud-itonami/cloud-itonami-app"
   :cadence-minutes 15})

(defn- workforce-catalog [roles]
  {:schema "network.awai.workforce-bots.v1"
   :businesses (if (seq roles) 1 0)
   :roles roles
   :source {:path "/registry"}})

(defn- reaches-for [tool]
  (let [turns (atom 0)]
    (fn [_ _]
      (if (= 1 (swap! turns inc))
        {:content "調べます。" :tool-calls [{:id "c1" :name tool :input {}}]}
        {:content "終わりました。" :tool-calls []}))))

(defn- always-reaches-for
  "A model that reaches for `tool` on every turn.

  `reaches-for` stops after one, which is right for a test that asks a Bot to
  do one thing. A test that answers one card and expects the NEXT one has to
  keep reaching, or the second card is never offered and the assertion that
  depends on it passes for the wrong reason."
  [tool]
  (fn [_ _] {:content "調べます。"
             :tool-calls [{:id "c1" :name tool :input {}}]}))

(defn- open-secret-card
  "The last secret card in this Bot's conversation, as stored."
  [bot-id]
  (->> (get-in (store/snapshot) [:bots :conversations bot-id])
       (mapcat :message/cards)
       (filter #(= :secret (:card/kind %)))
       last))

(defn- steward-bot []
  (bots/provision-workforce! {} alice (workforce-catalog [(domain-steward-entry)]))
  (first (:bots (bots/overview {} alice))))

(defn- conversation [bot-id]
  (get-in (store/snapshot) [:bots :conversations bot-id]))

(defn- transcript-mentions?
  "Does the literal `needle` appear anywhere in this Bot's stored conversation?

  `pr-str` over the whole structure rather than a walk over the fields this
  test happens to know about: a field added later would escape a hand-written
  walk, and escaping the check is exactly how a value would get in."
  [bot-id needle]
  (str/includes? (pr-str (conversation bot-id)) needle))

;; A store with nothing in it and no environment: the state a fresh install is
;; in, and the state the failure this whole mechanism answers was measured in.
(defn- without-credentials [f]
  (binding [secret-store/*environment* (constantly nil)
            secret-store/*run* (constantly nil)
            secret-store/*write* (constantly true)
            cloudflare/*environment* (constantly nil)]
    (f)))

(deftest the-check-that-the-other-assertions-rest-on-can-fail
  (with-store
    (fn []
      (without-credentials
       (fn []
         (with-redefs [workspace-tools/admit-root (fn [path] path)
                       domain-tools/answerable? (constantly true)
                       policy/select-provider (fn [_ _] {:id :local})
                       provider/agent-turn (fn [_ _] {:content "はい。" :tool-calls []})]
           (let [b (steward-bot)]
             (bots/send! nil alice (:id b) (str "token is " token))
             (is (true? (transcript-mentions? (:id b) token))
                 "a value said in prose IS in the transcript -- so the negative
                  assertions below are measuring something"))))))))

(deftest a-missing-credential-becomes-a-card-instead-of-a-sentence
  (with-store
    (fn []
      (without-credentials
       (fn []
         (with-redefs [workspace-tools/admit-root (fn [path] path)
                       domain-tools/answerable? (constantly true)
                       policy/select-provider (fn [_ _] {:id :local})
                       provider/agent-turn (reaches-for "domain_registrations")]
           (let [b (steward-bot)]
             (testing "the tool is offered even though it cannot run yet"
               (is (contains? (set (:admitted-tools b)) "domain_registrations")
                   "offered: otherwise the model never reaches for it and
                    nothing ever says what is missing"))
             (let [messages (bots/send! nil alice (:id b) "ドメインを確認して")
                   card (first (:cards (last messages)))]
               (is (= "secret" (:kind card)))
               (is (= "cloudflare-account-id" (:secret card))
                   "the first missing coordinate, one at a time")
               (is (= "requested" (str (:state card))))
               (testing "and the card carries the request, never a value"
                 (is (nil? (:value card)))
                 (is (= (secret-request/public
                         (secret-request/requirement "cloudflare-account-id"))
                        (:requirement card))))
               (testing "the Bot's own sentence points at the field, and promises
                         what the field is for"
                 ;; A sentence that asked for the value would be answered in the
                 ;; composer, which is the failure this whole card replaces.
                 (let [said (:text (last messages))]
                   (is (str/includes? said "下の欄"))
                   (is (str/includes? said "この会話には残しません"))
                   (is (str/includes? said "macOS キーチェーン")
                       "it says where the value goes before asking for it")))))))))))

(deftest a-credential-pasted-into-the-composer-is-refused-and-not-recorded
  (with-store
    (fn []
      (without-credentials
       (fn []
         (with-redefs [workspace-tools/admit-root (fn [path] path)
                       domain-tools/answerable? (constantly true)
                       policy/select-provider (fn [_ _] {:id :local})
                       provider/agent-turn (always-reaches-for "domain_registrations")]
           (let [b (steward-bot)]
             (bots/send! nil alice (:id b) "ドメインを確認して")
             ;; The first open card is for the account id, and an account id is
             ;; not guarded. Answer it so that the card the guard is being
             ;; tested against is the API token's.
             (bots/provide-secret! alice (:id b)
                                   (:card/id (open-secret-card (:id b))) account)
             (binding [secret-store/*run* (fn [argv & _]
                                            (when (some #{"cloudflare-account-id"} argv)
                                              account))]
               (bots/send! nil alice (:id b) "もう一度ドメインを確認して")
               (is (= "cloudflare-api-token"
                      (:card/secret (open-secret-card (:id b))))
                   "the guarded card is open -- without this the refusal below
                    could pass by never having been asked for")
               (let [before (count (conversation (:id b)))]
                 (testing "the paste is refused"
                   (is (thrown-with-msg?
                        clojure.lang.ExceptionInfo #"チャットに貼らないで"
                        (bots/send! nil alice (:id b) token))))
                 (testing "and nothing about it was written"
                   (is (= before (count (conversation (:id b))))
                       "not even the person's message")
                   (is (false? (transcript-mentions? (:id b) token))))
                 (testing "an ordinary message is still accepted"
                   (is (seq (bots/send! nil alice (:id b) "状況を教えて")))))))))))))

(deftest the-value-goes-in-by-one-door-and-does-not-come-back-out
  (with-store
    (fn []
      (without-credentials
       (fn []
         (with-redefs [workspace-tools/admit-root (fn [path] path)
                       domain-tools/answerable? (constantly true)
                       policy/select-provider (fn [_ _] {:id :local})
                       provider/agent-turn (reaches-for "domain_registrations")]
           (let [b (steward-bot)
                 written (atom [])]
             (bots/send! nil alice (:id b) "ドメインを確認して")
             (let [card-id (->> (conversation (:id b))
                                (mapcat :message/cards)
                                (filter #(= :secret (:card/kind %)))
                                last :card/id)]
               (testing "an agent session cannot answer it"
                 (is (thrown-with-msg?
                      clojure.lang.ExceptionInfo #"人が入力します"
                      (bots/provide-secret! agent-session (:id b) card-id account))))
               (testing "a value of the wrong shape is refused"
                 (is (thrown? clojure.lang.ExceptionInfo
                              (bots/provide-secret! alice (:id b) card-id "nope"))))
               (binding [secret-store/*write* (fn [argv & _]
                                                (swap! written conj argv) true)]
                 (let [messages (bots/provide-secret! alice (:id b) card-id account)]
                   (testing "it reached the keychain"
                     (is (= 1 (count @written)))
                     (is (some #{"add-generic-password"} (first @written))))
                   (testing "and not the transcript"
                     (is (false? (transcript-mentions? (:id b) account)))
                     (is (not (str/includes? (pr-str messages) account))))
                   (testing "what the thread says is that it was stored"
                     (is (str/includes? (:text (last messages)) "保存しました")))
                   (testing "the stored card records the locator, not the value"
                     (let [card (->> (conversation (:id b))
                                     (mapcat :message/cards)
                                     (filter #(= :secret (:card/kind %)))
                                     last)]
                       (is (= :stored (:card/state card)))
                       (is (str/starts-with? (:card/stored-ref card) "keychain://"))
                       (is (not (str/includes? (:card/stored-ref card) account)))))))))))))))

(deftest a-card-reports-the-keychain-rather-than-replaying-what-it-said
  (with-store
    (fn []
      (without-credentials
       (fn []
         (with-redefs [workspace-tools/admit-root (fn [path] path)
                       domain-tools/answerable? (constantly true)
                       policy/select-provider (fn [_ _] {:id :local})
                       provider/agent-turn (reaches-for "domain_registrations")]
           (let [b (steward-bot)]
             (bots/send! nil alice (:id b) "ドメインを確認して")
             (letfn [(card [] (->> (bots/messages alice (:id b))
                                   (mapcat :cards)
                                   (filter #(= "secret" (:kind %)))
                                   last))]
               (testing "requested while nothing is stored"
                 (is (= "requested" (str (:state (card))))))
               (testing "stored once the item exists, without the card changing"
                 (binding [secret-store/*run* (constantly account)]
                   (is (= "stored" (str (:state (card)))))
                   (is (= "stored" (:source (card))))))
               (testing "and an exported variable is reported as the variable"
                 (binding [secret-store/*environment*
                           {"CLOUDFLARE_ACCOUNT_ID" account}]
                   (is (= "stored" (str (:state (card)))))
                   (is (= "environment" (:source (card)))
                       "so 入れ直す can say that re-entering changes nothing")))))))))))

(deftest every-catalogue-entry-is-read-by-something
  ;; A catalogue entry nothing reads is a field that stores a value no code
  ;; will ever use — the card would say 保存済み and the tool would go on
  ;; failing. Asserted against the shipped sources because the read is a call
  ;; site rather than a registration: there is nothing to enumerate.
  ;;
  ;; The catalogue's own file is excluded, or every entry would satisfy this by
  ;; being defined.
  (let [sources (->> (file-seq (io/file "src"))
                     (filter #(.isFile ^java.io.File %))
                     (remove #(str/includes? (.getPath ^java.io.File %)
                                             "secret_request"))
                     (map slurp)
                     (str/join "\n"))]
    (is (seq (secret-request/ids)))
    (doseq [id (secret-request/ids)]
      (is (str/includes? sources (str \" id \"))
          (str id " is in the catalogue and nothing reads it")))))

(deftest the-screen-can-render-the-card-it-is-sent
  ;; The server can be right about all of the above and the person still sees
  ;; nothing: a card kind with no branch in `appendBotsCards` renders as an
  ;; empty message. Asserted against the shipped sources, the way this
  ;; repository already asserts its other interaction-layer invariants.
  (let [interaction (slurp (io/resource "cloud/itonami/app/interaction.js"))]
    (is (str/includes? interaction "card.kind === 'secret'")
        "the dispatch has a branch for this kind")
    (is (str/includes? interaction "botsSecretCard"))
    (testing "the field is cleared before the request is awaited"
      (is (str/includes? interaction "input.value = '';")))
    (testing "and the value is never put where the thread state is read from"
      (let [start (str/index-of interaction "const botsSecretCard")
            end (str/index-of interaction "const appendBotsCards")
            body (subs interaction start end)]
        (is (not (str/includes? body "botsState.messages = value")))
        (is (not (str/includes? body "console.log"))))))
  (testing "and the card frame has rules to render with"
    (is (str/includes? web/app-css ".bots-card__input"))
    (is (str/includes? web/app-css ".bots-card__shield"))
    (is (str/includes? web/app-css
                       ".bots-card__state[data-state='stored']"))))
