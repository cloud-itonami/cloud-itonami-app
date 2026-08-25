(ns cloud.itonami.app.decision-method
  "A bounded Ontology -> System Dynamics -> Scenario -> Decision frame.

  This is Maven-style in the public, architectural sense: operational objects,
  relationships, actions and scenarios share one explicit model.  It does not
  claim or reproduce Palantir Maven internals.

  The frame is decision evidence, not execution authority.  It may rank an
  action highly and the capability/HITL gates may still refuse that action."
  (:require [clojure.string :as str]
            [cloud.itonami.app.loops :as loops]))

(def score-weights
  {:expected-value 0.25
   :evidence-confidence 0.20
   :reversibility 0.15
   :authority-fit 0.15
   :time-efficiency 0.10
   :cost-efficiency 0.10
   :dependency-independence 0.05})

(def score-keys (set (keys score-weights)))
(def dynamics-modes #{"xmile-bound" "structural-sketch" "not-material"})

(def tool-definition
  {:name "decision_frame"
   :description
   (str "Record or revise the evidence-bounded decision frame for the active Goal. "
        "Use observed facts and explicit assumptions to identify ontology objects and "
        "relations, decide whether dynamics require a bound XMILE model or a stock-flow "
        "sketch, compare at least baseline and one alternative, and select one scenario. "
        "The host computes weighted scores; this record grants no execution authority. "
        "Call it after enough read evidence is available and before any write or goal_complete. "
        "An xmile-bound frame is accepted only after the host runs that workspace model successfully.")
   :parameters
   {:type "object"
    :required ["scope" "facts" "entities" "relations" "dynamics" "scenarios" "selected"]
    :properties
    {:scope {:type "string"}
     :facts {:type "array" :minItems 1 :maxItems 24
             :items {:type "object" :required ["id" "statement" "evidence"]
                     :properties {:id {:type "string"}
                                  :statement {:type "string"}
                                  :evidence {:type "array" :items {:type "string"}}}}}
     :entities {:type "array" :minItems 1 :maxItems 24
                :items {:type "object" :required ["id" "type"]
                        :properties {:id {:type "string"} :type {:type "string"}}}}
     :relations {:type "array" :maxItems 48
                 :items {:type "object" :required ["from" "predicate" "to"]
                         :properties {:from {:type "string"}
                                      :predicate {:type "string"}
                                      :to {:type "string"}
                                      :evidence {:type "array" :items {:type "string"}}}}}
     :dynamics {:type "object" :required ["mode" "reason" "stocks" "flows"]
                :properties {:mode {:type "string"
                                    :enum ["xmile-bound" "structural-sketch" "not-material"]}
                             :model {:type "string"}
                             :reason {:type "string"}
                             :stocks {:type "array" :items {:type "string"}}
                             :flows {:type "array" :items {:type "string"}}}}
     :scenarios {:type "array" :minItems 2 :maxItems 8
                 :items {:type "object"
                         :required ["id" "label" "assumptions" "outcomes" "scores"]
                         :properties
                         {:id {:type "string"} :label {:type "string"}
                          :assumptions {:type "array" :items {:type "string"}}
                          :outcomes {:type "array" :items {:type "string"}}
                          :scores {:type "object"
                                   :required ["expected_value" "evidence_confidence"
                                              "reversibility" "authority_fit"
                                              "time_efficiency" "cost_efficiency"
                                              "dependency_independence"]
                                   :properties
                                   {:expected_value {:type "number" :minimum 0 :maximum 1}
                                    :evidence_confidence {:type "number" :minimum 0 :maximum 1}
                                    :reversibility {:type "number" :minimum 0 :maximum 1}
                                    :authority_fit {:type "number" :minimum 0 :maximum 1}
                                    :time_efficiency {:type "number" :minimum 0 :maximum 1}
                                    :cost_efficiency {:type "number" :minimum 0 :maximum 1}
                                    :dependency_independence {:type "number" :minimum 0 :maximum 1}}}}}}
     :selected {:type "string"}}}})

(defn- text [value limit]
  (let [s (some-> value str str/trim)]
    (when (seq s) (subs s 0 (min limit (count s))))))

(defn- texts [values limit maximum]
  (->> values (keep #(text % limit)) (take maximum) vec))

(defn- wire-score-key [key]
  (-> key name (str/replace "_" "-") keyword))

(defn- scores [input]
  (let [values (into {} (map (fn [[k value]] [(wire-score-key k) value])) input)]
    (when-not (= score-keys (set (keys values)))
      (throw (ex-info "every scenario requires all decision scores"
                      {:type :decision/invalid-scores})))
    (when-not (every? #(and (number? %) (<= 0.0 (double %) 1.0)) (vals values))
      (throw (ex-info "decision scores must be numbers between 0 and 1"
                      {:type :decision/invalid-scores})))
    (into {} (map (fn [[k v]] [k (double v)])) values)))

(defn- weighted-score [values]
  (reduce-kv (fn [total key weight]
               (+ total (* weight (get values key))))
             0.0 score-weights))

(defn- safe-relative-model? [path]
  (and path
       (not (str/starts-with? path "/"))
       (not (str/starts-with? path "~"))
       (not (re-find #"(^|[\\/])\.\.([\\/]|$)" path))
       (not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" path))))

(defn prepare-frame
  "Validate, bound and score a model-produced frame."
  [input]
  (let [scope (text (:scope input) 500)
        facts (mapv (fn [fact]
                      {:fact/id (text (:id fact) 80)
                       :fact/statement (text (:statement fact) 500)
                       :fact/evidence (texts (:evidence fact) 300 8)})
                    (take 24 (:facts input)))
        entities (mapv (fn [entity]
                         {:entity/id (text (:id entity) 80)
                          :entity/type (text (:type entity) 80)})
                       (take 24 (:entities input)))
        entity-ids (set (map :entity/id entities))
        relations (mapv (fn [relation]
                          {:relation/from (text (:from relation) 80)
                           :relation/predicate (text (:predicate relation) 80)
                           :relation/to (text (:to relation) 80)
                           :relation/evidence (texts (:evidence relation) 300 8)})
                        (take 48 (:relations input)))
        dynamics-input (:dynamics input)
        mode (text (:mode dynamics-input) 40)
        stocks (texts (:stocks dynamics-input) 120 24)
        flows (texts (:flows dynamics-input) 120 24)
        dynamics {:dynamics/mode mode
                  :dynamics/model (text (:model dynamics-input) 300)
                  :dynamics/reason (text (:reason dynamics-input) 500)
                  :dynamics/stocks stocks :dynamics/flows flows}
        scenarios (mapv (fn [scenario]
                          (let [values (scores (:scores scenario))]
                            {:scenario/id (text (:id scenario) 80)
                             :scenario/label (text (:label scenario) 160)
                             :scenario/assumptions (texts (:assumptions scenario) 300 16)
                             :scenario/outcomes (texts (:outcomes scenario) 300 16)
                             :scenario/scores values
                             :scenario/weighted-score (weighted-score values)}))
                        (take 8 (:scenarios input)))
        scenario-ids (set (map :scenario/id scenarios))
        selected (text (:selected input) 80)]
    (when-not (and scope (seq facts) (seq entities) (<= 2 (count scenarios)))
      (throw (ex-info "decision frame requires scope, evidence, ontology, and at least two scenarios"
                      {:type :decision/incomplete})))
    (when-not (and (every? :fact/id facts)
                   (every? :fact/statement facts)
                   (every? (comp seq :fact/evidence) facts)
                   (= (count facts) (count (set (map :fact/id facts)))))
      (throw (ex-info "facts require unique ids, statements, and evidence"
                      {:type :decision/invalid-facts})))
    (when-not (and (every? :entity/id entities) (every? :entity/type entities)
                   (= (count entities) (count entity-ids)))
      (throw (ex-info "ontology entities require unique ids and types"
                      {:type :decision/invalid-ontology})))
    (when-not (every? #(and (contains? entity-ids (:relation/from %))
                            (contains? entity-ids (:relation/to %))
                            (:relation/predicate %)) relations)
      (throw (ex-info "ontology relations must connect declared entities"
                      {:type :decision/invalid-ontology})))
    (when-not (and (contains? dynamics-modes mode) (:dynamics/reason dynamics))
      (throw (ex-info "dynamics requires a supported mode and reason"
                      {:type :decision/invalid-dynamics})))
    (when (and (not= "not-material" mode) (or (empty? stocks) (empty? flows)))
      (throw (ex-info "dynamic work requires explicit stocks and flows"
                      {:type :decision/invalid-dynamics})))
    (when (and (= "xmile-bound" mode) (nil? (:dynamics/model dynamics)))
      (throw (ex-info "xmile-bound dynamics requires a model reference"
                      {:type :decision/invalid-dynamics})))
    (when (and (= "xmile-bound" mode)
               (not (safe-relative-model? (:dynamics/model dynamics))))
      (throw (ex-info "XMILE model must be a safe workspace-relative path"
                      {:type :decision/unsafe-model-path})))
    (when-not (and (every? :scenario/id scenarios)
                   (every? :scenario/label scenarios)
                   (= (count scenarios) (count scenario-ids))
                   (contains? scenario-ids selected))
      (throw (ex-info "scenarios require unique ids and a declared selection"
                      {:type :decision/invalid-scenarios})))
    {:decision.method/schema "cloud.itonami.decision-frame.v1"
     :decision.method/scope scope
     :decision.method/facts facts
     :decision.method/entities entities
     :decision.method/relations relations
     :decision.method/dynamics dynamics
     :decision.method/scenarios (vec (sort-by (comp - :scenario/weighted-score) scenarios))
     :decision.method/selected selected
     :decision.method/score-weights score-weights}))

(defn verify-dynamics
  "Execute an XMILE-bound frame with Cloud Itonami's canonical engine.

  Structural sketches and explicit non-dynamic exceptions carry no simulated
  receipt.  The receipt is deliberately bounded: the full trajectory can be
  re-run from the model, while the decision record needs only enough to prove
  which model ran and that it produced a trajectory."
  [configuration frame]
  (if-not (= "xmile-bound"
             (get-in frame [:decision.method/dynamics :dynamics/mode]))
    frame
    (let [path (get-in frame [:decision.method/dynamics :dynamics/model])
          result (loops/model configuration {:business/model path})
          trajectory (:trajectory result)]
      (when-not (and (= :resolved (:state result))
                     (= :simulated (:state trajectory)))
        (throw (ex-info
                (str "XMILE execution did not produce a trajectory: "
                     (or (:detail result) (:reason trajectory) (:state result)))
                {:type :decision/xmile-not-simulated
                 :state (:state result)
                 :trajectory-state (:state trajectory)})))
      (assoc frame :decision.method/xmile-execution
             {:xmile/source (:source result)
              :xmile/model (:simulated-model result)
              :xmile/steps (:steps trajectory)
              :xmile/state :simulated}))))

(def prompt
  (str "Default decision method for business work:\n"
       "1. Ontology: separate observed facts and their evidence from assumptions; name actors, assets, obligations, constraints and their relations.\n"
       "2. System dynamics: identify stocks, flows, delays and feedback. Bind and run an existing XMILE model when one fits. Otherwise record a structural stock-flow sketch; use not-material only when time-dependent feedback cannot change the decision, and state why.\n"
       "3. Scenarios: compare the baseline/status quo with at least one feasible alternative. Keep assumptions explicit and do not alter the baseline to make an alternative win.\n"
       "4. Inference: score expected value, evidence confidence, reversibility, authority fit, time efficiency, cost efficiency and dependency independence from 0 to 1. The host computes the weighted result.\n"
       "5. Action: call decision_frame after evidence gathering and before any write or goal_complete. A selected scenario is advice, never authority; all capability, approval, money and external-effect gates remain in force.\n"
       "This is a public-concept Maven-style ontology/scenario method, not a claim about proprietary Maven internals."))
