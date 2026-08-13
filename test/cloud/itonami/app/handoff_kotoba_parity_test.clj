(ns cloud.itonami.app.handoff-kotoba-parity-test
  "One Bot giving work to another, in .kotoba and through the host.

  ## What is actually at risk

  Delegation is the shape in which a permission system is usually defeated, and
  two properties are the whole defence:

  - a Bot must not approve through a proxy. `bot_core` refuses to let an agent
    approve; if this path does not refuse identically, the way around it is to
    hand the held run to a second Bot and have that one say yes. Asserted
    exhaustively over all eight combinations of the other three facts, because
    the refusal is worth nothing if any of them can trade against it.
  - work must not cross between two people's Bots. `same-owner` is checked
    first and alone for the same reason: a tool a Bot should not have had is a
    bad afternoon; someone else's Bot acting on your data is an incident.

  A third property is about termination rather than authority: a chain of Bots
  that answer by delegating does not stop on its own, so `next-depth` is
  asserted to strictly increase and `admitted?` to refuse at the ceiling. That
  pair is what makes a ring terminate, which is why the core has no separate
  cycle detector and this test asserts the pair rather than looking for one.

  ## What is NOT tested here, because it cannot be

  That a handoff does not widen the target's grant. There is no test for it
  because there is no field for it: `:handoff/request` carries no tool, grant,
  scope or account, so there is no value to assert about. The absence is
  checked by reading `->request`, and the test that would fail if somebody
  added one is the record-shape mismatch the compiler raises."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.handoff :as handoff]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/cloud/itonami/app/handoff_core.kotoba"))

(def ^:private export-prefix
  "budget-exhausted? admitted? may-approve? next-depth main")

(def ^:private request-ty
  (str "[:record :handoff/request [[:same-owner :bool] [:source-enabled :bool] "
       "[:target-enabled :bool] [:distinct-bots :bool] [:depth :i64] "
       "[:max-depth :i64]]]"))

(def ^:private decision-ty
  (str "[:record :handoff/decision [[:human :bool] [:identified :bool] "
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

;; ── the corpus ───────────────────────────────────────────────────────

(def ^:private rows
  "All four booleans against the depth boundary: under, at, and a zero ceiling."
  (for [same-owner [true false]
        source-enabled [true false]
        target-enabled [true false]
        distinct-bots [true false]
        [depth max-depth] [[0 4] [3 4] [4 4] [0 0]]]
    {:same-owner same-owner :source-enabled source-enabled
     :target-enabled target-enabled :distinct-bots distinct-bots
     :depth depth :max-depth max-depth}))

(defn- request-literal [{:keys [same-owner source-enabled target-enabled
                                distinct-bots depth max-depth]}]
  (str "(record-new " request-ty " " same-owner " " source-enabled " "
       target-enabled " " distinct-bots " " depth " " max-depth ")"))

;; ── the host side, driven through its public door ────────────────────

(defn- host-args [{:keys [same-owner source-enabled target-enabled
                          distinct-bots depth max-depth]}]
  [{:bot/id "bot-a" :bot/enabled? source-enabled}
   {:bot/id (if distinct-bots "bot-b" "bot-a") :bot/enabled? target-enabled}
   {:source-owner "person-1"
    :target-owner (if same-owner "person-1" "person-2")
    :depth depth :max-depth max-depth}])

;; ── agreement ────────────────────────────────────────────────────────

(deftest kotoba-and-host-agree-on-admission
  (doseq [[fn-name export host-fn] [["adm" "admitted?" handoff/admitted?]
                                    ["bud" "budget-exhausted?" handoff/budget-exhausted?]]]
    (testing export
      (let [probes (map-indexed
                    (fn [i row] [(str fn-name "_" i)
                                 (str "(" export " " (request-literal row) ")")])
                    rows)
            guest (run-probes probes ":bool")]
        (doseq [[i row] (map-indexed vector rows)]
          (is (= (get guest (str fn-name "_" i)) (apply host-fn (host-args row)))
              (str export " disagreed on " (pr-str row))))))))

(deftest kotoba-and-host-agree-on-next-depth
  (let [probes (map-indexed
                (fn [i row] [(str "nd_" i) (str "(next-depth " (request-literal row) ")")])
                rows)
        guest (run-probes probes ":i64")]
    (doseq [[i row] (map-indexed vector rows)]
      (is (= (get guest (str "nd_" i)) (apply handoff/next-depth (host-args row)))
          (str "next-depth disagreed on " (pr-str row))))))

;; ── the properties, which agreement alone would not catch ────────────

(def ^:private decision-rows
  (for [human [true false] identified [true false] authorized [true false]]
    {:human? human :identified? identified :authorized? authorized}))

(deftest an-agent-can-never-approve-through-a-handoff
  ;; Exhaustive over the other three facts. This is the refusal the whole file
  ;; exists for: a Bot that could approve by delegating would make every held
  ;; run self-clearing.
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
      (is (false? (handoff/may-approve? (assoc d :actor-kind :agent)))
          (str "the host let an agent approve: " (pr-str d))))))

(deftest a-person-approves-only-with-all-three-facts
  (doseq [d decision-rows]
    (let [expected (boolean (and (:human? d) (:identified? d) (:authorized? d)))]
      (is (= expected (handoff/may-approve? (assoc d :actor-kind :person)))
          (str "person approval disagreed on " (pr-str d))))))

(deftest work-never-crosses-between-two-peoples-bots
  (doseq [row rows :when (not (:same-owner row))]
    (is (false? (apply handoff/admitted? (host-args row)))
        (str "admitted across owners: " (pr-str row)))))

(deftest a-bot-cannot-hand-work-to-itself
  (doseq [row rows :when (not (:distinct-bots row))]
    (is (false? (apply handoff/admitted? (host-args row)))
        (str "admitted a self-handoff: " (pr-str row)))))

(deftest the-chain-terminates
  ;; Depth strictly increases and the ceiling refuses, which together bound any
  ;; ring. Asserted as the pair, because either one alone permits a loop.
  (doseq [row rows]
    (is (= (inc (:depth row)) (apply handoff/next-depth (host-args row)))
        (str "depth did not increase: " (pr-str row))))
  (doseq [row rows :when (>= (:depth row) (:max-depth row))]
    (is (false? (apply handoff/admitted? (host-args row)))
        (str "admitted at or past the ceiling: " (pr-str row))))
  ;; A zero ceiling admits nothing, which is what setting zero asks for.
  (doseq [row rows :when (zero? (:max-depth row))]
    (is (false? (apply handoff/admitted? (host-args row)))
        (str "a zero ceiling admitted a handoff: " (pr-str row)))))

(deftest an-ordinary-handoff-is-admitted
  ;; The fixture is asserted rather than trusted: every property above holds
  ;; vacuously against a corpus that refuses everything.
  (is (true? (apply handoff/admitted?
                    (host-args {:same-owner true :source-enabled true
                                :target-enabled true :distinct-bots true
                                :depth 0 :max-depth 4})))))
