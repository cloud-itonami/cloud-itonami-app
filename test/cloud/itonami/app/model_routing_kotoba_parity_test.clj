(ns cloud.itonami.app.model-routing-kotoba-parity-test
  "Which model answers, in .kotoba and through the host.

  ## What is actually at risk

  Two properties carry this rule, and each is one comparison from being lost:

  - an auxiliary override naming a provider this deployment will not admit must
    REFUSE. The alternative -- running on the main model while the screen still
    names the assigned one -- reads as prudence and is the failure: the bill is
    the expensive model's, the belief is the cheap one's, and NOTHING IN THE
    OUTPUT DISTINGUISHES THEM. `an-unadmitted-override-refuses-by-that-name`
    pins the refusal to `:routing/auxiliary-denied`, not merely to something
    having been thrown, because a refusal for a different reason is not this
    refusal and would keep passing after the guard was gone.
  - precedence is a total order over two facts. Asserted as the relation --
    a Bot with its own assignment never reads the default -- rather than only
    as row-by-row agreement, which would still hold if both sides were wrong
    together.

  ## And one floor that is not about the core at all

  This surface reproduces one from a product whose auxiliary tasks are its own
  real call sites. `every-auxiliary-task-names-a-function-that-exists` is what
  keeps that true here: a task row whose `:source` no longer resolves is a menu
  entry with no kitchen behind it, which is the exact failure `bots.clj` was
  written to end. It fails on a rename, and that is the point -- the rename and
  the row are one commit or the screen starts lying.

  ## The corpus is exhaustive because it is four rows

  Two booleans for the scope, two for the auxiliary route: 4 x 2, checked in
  full. A conjunction is exactly the shape where sampling misses a dropped
  term."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.agent-control]
            [cloud.itonami.app.bots]
            [cloud.itonami.app.model-routing :as routing]
            [cloud.itonami.app.service]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/cloud/itonami/app/model_routing_core.kotoba"))

(def ^:private export-prefix
  (str "scope-bot scope-default scope-provider aux-override aux-main "
       "aux-refused route-scope auxiliary-route assignment-complete? main"))

(def ^:private scope-ty
  "[:record :routing/scope [[:bot-assigned :bool] [:default-assigned :bool]]]")

(def ^:private auxiliary-ty
  (str "[:record :routing/auxiliary [[:has-override :bool] "
       "[:override-admitted :bool]]]"))

(def ^:private submitted-ty
  "[:record :routing/submitted [[:has-provider :bool] [:has-model :bool]]]")

(defn- run-probes
  "Compile the core with zero-arg probes appended and execute each one."
  [probes result-type]
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

(def ^:private scope-rows
  (for [bot [true false] default [true false]]
    {:bot-assigned? bot :default-assigned? default}))

(def ^:private auxiliary-rows
  (for [override [true false] admitted [true false]]
    {:has-override? override :override-admitted? admitted}))

(def ^:private submitted-rows
  (for [provider [true false] model [true false]]
    {:has-provider? provider :has-model? model}))

(defn- probe-name [prefix i] (str prefix "_" i))

;; ── agreement ────────────────────────────────────────────────────────

(deftest kotoba-and-host-agree-on-route-scope
  (let [probes (map-indexed
                (fn [i {:keys [bot-assigned? default-assigned?]}]
                  [(probe-name "rs" i)
                   (str "(route-scope (record-new " scope-ty " "
                        bot-assigned? " " default-assigned? "))")])
                scope-rows)
        guest (run-probes probes ":i64")]
    (doseq [[i row] (map-indexed vector scope-rows)]
      (let [expected (get routing/scope-codes (get guest (probe-name "rs" i)))
            actual (routing/route-scope row)]
        (is (= expected actual) (str "route-scope disagreed on " (pr-str row)))))))

(deftest kotoba-and-host-agree-on-auxiliary-route
  (let [probes (map-indexed
                (fn [i {:keys [has-override? override-admitted?]}]
                  [(probe-name "ar" i)
                   (str "(auxiliary-route (record-new " auxiliary-ty " "
                        has-override? " " override-admitted? "))")])
                auxiliary-rows)
        guest (run-probes probes ":i64")]
    (doseq [[i row] (map-indexed vector auxiliary-rows)]
      (let [expected (get routing/auxiliary-codes (get guest (probe-name "ar" i)))
            actual (routing/auxiliary-route row)]
        (is (= expected actual)
            (str "auxiliary-route disagreed on " (pr-str row)))))))

(deftest kotoba-and-host-agree-on-assignment-complete
  (let [probes (map-indexed
                (fn [i {:keys [has-provider? has-model?]}]
                  [(probe-name "ac" i)
                   (str "(assignment-complete? (record-new " submitted-ty " "
                        has-provider? " " has-model? "))")])
                submitted-rows)
        guest (run-probes probes ":bool")]
    (doseq [[i {:keys [has-provider? has-model?] :as row}] (map-indexed vector submitted-rows)]
      (let [expected (get guest (probe-name "ac" i))
            actual (routing/assignment-complete?
                    {:provider-id (when has-provider? "murakumo")
                     :model (when has-model? "murakumo-main")})]
        (is (= expected actual)
            (str "assignment-complete? disagreed on " (pr-str row)))))))

;; ── the properties, which agreement alone would not catch ────────────

(deftest a-bot-with-its-own-assignment-never-reads-the-default
  ;; The relation, not the rows. Reversing the two branches in the core still
  ;; compiles, and every deployment where the two happen to name the same model
  ;; would keep passing.
  (doseq [row scope-rows :when (:bot-assigned? row)]
    (is (= :bot (routing/route-scope row))
        (str "an assigned Bot must read its own row: " (pr-str row))))
  (doseq [row scope-rows :when (and (not (:bot-assigned? row))
                                    (:default-assigned? row))]
    (is (= :default (routing/route-scope row))))
  ;; No assignment at all is the behaviour of every deployment that has never
  ;; opened the screen, and must not read as a failure.
  (is (= :provider (routing/route-scope {:bot-assigned? false
                                         :default-assigned? false}))))

(deftest an-unadmitted-override-never-resolves-to-main
  ;; The whole reason this decision is written down rather than left to an `or`.
  (doseq [row auxiliary-rows :when (and (:has-override? row)
                                        (not (:override-admitted? row)))]
    (is (= :refused (routing/auxiliary-route row))
        (str "an unadmitted override must refuse: " (pr-str row)))
    (is (not= :main (routing/auxiliary-route row))
        "silently billing the main model is the failure this prevents")))

(def ^:private denied-provider-config
  "A deployment where the assigned provider exists and is NOT admissible.

  `:reviewed? false` is the cheapest true blocker: `policy/select-provider`
  returns nil for it, which is exactly the state a person reaches by assigning
  a provider and then having its review withdrawn."
  {:routing {:default-provider "ollama" :cloud-enabled? false}
   :providers [{:id "ollama" :name "Ollama" :kind :openai-compatible
                :base-url "http://127.0.0.1:11434/v1"
                :default-model "local" :enabled? true :reviewed? true}
               {:id "xai" :name "Grok" :kind :xai
                :base-url "https://api.x.ai/v1"
                :default-model "grok-4.6" :enabled? true :reviewed? false}]})

(deftest an-unadmitted-override-refuses-by-that-name
  ;; Pinned to the reason, not to the fact of an exception. A refusal thrown by
  ;; something else -- a typo in the task name, a missing key -- is not this
  ;; guard doing its job, and asserting only `thrown?` would count it as one.
  (let [idx (routing/index [{:routing/task :room
                             :routing/scope routing/default-scope
                             :routing/provider-id "xai"
                             :routing/model "grok-4.6"}])
        main {:provider {:id "ollama"} :model "local"}]
    (is (= :refused (:route (routing/resolve-auxiliary
                             denied-provider-config idx :room))))
    (let [thrown (try
                   (routing/auxiliary-choice! denied-provider-config idx :room main)
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (some? thrown) "an unadmitted override must not return a route")
      (is (= :routing/auxiliary-denied (:type thrown))
          "the refusal must name itself, so a rename of the reason fails here")
      (is (= "xai" (:provider thrown))
          "and must name the destination that stopped being admissible"))))

(deftest a-task-with-no-override-runs-on-main-unchanged
  ;; The behaviour of every deployment that never opens the screen. If this
  ;; broke, adding the surface would have changed what an untouched
  ;; installation does, which is the one outcome a settings screen may not have.
  (let [main {:provider {:id "ollama"} :model "local"}]
    (doseq [t routing/auxiliary-tasks]
      (is (= main (routing/auxiliary-choice!
                   denied-provider-config (routing/index []) (:task t) main))
          (str (:task t) " with no override must be main, byte for byte")))))

(deftest half-an-assignment-never-reaches-storage
  (doseq [[label submitted] [["no model" {:task :bot :scope "b1"
                                          :provider-id "ollama" :model "  "}]
                             ["no provider" {:task :bot :scope "b1"
                                             :provider-id nil :model "local"}]]]
    (testing label
      (let [thrown (try (routing/assignment submitted) nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :routing/incomplete (:type thrown))
            "and refuses under its own name")))))

(deftest an-auxiliary-task-cannot-be-assigned-to-one-bot
  ;; A room round is many Bots at once and the machine loop belongs to the
  ;; workstation: a per-Bot scope for either would be a row that can never
  ;; match, silently doing nothing.
  (doseq [t routing/auxiliary-tasks]
    (let [thrown (try (routing/assignment {:task (:task t) :scope "bot-1"
                                           :provider-id "ollama" :model "local"})
                      nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :routing/scope-not-assignable (:type thrown))
          (str (:task t) " must refuse a per-Bot scope by that name"))))
  ;; The main task is the one that can, because it is the only one that has a
  ;; Bot to be scoped to.
  (is (= "bot-1" (:routing/scope
                  (routing/assignment {:task :bot :scope "bot-1"
                                       :provider-id "ollama" :model "local"})))))

(deftest a-task-this-application-does-not-call-is-refused
  (let [thrown (try (routing/assignment {:task :vision :scope "default"
                                         :provider-id "ollama" :model "local"})
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :routing/unknown-task (:type thrown))
        "a task name borrowed from another product has no call site here")))

(deftest a-stored-row-for-a-removed-task-stops-answering
  ;; `index` drops what no longer normalises. A task removed from this
  ;; application must not keep routing from a row written before it was
  ;; removed -- the row outlives the code, and the screen would not show it.
  (let [idx (routing/index [{:routing/task :vision
                             :routing/scope routing/default-scope
                             :routing/provider-id "xai" :routing/model "m"}
                            {:routing/task :room
                             :routing/scope routing/default-scope
                             :routing/provider-id "ollama" :routing/model "local"}])]
    (is (= 1 (count idx)))
    (is (contains? idx [:room routing/default-scope]))))

;; ── the floor that keeps the menu honest ─────────────────────────────

(deftest every-auxiliary-task-names-a-function-that-exists
  ;; Not a style check. A `:source` that no longer resolves is a task row on a
  ;; settings screen with nothing behind it, offering to route a model call
  ;; this application no longer makes. Fails on a rename, deliberately.
  (doseq [{:keys [task source]} routing/auxiliary-tasks]
    (let [[ns-part fn-part] (str/split source #"/" 2)
          full (symbol (str "cloud.itonami.app." ns-part))]
      (is (some? (find-ns full))
          (str task " names namespace " full ", which is not loaded"))
      (is (contains? (ns-interns full) (symbol fn-part))
          (str task " names " source ", which does not exist -- either the "
               "function was renamed and this row must follow, or the call "
               "site is gone and this row must be deleted")))))

(deftest the-main-task-is-not-in-the-auxiliary-list
  ;; They are resolved by different functions and a task in both lists would be
  ;; resolved twice, with the second answer winning silently.
  (is (not (contains? routing/auxiliary-task-set routing/main-task)))
  (is (= (count routing/tasks)
         (inc (count routing/auxiliary-tasks))
         (count (set routing/tasks)))))
