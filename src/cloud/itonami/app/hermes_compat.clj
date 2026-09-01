(ns cloud.itonami.app.hermes-compat
  "Hermes Agent Bot Mode compatibility over Cloud Itonami Bots.

  Hermes gives every profile one canonical `Bot Chat` session.  Cloud Itonami
  already gives every Bot one durable conversation and one serialized agent
  loop, so the compatibility identity is deliberately one-to-one:

      Hermes profile == Hermes Bot Chat session == Cloud Itonami Bot id

  The adapter changes transport and response envelopes only.  Bot ownership,
  tool grants, write approval, provider admission, receipts, and the durable
  turn ledger remain enforced by `cloud.itonami.app.bots`."
  (:require [clojure.string :as str]
            [cloud.itonami.app.bots :as bots])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.time Instant)
           (java.util UUID)
           (java.util.concurrent Executors LinkedBlockingQueue ThreadFactory
                                 TimeUnit)))

(def ^:private bot-chat-title "Bot Chat")
(def ^:private terminal-statuses #{"completed" "failed" "cancelled"})
(def ^:private closed-event ::closed)
(def ^:private run-retention-seconds 3600)

(defonce ^:private runs (atom {}))

(defonce ^:private run-executor
  (Executors/newCachedThreadPool
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable "cloud-itonami-hermes-run")
         (.setDaemon true))))))

(defn reset-runs!
  "Test/support seam. Active Bot turns are cancelled by their normal owner."
  []
  (reset! runs {}))

(defn- epoch-seconds [value]
  (when value
    (try
      (double (/ (.toEpochMilli (Instant/parse (str value))) 1000.0))
      (catch Exception _ nil))))

(defn- now-seconds [] (double (/ (System/currentTimeMillis) 1000.0)))

(defn- decode-segment [value]
  (URLDecoder/decode (str value) (.name StandardCharsets/UTF_8)))

(defn split-profile-path
  "Return `[profile inner-path]`. `/p/<profile>` mirrors Hermes multiplexing."
  [path]
  (if-let [[_ encoded inner] (re-matches #"/p/([^/]+)(/.*)?" (str path))]
    [(decode-segment encoded) (or inner "/")]
    [nil (str path)]))

(defn- visible-bots [configuration session]
  (:bots (bots/overview configuration session)))

(defn resolve-bot
  "Resolve an explicit Hermes profile/session id, with `default` selecting the
  first visible Bot. Throws the same not-found class as the native Bot API."
  [configuration session profile]
  (let [available (visible-bots configuration session)
        requested (some-> profile str str/trim not-empty)
        default-id (some-> (get-in configuration [:bots :hermes :default-bot-id])
                           str str/trim not-empty)
        selected (if (or (nil? requested) (= "default" requested))
                   (or (some #(when (= default-id (:id %)) %) available)
                       (some #(when (:pinned? %) %) available)
                       (first available))
                   (some #(when (= requested (:id %)) %) available))]
    (or selected
        (throw (ex-info (str "Hermes profile/session not found: " requested)
                        {:type :hermes/not-found :profile requested})))))

(defn profile-list [configuration session]
  {:object "list"
   :data (mapv (fn [bot]
                 {:id (:id bot)
                  :name (:name bot)
                  :model (:model bot)
                  :provider (:provider-id bot)
                  :enabled (boolean (:enabled? bot))
                  :status (:status bot)
                  :session_id (:id bot)
                  :title bot-chat-title})
               (visible-bots configuration session))})

(defn- session-row [bot messages]
  (let [first-message (first messages)
        last-message (last messages)
        started (or (epoch-seconds (:at first-message))
                    (epoch-seconds (:updated-at bot)))
        active (or (epoch-seconds (:at last-message))
                   (epoch-seconds (:activity-at bot))
                   (epoch-seconds (:updated-at bot)))
        preview (some-> last-message :text str (subs 0 (min 160 (count (str (:text last-message))))))]
    {:id (:id bot)
     :source "cloud_itonami"
     :model (:model bot)
     :title bot-chat-title
     :started_at started
     :message_count (count messages)
     :tool_call_count (or (get-in bot [:last-turn :tool-count]) 0)
     :last_active active
     :preview preview
     :pinned (boolean (:pinned? bot))
     :archived (not (boolean (:enabled? bot)))
     :hidden (boolean (:hidden? bot))
     :has_system_prompt false
     :has_model_config (boolean (or (:own-model bot) (:own-provider-id bot)))}))

(defn session
  [configuration session-auth profile-or-session]
  (let [bot (resolve-bot configuration session-auth profile-or-session)]
    {:object "hermes.session"
     :session (session-row bot (bots/messages session-auth (:id bot)))}))

(defn session-list
  [configuration session-auth profile {:keys [limit offset title include-hidden]}]
  (let [all (if profile
              [(resolve-bot configuration session-auth profile)]
              (visible-bots configuration session-auth))
        rows (->> all
                  (remove #(and (not include-hidden) (:hidden? %)))
                  (map (fn [bot]
                         (session-row bot (bots/messages session-auth (:id bot)))))
                  (filter #(or (str/blank? (str title)) (= title (:title %))))
                  vec)
        offset (max 0 (long (or offset 0)))
        limit (-> (or limit 50) long (max 0) (min 200))
        page (->> rows (drop offset) (take limit) vec)]
    {:object "list"
     :data page
     :limit limit
     :offset offset
     :has_more (> (count rows) (+ offset (count page)))}))

(defn- hermes-role [role]
  (case (str role)
    "owner" "user"
    "person" "user"
    "bot" "assistant"
    "assistant" "assistant"
    "user" "user"
    (str role)))

(defn session-messages
  [configuration session-auth profile-or-session {:keys [limit offset order]}]
  (let [bot (resolve-bot configuration session-auth profile-or-session)
        all (mapv (fn [message]
                    (cond-> {:id (:id message)
                             :session_id (:id bot)
                             :role (hermes-role (:role message))
                             :content (:text message)
                             :timestamp (epoch-seconds (:at message))}
                      (seq (:cards message))
                      (assoc :display_kind "cloud_itonami_cards")))
                  (bots/messages session-auth (:id bot)))
        default-page? (nil? limit)
        limit (-> (or limit 500) long (max 0) (min 500))
        offset (max 0 (long (or offset 0)))
        latest? (or (= "latest" order) (and (nil? order) default-page?))
        candidates (if latest? (vec (reverse all)) all)
        page (->> candidates (drop offset) (take limit) vec)]
    {:object "list"
     :session_id (:id bot)
     :data page
     :pagination {:limit limit
                  :offset offset
                  :order (or order (if default-page? "latest" "oldest"))
                  :returned (count page)}}))

(defn- input-text [raw]
  (cond
    (string? raw) (str/trim raw)
    (sequential? raw)
    (let [last-item (last raw)
          content (if (map? last-item) (:content last-item) last-item)]
      (cond
        (string? content) (str/trim content)
        (sequential? content)
        (->> content
             (keep #(when (and (map? %) (= "text" (str (:type %)))) (:text %)))
             (str/join "\n") str/trim)
        :else ""))
    :else ""))

(defn chat-text [body]
  (let [text (input-text (or (:input body) (:message body) (:text body)
                             (:content body)))]
    (when (str/blank? text)
      (throw (ex-info "Missing non-empty input/message/text."
                      {:type :hermes/invalid-input})))
    (if-let [instructions (some-> (:instructions body) str str/trim not-empty)]
      (str "Instructions for this turn:\n" instructions "\n\n" text)
      text)))

(defn session-chat!
  [configuration session-auth profile-or-session body]
  (let [bot (resolve-bot configuration session-auth profile-or-session)
        messages (bots/send! configuration session-auth (:id bot) (chat-text body))
        answer (or (some->> messages
                            reverse
                            (some #(when (= "bot" (:role %)) (:text %))))
                   "")
        turn (bots/latest-turn session-auth (:id bot))]
    {:object "hermes.session.chat.completion"
     :session_id (:id bot)
     :message {:role "assistant" :content answer}
     :usage (or (:usage turn) {})
     :runtime {:provider (:provider turn)
               :model (:model turn)
               :requested_model (:requested-model turn)
               :route_source "cloud_itonami"}}))

(defn- hermes-event [run-id event]
  (let [timestamp (now-seconds)]
    (case (:type event)
      "delta" {:event "assistant.delta" :run_id run-id :timestamp timestamp
               :delta (:content event)}
      "phase" (cond-> {:event "run.phase" :run_id run-id :timestamp timestamp
                       :phase (:phase event)}
                (:tool event) (assoc :tool (:tool event))
                (:tool-count event) (assoc :tool_count (:tool-count event)))
      "followup-applied" {:event "run.steered" :run_id run-id
                          :timestamp timestamp :accepted true}
      {:event "run.progress" :run_id run-id :timestamp timestamp :data event})))

(defn- put-event! [run-id event]
  (when-let [{:keys [events]} (get @runs run-id)]
    (.offer ^LinkedBlockingQueue events event)))

(defn- set-status! [run-id status & [fields]]
  (let [now (now-seconds)]
    (swap! runs update run-id
           (fn [run]
             (when run
               (update run :status merge
                       {:object "hermes.run" :run_id run-id :status status
                        :updated_at now}
                       fields))))
    (get-in @runs [run-id :status])))

(defn- close-events! [run-id]
  (when-let [queue (get-in @runs [run-id :events])]
    (.offer ^LinkedBlockingQueue queue closed-event)))

(defn- purge-runs! []
  (let [cutoff (- (now-seconds) run-retention-seconds)]
    (swap! runs
           (fn [current]
             (into {}
                   (remove (fn [[_ run]]
                             (and (terminal-statuses (get-in run [:status :status]))
                                  (< (get-in run [:status :updated_at] 0) cutoff))))
                   current)))))

(defn start-run!
  "Start a Hermes run on the Bot's native serialized/cancellable loop."
  [configuration session-auth profile body]
  (purge-runs!)
  (let [bot (resolve-bot configuration session-auth
                         (or profile (:profile body) (:session_id body)))
        run-id (str "run_" (str/replace (str (UUID/randomUUID)) "-" ""))
        created (now-seconds)
        queue (LinkedBlockingQueue.)
        status {:object "hermes.run" :run_id run-id :status "started"
                :session_id (:id bot) :model (:model bot)
                :created_at created :updated_at created
                :last_event "run.started"}]
    (swap! runs assoc run-id {:bot-id (:id bot)
                              :session-id (:id bot)
                              :session session-auth
                              :events queue
                              :status status})
    (put-event! run-id {:event "run.started" :run_id run-id :timestamp created})
    (.submit run-executor
             ^Runnable
             (fn []
               (try
                 (set-status! run-id "running" {:last_event "run.started"})
                 (let [messages
                       (bots/send-stream!
                        configuration session-auth (:id bot) (chat-text body)
                        run-id (boolean (:goal body))
                        (fn [event]
                          (let [wire (hermes-event run-id event)]
                            (set-status! run-id "running"
                                         {:last_event (:event wire)})
                            (put-event! run-id wire))))
                       turn (bots/latest-turn session-auth (:id bot))
                       output (or (some->> messages reverse
                                           (some #(when (= "bot" (:role %))
                                                    (:text %))))
                                  "")
                       final-state (if (= "cancelled" (:state turn))
                                     "cancelled" "completed")
                       final-event (str "run." final-state)
                       payload (cond-> {:event final-event :run_id run-id
                                        :timestamp (now-seconds)}
                                 (= "completed" final-state)
                                 (assoc :output output :usage (or (:usage turn) {})))]
                   (put-event! run-id payload)
                   (set-status! run-id final-state
                                (cond-> {:last_event final-event}
                                  (= "completed" final-state)
                                  (assoc :output output :usage (or (:usage turn) {})))))
                 (catch Exception error
                   (let [cancelled? (= :bot/cancelled (:type (ex-data error)))
                         state (if cancelled? "cancelled" "failed")
                         event-name (str "run." state)
                         message (.getMessage error)]
                     (put-event! run-id
                                 (cond-> {:event event-name :run_id run-id
                                          :timestamp (now-seconds)}
                                   (not cancelled?) (assoc :error message)))
                     (set-status! run-id state
                                  (cond-> {:last_event event-name}
                                    (not cancelled?) (assoc :error message)))))
                 (finally
                   (close-events! run-id)))))
    {:run_id run-id :status "started"}))

(defn- durable-run-status [configuration session-auth run-id]
  (some
   (fn [bot]
     (when-let [turn (bots/turn session-auth (:id bot) run-id)]
       (let [state (case (:state turn)
                     "running" "running"
                     "completed" "completed"
                     "cancelled" "cancelled"
                     "failed" "failed"
                     "checkpointed" "running"
                     "blocked" "running"
                     (:state turn))]
         (cond-> {:object "hermes.run" :run_id (str run-id)
                  :session_id (:id bot) :status state
                  :created_at (epoch-seconds (:started-at turn))
                  :updated_at (or (epoch-seconds (:updated-at turn))
                                  (epoch-seconds (:finished-at turn)))
                  :last_event (str "run." state)}
           (:result turn) (assoc :output (:result turn))
           (:usage turn) (assoc :usage (:usage turn))
           (:error-message turn) (assoc :error (:error-message turn))))))
   (visible-bots configuration session-auth)))

(defn run-status [configuration session-auth run-id]
  (purge-runs!)
  (or (when-let [{:keys [bot-id status]} (get @runs (str run-id))]
        ;; A run id is not an authority token. Reuse the native Bot ownership
        ;; gate before returning even in-memory status.
        (bots/messages session-auth bot-id)
        status)
      (durable-run-status configuration session-auth (str run-id))
      (throw (ex-info (str "Run not found: " run-id)
                      {:type :hermes/run-not-found :run-id run-id}))))

(defn take-event!
  "Poll one SSE event. Returns nil on keepalive timeout and `::closed` at EOS."
  [session-auth run-id timeout-seconds]
  (let [{:keys [bot-id events]}
        (or (get @runs (str run-id))
            (throw (ex-info (str "Run not found: " run-id)
                            {:type :hermes/run-not-found :run-id run-id})))
        _ (bots/messages session-auth bot-id)
        queue events]
    (.poll ^LinkedBlockingQueue queue (long timeout-seconds) TimeUnit/SECONDS)))

(defn closed-event? [event] (= closed-event event))

(defn steer!
  [_configuration session-auth run-id body]
  (let [{:keys [bot-id]} (or (get @runs (str run-id))
                             (throw (ex-info (str "Run not found: " run-id)
                                             {:type :hermes/run-not-found})))
        text (chat-text body)
        result (bots/queue-followup! session-auth bot-id (str run-id) text)]
    (set-status! (str run-id) "running" {:last_event "run.steered"})
    {:object "hermes.run.steer" :run_id (str run-id)
     :accepted true :followup_id (:id result)}))

(defn stop!
  [_configuration session-auth run-id]
  (let [{:keys [bot-id]} (or (get @runs (str run-id))
                             (throw (ex-info (str "Run not found: " run-id)
                                             {:type :hermes/run-not-found})))]
    (bots/cancel! session-auth bot-id (str run-id))
    (set-status! (str run-id) "stopping" {:last_event "run.stopping"})
    {:run_id (str run-id) :status "stopping"}))

(defn approval!
  [configuration session-auth run-id body]
  (let [{:keys [bot-id]} (or (get @runs (str run-id))
                             (throw (ex-info (str "Run not found: " run-id)
                                             {:type :hermes/run-not-found})))
        raw (some-> (:choice body) str str/lower-case str/trim)
        choice (get {"approve" "once" "approved" "once" "allow" "once"}
                    raw raw)
        _ (when-not (#{"once" "session" "always" "deny"} choice)
            (throw (ex-info
                    "Invalid approval choice; expected once, session, always, or deny."
                    {:type :hermes/invalid-approval-choice})))
        card (->> (bots/messages session-auth bot-id)
                  reverse (mapcat :cards)
                  (some #(when (and (= "approval" (:kind %))
                                    (= "open" (:standing %))) %)))
        _ (when-not card
            (throw (ex-info (str "Run has no pending approval: " run-id)
                            {:type :hermes/approval-not-pending})))
        decision (if (= "deny" choice) :rejected :approved)]
    (bots/decide! configuration session-auth bot-id (:id card) decision)
    (set-status! (str run-id) "running" {:last_event "approval.responded"})
    {:object "hermes.run.approval_response" :run_id (str run-id)
     :choice choice :resolved 1}))
