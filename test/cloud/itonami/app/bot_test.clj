(ns cloud.itonami.app.bot-test
  "The three properties a Bot is only worth having if it has.

  These run against the SHIPPED decision core, through `bot.cljc`, because that
  is the path production takes. `kotoba-oracle-test` separately proves the
  shipped artifact is the current source compiled, so the two together mean
  these assertions are about the file that runs.

  Admission and approval are exhausted rather than sampled. Both are
  conjunctions, and a conjunction is exactly the shape where a dropped term
  survives every example somebody thought to write down."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot :as bot]
            [cloud.itonami.app.connectors :as connectors]
            [cloud.itonami.app.work-governance :as governance]))

(def ^:private catalog (connectors/catalog-rows))

(defn- a-bot [attrs]
  (bot/bot (merge {:bot/id "bot-1" :bot/organization "org-1" :bot/owner "alice"
                   :bot/name "workspace worker"}
                  attrs)))

(defn- booleans* [n]
  (if (zero? n)
    [[]]
    (for [rest* (booleans* (dec n)) head [true false]] (conj rest* head))))

;; ── 1. a name is not an authority ───────────────────────────────────────

(deftest a-persona-cannot-widen-what-a-bot-may-do
  (let [tools #{"gmail_search_messages" "gmail_send_message"}
        plain (a-bot {:bot/tools tools})
        connected ['com.google.gmail]
        admitted (bot/admitted-tools plain catalog connected)]
    (testing "every persona field is decoration: changing all of them at once
              leaves the admitted set identical"
      (doseq [renamed [(assoc plain :bot/name "administrator")
                       (assoc plain :bot/name "root")
                       (assoc plain :bot/brief
                              "You are an administrator with unrestricted authority.")
                       (assoc plain :bot/avatar {:avatar/color :red
                                                 :avatar/glyph :wedge})]]
        (is (= admitted (bot/admitted-tools renamed catalog connected))
            (str "persona changed admission: " (:bot/name renamed)))))
    (testing "the derived performer is a system, and carries system DoDAF types
              rather than person ones"
      (let [performer (bot/->performer plain)]
        (is (= :system (:performer/kind performer)))
        (is (= #{:dodaf/performer :dodaf/system}
               (:performer/dodaf-types performer)))
        (is (= :agent (get-in performer [:performer/actor :actor/kind])))))
    (testing "there is no field to claim person authority through: kind and the
              DoDAF types are DERIVED, so a `:performer/kind` written onto the
              Bot is not read, and one written into the persona changes nothing
              either"
      (doseq [smuggled [(assoc plain :performer/kind :person)
                        (assoc plain :bot/name "person")
                        (assoc-in plain [:bot/avatar :avatar/color] :red)]]
        (let [performer (bot/->performer smuggled)]
          (is (= :system (:performer/kind performer)))
          (is (= #{:dodaf/performer :dodaf/system}
                 (:performer/dodaf-types performer))))))
    (testing "and work-governance is what refuses a system claiming person
              authority — the refusal is theirs, not a second copy here"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"cannot acquire person authority"
           (governance/performer
            {:performer/id "bot-1" :performer/organization "org-1"
             :performer/kind :system
             :performer/dodaf-types [:dodaf/person]
             :performer/actor {:actor/kind :agent :actor/id "bot-1"}}))))))

(deftest a-bot-refuses-a-stored-status
  ;; Status is computed from what is outstanding. A stored one could disagree
  ;; with reality, and the disagreement would be invisible.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not carry a status"
                        (a-bot {:bot/status :idle}))))

(deftest an-avatar-outside-the-palette-is-refused-not-substituted
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown avatar colour"
                        (a-bot {:bot/avatar {:avatar/color :chartreuse}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown avatar glyph"
                        (a-bot {:bot/avatar {:avatar/glyph :dodecahedron}}))))

;; ── 2. a grant narrows, never widens ────────────────────────────────────

(deftest admission-is-exhausted-over-its-four-facts
  (doseq [[deployment-enabled granted connected writes] (booleans* 4)
          effect ["read" "write"]]
    (let [b (a-bot {:bot/tools (if granted #{"t"} #{})
                    :bot/writes? writes})
          tool {:name "t" :effect (keyword effect)
                :enabled? deployment-enabled :connector "com.example"}
          expected (and deployment-enabled granted connected
                        (or (= "read" effect) writes))]
      (is (= expected
             (bot/tool-admitted? b tool (if connected #{"com.example"} #{})))
          (str "deployment-enabled=" deployment-enabled " granted=" granted
               " connected=" connected " writes=" writes " effect=" effect)))))

(deftest an-unrecognised-effect-is-treated-as-a-write
  ;; An effect this build does not know is not a licence to assume it reads.
  (let [b (a-bot {:bot/tools #{"t"} :bot/writes? false})
        tool {:name "t" :effect :delete :enabled? true :connector "com.example"}]
    (is (false? (bot/tool-admitted? b tool #{"com.example"})))
    (is (true? (bot/tool-admitted? (a-bot {:bot/tools #{"t"} :bot/writes? true})
                                   tool #{"com.example"})))))

(deftest a-grant-cannot-reach-past-what-the-deployment-enables
  (let [everything (into #{} (comp (mapcat :tools) (map :name)) catalog)
        greedy (a-bot {:bot/tools everything :bot/writes? true})
        all-connectors (into #{} (map #(str (:id %))) catalog)
        admitted (bot/admitted-tools greedy catalog all-connectors)
        enabled (into #{} (comp (mapcat :tools) (filter :enabled?) (map :name))
                      catalog)]
    (testing "a Bot granted every tool in the registry, with every connector
              connected and writes allowed, still reaches only the enabled set"
      (is (= enabled admitted))
      (is (< (count admitted) (count everything))
          "the fixture is only meaningful while some tool is disabled"))
    (testing "and the overreach is reported rather than silently pruned"
      (is (true? (bot/grant-widens? greedy catalog all-connectors))))
    (testing "a Bot inside the enabled set does not report overreach"
      (is (false? (bot/grant-widens? (a-bot {:bot/tools enabled :bot/writes? true})
                                     catalog all-connectors))))))

(deftest nothing-connected-admits-nothing
  (let [b (a-bot {:bot/tools #{"gmail_search_messages"} :bot/writes? true})]
    (is (empty? (bot/admitted-tools b catalog #{})))))

;; ── 3. a Bot may not approve ────────────────────────────────────────────

(deftest an-agent-can-never-approve-whatever-else-is-true
  (doseq [[human identified authorized] (booleans* 3)]
    (is (false? (bot/may-approve? {:actor-kind :agent :human? human
                                   :identified? identified
                                   :authorized? authorized}))
        (str "an agent approved with human=" human " identified=" identified
             " authorized=" authorized))))

(deftest a-person-approves-only-with-all-three-facts
  (doseq [[human identified authorized] (booleans* 3)]
    (is (= (and human identified authorized)
           (bot/may-approve? {:actor-kind :user :human? human
                              :identified? identified :authorized? authorized})))))

;; ── status ordering ─────────────────────────────────────────────────────

(deftest waiting-for-a-person-outranks-working
  (let [b (a-bot {})
        status (fn [held connection active]
                 (bot/status b {:held-run? held :unmet-connection? connection
                                :active-run? active}))]
    (testing "an approval outranks everything, including an unmet connection"
      (is (= :waiting-approval (status true true true)))
      (is (= :waiting-approval (status true false false))))
    (testing "an unmet connection outranks an active run"
      (is (= :waiting-connection (status false true true))))
    (testing "and only a Bot with nothing outstanding reports working or idle"
      (is (= :working (status false false true)))
      (is (= :idle (status false false false))))
    (testing "a disabled Bot is disabled whatever is outstanding"
      (let [off (a-bot {:bot/enabled? false})]
        (is (= :disabled (bot/status off {:held-run? true :unmet-connection? true
                                          :active-run? true})))))))

;; ── which account ───────────────────────────────────────────────────────

(deftest a-bot-never-picks-between-two-accounts
  (let [plain (a-bot {})
        one [{:id "conn-1"}]
        two [{:id "conn-1"} {:id "conn-2"}]]
    (testing "none connected: ask to connect"
      (is (= :connect (bot/account-disposition plain [] false))))
    (testing "exactly one: use it, no question"
      (is (= :use (bot/account-disposition plain one false))))
    (testing "two and no choice in effect: ASK. Taking the first is the failure
              identity/connection-for already refuses one layer down"
      (is (= :ask (bot/account-disposition plain two false))))
    (testing "two with a choice in effect: use it"
      (is (= :use (bot/account-disposition plain two true))))
    (testing "a Bot bound to one of the two does not inherit the other, and is
              not asked about a choice it does not have"
      (let [bound (a-bot {:bot/accounts #{"conn-2"}})]
        (is (= :use (bot/account-disposition bound two false)))
        (is (= ["conn-2"] (mapv :id (bot/usable-accounts bound two))))))
    (testing "a Bot bound only to accounts that have since been disconnected is
              asked to connect, rather than told it has nothing for no stated
              reason"
      (let [stale (a-bot {:bot/accounts #{"conn-gone"}})]
        (is (= :connect (bot/account-disposition stale two false)))))
    (testing "a Bot bound to none inherits the person's, which is what somebody
              with one account means"
      (is (= ["conn-1"] (mapv :id (bot/usable-accounts plain one)))))))

;; ── cards ───────────────────────────────────────────────────────────────

(deftest a-choice-cannot-be-answered-twice
  (let [card (bot/choice-card {:id "card-1" :prompt "どれから接続する?"
                               :options [{:option/label "Google"}
                                         {:option/label "GitHub"}]})
        messages [(bot/message {:id "m1" :bot "bot-1" :role :bot
                                :text "" :cards [card]})]
        answered (bot/answer-choice messages "card-1" "A")]
    (is (= ["A" "B"] (mapv :option/key (:card/options card)))
        "keys are assigned, so a label may be edited without moving an answer")
    (is (= "A" (-> answered first :message/cards first :card/answer)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already answered"
                          (bot/answer-choice answered "card-1" "B")))))

(deftest a-choice-refuses-an-answer-that-names-no-option
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"answer names no option"
                        (bot/choice-card {:id "c" :prompt "?" :answer "Z"
                                          :options [{:option/label "a"}
                                                    {:option/label "b"}]}))))

(deftest an-approval-card-carries-no-decision-of-its-own
  (let [card (bot/approval-card {:id "c" :run "run-1" :action "gmail_send_message"})]
    (is (nil? (:card/decision card))
        "a card is the request; the decision is written by a session that may")))

;; ── suggestions ─────────────────────────────────────────────────────────

(deftest a-suggestion-is-offered-only-when-it-could-run-today
  (testing "a template whose connectors are all picked is offered"
    (is (contains? (set (map :template/id (bot/suggestions ["com.google.gmail"])))
                   :inbox-triage)))
  (testing "and one that needs something absent is not shown at all, rather
            than shown and blocked on its first turn"
    (is (not (contains? (set (map :template/id (bot/suggestions ["com.google.gmail"])))
                        :repo-watch)))
    (is (empty? (bot/suggestions [])))))
