(ns cloud.itonami.app.agent-event
  "Provider-neutral facts emitted by every interactive or background agent.

  Provider payloads never become the public contract. Codex app-server,
  Claude stream-json, a local model, and Tamaki actors all project into this
  small vocabulary so storage, policy, UI, replay, and evaluation do not need
  provider-specific branches."
  (:require [clojure.string :as str]))

(def schema "cloud.itonami.app.agent-event.v1")

(def event-types
  #{:run/started :run/completed :run/failed :run/blocked
    :cycle/started :cycle/completed
    :phase/started :phase/completed
    :model/started :model/completed
    :tool/started :tool/completed :tool/failed
    :artifact/changed
    :workspace/prepared :workspace/released
    :approval/requested :approval/resolved
    :verification/completed :evaluation/completed})

(def phases [:discover :plan :execute :verify :review :integrate :reflect])

(def terminal-statuses #{:succeeded :needs-review :failed :blocked :cancelled})

(defn valid-event? [event]
  (and (= schema (:event/schema event))
       (contains? event-types (:event/type event))
       (not (str/blank? (:event/id event)))
       (not (str/blank? (:run/id event)))
       (not (str/blank? (:session/id event)))
       (not (str/blank? (:event/at event)))
       (map? (:event/data event))))

(defn event
  [{:keys [id run-id session-id type at data]}]
  (let [value {:event/schema schema
               :event/id id
               :run/id run-id
               :session/id session-id
               :event/type type
               :event/at at
               :event/data (or data {})}]
    (when-not (valid-event? value)
      (throw (ex-info "Agent event contract is invalid."
                      {:type :agent-event/invalid :event value})))
    value))

(defn public-event
  "The event envelope is already deliberately narrow. Remove values which may
  carry a provider transcript or a raw command before crossing the UI boundary."
  [value]
  (update value :event/data
          #(select-keys % [:phase :status :provider :model :effort
                           :tool :item-type :exit-code :path :paths
                           :check :passed? :reason :evidence-count
                           :duration-ms :usage :approval-id :kind :decision
                           :digest :workspace :isolation :score :grade
                           :artifact-events :successful-tools :failed-tools
                           :cycle :max-cycles :continue? :branch
                           :changed-files :commits :reused?])))
