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
  (str "may-message? computer-shared? foreign-memory? may-approve? "
       "may-address? reaches-another-machine? main"))

(def ^:private pair-ty
  (str "[:record :peer/pair [[:same-owner :bool] [:source-enabled :bool] "
       "[:target-enabled :bool] [:distinct-bots :bool]]]"))

(def ^:private decision-ty
  (str "[:record :peer/decision [[:human :bool] [:identified :bool] "
       "[:authorized :bool]]]"))

(def ^:private reach-ty
  (str "[:record :peer/reach [[:same-owner :bool] [:target-enabled :bool] "
       "[:device-known :bool] [:device-is-local :bool] [:remote-enabled :bool]]]"))

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

;; ── reaching another machine (ADR-0062) ─────────────────────────────────

(def ^:private reach-rows
  (for [same-owner [true false]
        target-enabled [true false]
        device-known [true false]
        device-is-local [true false]
        remote-enabled [true false]]
    {:same-owner same-owner :target-enabled target-enabled
     :device-known device-known :device-is-local device-is-local
     :remote-enabled remote-enabled}))

(defn- reach-literal [{:keys [same-owner target-enabled device-known
                              device-is-local remote-enabled]}]
  (str "(record-new " reach-ty " " same-owner " " target-enabled " "
       device-known " " device-is-local " " remote-enabled ")"))

(defn- reach-host-args
  "The host takes a Bot and a context, and derives the five facts. Driving it
  through that derivation rather than handing it the record is the point: a
  parity test that fed both sides the same booleans would agree even if
  `->reach` computed the wrong ones."
  [{:keys [same-owner target-enabled device-known device-is-local
           remote-enabled]}]
  [{:bot/id "bot-b" :bot/enabled? target-enabled}
   {:source-owner "person-1"
    :target-owner (if same-owner "person-1" "person-2")
    :local-device "air"
    :device (if device-is-local "air" "studio")
    :known-devices (if device-known ["studio"] [])
    :remote-enabled? remote-enabled}])

(deftest kotoba-and-host-agree-on-where-a-bot-can-be-reached
  (doseq [[fn-name export host-fn]
          [["adr" "may-address?" peer/may-address?]
           ["rem" "reaches-another-machine?" peer/reaches-another-machine?]]]
    (testing export
      (let [probes (map-indexed
                    (fn [i row] [(str fn-name "_" i)
                                 (str "(" export " " (reach-literal row) ")")])
                    reach-rows)
            guest (run-probes probes ":bool")]
        (doseq [[i row] (map-indexed vector reach-rows)]
          (is (= (get guest (str fn-name "_" i))
                 (apply host-fn (reach-host-args row)))
              (str export " disagreed on " (pr-str row))))))))

(deftest a-handle-for-another-persons-bot-is-refused-however-it-is-configured
  ;; Ownership is tested first and alone here as everywhere else on this path.
  ;; Sixteen configurations, none of which reaches a yes.
  (doseq [row reach-rows :when (not (:same-owner row))]
    (is (false? (apply peer/may-address? (reach-host-args row)))
        (str "addressed another person's Bot with " (pr-str row)))))

(deftest the-remote-switch-cannot-turn-off-the-machine-you-are-sitting-at
  ;; The deployment switch is read LAST, and only on the remote branch. If it
  ;; were read earlier -- or unconditionally -- turning remote addressing off
  ;; would have silently stopped local Bots from being addressable, which is
  ;; the kind of outage that looks like the Bots being broken.
  (doseq [row reach-rows
          :when (and (:same-owner row) (:target-enabled row)
                     (:device-is-local row))]
    (is (true? (apply peer/may-address? (reach-host-args row)))
        (str "a local handle was refused with " (pr-str row)))))

(deftest an-unregistered-device-is-not-addressable
  ;; A handle is not a guess. `device-known` is registration on the messenger
  ;; plane -- the device published Signal material under this principal -- and
  ;; without it there is nothing to send to and no way to be sure whose machine
  ;; answered.
  (doseq [row reach-rows
          :when (and (not (:device-known row)) (not (:device-is-local row)))]
    (is (false? (apply peer/may-address? (reach-host-args row)))
        (str "addressed an unregistered device with " (pr-str row)))))

(deftest an-address-round-trips-and-a-malformed-one-is-nil
  (is (= "bot:b1" (peer/address "b1")))
  (is (= "bot:b1" (peer/address "b1" "   ")) "a blank device is the local form")
  (is (= "bot:b1@studio" (peer/address "b1" "studio")))
  (doseq [[bot-id device] [["b1" nil] ["b1" "studio"] ["b-1" "mac-mini.local"]]]
    (is (= {:bot-id bot-id :device device}
           (peer/parse-address (peer/address bot-id device)))))
  ;; nil rather than a partial parse: an address that does not match is not a
  ;; Bot's, and guessing which half was meant is how a message reaches the
  ;; wrong principal.
  (doseq [bad ["b1" "bot:" "bot:b1@" "bot:@studio" "bot:b1@a@b" "user:x"
               "bot:-b1" "" "bot:b1 @studio"]]
    (is (nil? (peer/parse-address bad)) (str "parsed " (pr-str bad)))))
