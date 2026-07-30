(ns cloud.itonami.app.agent-loop
  "Durable lifecycle projection for agent work.

  This state machine is intentionally provider-neutral. It records coarse
  lifecycle and evidence events, not token deltas, so the EDN installation
  state remains bounded while a live transport can still stream every event."
  (:require [clojure.string :as str]
            [cloud.itonami.app.agent-event :as agent-event]
            [cloud.itonami.app.store :as store]))

(def schema "cloud.itonami.app.agent-loop.v1")
(def max-events 500)

(defn- emit!
  [{:keys [run-id session-id emit]} type data]
  (let [value (agent-event/event
               {:id (store/new-id "agent-event")
                :run-id run-id
                :session-id session-id
                :type type
                :at (store/now)
                :data data})]
    (store/record-agent-event! value max-events)
    (when emit (emit (agent-event/public-event value)))
    value))

(defn start!
  [{:keys [session-id objective provider model effort mode guardrail emit]}]
  (let [run-id (store/new-id "agent-run")
        context {:run-id run-id :session-id session-id :emit emit}]
    (emit! context :run/started
           {:status :running :provider provider :model model :effort effort})
    (emit! context :phase/started
           {:phase (if (= mode :plan) :plan :discover)
            :status :running :reason guardrail})
    (assoc context
           :objective objective :provider provider :model model :effort effort
           :mode mode :guardrail guardrail)))

(defn phase!
  [context phase]
  (when-not (some #{phase} agent-event/phases)
    (throw (ex-info "Unknown agent loop phase."
                    {:type :agent-loop/invalid-phase :phase phase})))
  (emit! context :phase/started {:phase phase :status :running})
  context)

(defn provider-event!
  [context {:keys [type] :as value}]
  (when (contains? agent-event/event-types type)
    (emit! context type (dissoc value :type)))
  context)

(defn verify!
  "Produce an honest evidence verdict. A non-empty response proves that the
  provider completed, but an Agent run without a tool/artifact/check fact is
  not represented as verified implementation work."
  [context result provider-events]
  (let [content? (not (str/blank? (:content result)))
        evidence (filter #(contains? #{:tool/completed :artifact/changed}
                                     (:type %))
                         provider-events)
        plan? (= :plan (:mode context))
        passed? (and content? (or plan? (seq evidence)))
        status (if passed? :succeeded :needs-review)]
    (phase! context :verify)
    (emit! context :verification/completed
           {:check (if plan? :plan-produced :artifact-evidence)
            :passed? (boolean passed?)
            :status status
            :evidence-count (count evidence)
            :reason (when-not passed?
                      :provider-completed-without-verifiable-artifact)})
    {:status status :passed? (boolean passed?) :evidence-count (count evidence)}))

(defn complete! [context verification result]
  (emit! context :run/completed
         {:status (:status verification)
          :provider (:provider context)
          :model (:model context)
          :usage (:usage result)
          :evidence-count (:evidence-count verification)})
  verification)

(defn fail! [context error]
  (emit! context :run/failed
         {:status :failed
          :reason (or (some-> error ex-data :type) :provider-failure)})
  {:status :failed})
