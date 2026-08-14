(ns cloud.itonami.app.peer-kotoba-parity-test
  "Named durable Bots as persistent peers, in .kotoba and through the host.

  Messaging is the shape in which a permission system is usually defeated
  without looking like delegation: ask a Bot that has the tool. Two properties
  are the whole defence:

  - a Bot must not approve through a peer. Restated exhaustively over all
    eight combinations of the other three facts.
  - a note must not cross between two people's Bots. `same-owner` is checked
    first and alone.

  A third property is the Grok asymmetry this core exists to name: the
  computer is shared when the owner is the same, and memory is foreign when
  the Bots are distinct. Asserted as a pair, because either one alone is the
  wrong multi-agent (fully shared cognition, or fully isolated work).

  That a peer message does not widen the target's grant is not tested here
  because there is no field for it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.peer :as peer]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/cloud/itonami/app/peer_core.kotoba"))

(def ^:private export-prefix
  "may-message? computer-shared? foreign-memory? may-approve? main")

(def ^:private pair-ty
  (str "[:record :peer/pair [[:same-owner :bool] [:source-enabled :bool] "
       "[:target-enabled :bool] [:distinct-bots :bool]]]"))

(def ^:private decision-ty
  (str "[:record :peer/decision [[:human :bool] [:identified :bool] "
       "[:authorized :bool]]]"))

(defn- run-probes [probes result-type]
  (let [defs (for [[name body] probes]
               (str "(defn " name " [] " result-type " " body ")"))
        src (-> core-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " "
                      (str/join " " (map first probes)) "])"))
                (str "\n" (str/join "\n" defs)))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _]] [n (ir/execute kir (symbol n) [])]) probes))))

(def ^:private rows
  (for [same-owner [true false]
        source-enabled [true false]
        target-enabled [true false]
        distinct-bots [true false]]
    {:same-owner same-owner :source-enabled source-enabled
     :target-enabled target-enabled :distinct-bots distinct-bots}))

(defn- pair-literal [{:keys [same-owner source-enabled target-enabled
                             distinct-bots]}]
  (str "(record-new " pair-ty " " same-owner " " source-enabled " "
       target-enabled " " distinct-bots ")"))

(defn- host-args [{:keys [same-owner source-enabled target-enabled
                          distinct-bots]}]
  [{:bot/id "bot-a" :bot/enabled? source-enabled}
   {:bot/id (if distinct-bots "bot-b" "bot-a") :bot/enabled? target-enabled}
   {:source-owner "person-1"
    :target-owner (if same-owner "person-1" "person-2")}])

(deftest kotoba-and-host-agree-on-peer-judgements
  (doseq [[fn-name export host-fn]
          [["msg" "may-message?" peer/may-message?]
           ["cpu" "computer-shared?" peer/computer-shared?]
           ["mem" "foreign-memory?" peer/foreign-memory?]]]
    (testing export
      (let [probes (map-indexed
                    (fn [i row] [(str fn-name "_" i)
                                 (str "(" export " " (pair-literal row) ")")])
                    rows)
            guest (run-probes probes ":bool")]
        (doseq [[i row] (map-indexed vector rows)]
          (is (= (get guest (str fn-name "_" i)) (apply host-fn (host-args row)))
              (str export " disagreed on " (pr-str row))))))))

(def ^:private decision-rows
  (for [human [true false] identified [true false] authorized [true false]]
    {:human? human :identified? identified :authorized? authorized}))

(deftest an-agent-can-never-approve-through-a-peer-message
  (let [probes (map-indexed
                (fn [i {:keys [human? identified? authorized?]}]
                  [(str "ag_" i)
                   (str "(may-approve? (record-new " decision-ty " "
                        human? " " identified? " " authorized? ") \"agent\")")])
                decision-rows)
        guest (run-probes probes ":bool")]
    (doseq [[i d] (map-indexed vector decision-rows)]
      (is (false? (get guest (str "ag_" i)))
          (str "the core let an agent approve: " (pr-str d)))
      (is (false? (peer/may-approve? (assoc d :actor-kind :agent)))
          (str "the host let an agent approve: " (pr-str d))))))

(deftest a-person-approves-only-with-all-three-facts
  (doseq [d decision-rows]
    (let [expected (boolean (and (:human? d) (:identified? d) (:authorized? d)))]
      (is (= expected (peer/may-approve? (assoc d :actor-kind :person)))
          (str "person approval disagreed on " (pr-str d))))))

(deftest a-note-never-crosses-between-two-peoples-bots
  (doseq [row rows :when (not (:same-owner row))]
    (is (false? (apply peer/may-message? (host-args row)))
        (str "messaged across owners: " (pr-str row)))))

(deftest a-bot-cannot-message-itself
  (doseq [row rows :when (not (:distinct-bots row))]
    (is (false? (apply peer/may-message? (host-args row)))
        (str "admitted a self-message: " (pr-str row)))))

(deftest computer-is-shared-iff-same-owner
  (doseq [row rows]
    (is (= (boolean (:same-owner row))
           (apply peer/computer-shared? (host-args row)))
        (str "computer-shared? disagreed on " (pr-str row)))))

(deftest memory-is-foreign-iff-distinct-bots
  (doseq [row rows]
    (is (= (boolean (:distinct-bots row))
           (apply peer/foreign-memory? (host-args row)))
        (str "foreign-memory? disagreed on " (pr-str row)))))

(deftest an-ordinary-peer-message-is-admitted
  (is (true? (apply peer/may-message?
                    (host-args {:same-owner true :source-enabled true
                                :target-enabled true :distinct-bots true})))))
