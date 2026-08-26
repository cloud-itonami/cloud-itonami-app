(ns cloud.itonami.app.a2a
  "Authenticated A2A v1 host adapter for one explicitly configured Bot.

  The common library owns wire validation. This namespace owns effects:
  caller ownership, durable idempotency and the isolated Bot turn. No grant,
  cookie, approval receipt, wallet authority or private memory is projected."
  (:require [clojure.string :as str]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.store :as store]
            [kotoba.protocol.a2a :as protocol-a2a]
            [kotoba.protocol.slim :as protocol-slim]))

(def scope "a2a:tasks")

(defprotocol SlimPublisher
  (publisher-ready? [publisher])
  (publish-envelope! [publisher envelope]))

(defn enabled? [configuration]
  (and (true? (get-in configuration [:a2a :enabled?]))
       (not (str/blank? (get-in configuration [:a2a :bot-id])))))

(defn- base-origin [configuration]
  (or (get-in configuration [:a2a :resource-origin])
      (get-in configuration [:server :public-origin])))

(defn agent-card [configuration]
  (when-not (enabled? configuration)
    (throw (ex-info "A2A is not configured" {:type :a2a/not-configured})))
  (protocol-a2a/agent-card
   {:name (get-in configuration [:a2a :name])
    :description (get-in configuration [:a2a :description])
    :url (str (str/replace (base-origin configuration) #"/+$" "") "/a2a")
    :version (get-in configuration [:a2a :version])
    :skills (vec (get-in configuration [:a2a :skills] []))
    :security-schemes {:bearerAuth {:type "http" :scheme "bearer"}}
    :security [{:bearerAuth [scope]}]}))

(defn- owner-key [session]
  [(:user-id session) (:organization-id session) (:id session)])

(defn- wire-task [task]
  (protocol-a2a/task
   {:id (:id task)
    :context-id (:context-id task)
    :state (:state task)
    :timestamp (:updated-at task)
    :text (:text task)
    :metadata {:profile "cloud.itonami.a2a-task.v1"}}))

(defn- task-for [session task-id]
  (let [task (get-in (store/snapshot) [:a2a :tasks task-id])]
    (when (and task (= (owner-key session) (:owner task))) task)))

(defn- save-task! [task]
  (store/transact! assoc-in [:a2a :tasks (:id task)] task)
  task)

(defn- prune-tasks [state limit]
  (let [tasks (get-in state [:a2a :tasks] {})
        keep-ids (->> (vals tasks)
                      (sort-by :updated-at)
                      (take-last limit)
                      (map :id)
                      set)]
    (-> state
        (assoc-in [:a2a :tasks] (select-keys tasks keep-ids))
        (update-in [:a2a :messages]
                   (fn [messages]
                     (into {} (filter (comp keep-ids val)) messages))))))

(defn- claim-task! [configuration session admitted]
  (let [owner (owner-key session)
        message-key [owner (:message-id admitted)]
        candidate-id (store/new-id "a2a-task")
        context-id (or (:context-id admitted) (store/new-id "a2a-context"))
        now (store/now)
        limit (max 1 (long (get-in configuration [:a2a :max-tasks] 1000)))]
    (store/transact!
     (fn [state]
       (if (get-in state [:a2a :messages message-key])
         state
         (prune-tasks
          (-> state
              (assoc-in [:a2a :messages message-key] candidate-id)
              (assoc-in [:a2a :tasks candidate-id]
                        {:id candidate-id
                         :context-id context-id
                         :message-id (:message-id admitted)
                         :owner owner
                         :state "TASK_STATE_SUBMITTED"
                         :created-at now
                         :updated-at now}))
          limit))))
    (let [task-id (get-in (store/snapshot) [:a2a :messages message-key])]
      {:task (get-in (store/snapshot) [:a2a :tasks task-id])
       :claimed? (= candidate-id task-id)})))

(defn- answer-text [messages]
  (some->> messages reverse (some #(when (= "bot" (:role %)) (:text %)))))

(defn send-message! [configuration session request]
  (when-not (enabled? configuration)
    (throw (ex-info "A2A is not configured" {:type :a2a/not-configured})))
  (let [admitted (protocol-a2a/send-message-request request)]
    (when (:error admitted)
      (throw (ex-info "Invalid A2A SendMessage request"
                      {:type :a2a/invalid-request
                       :problems (:problems admitted)})))
    (let [{:keys [task claimed?]} (claim-task! configuration session admitted)]
      (if-not claimed?
        (wire-task task)
        (let [working (save-task! (assoc task
                                         :state "TASK_STATE_WORKING"
                                         :updated-at (store/now)))]
          (try
            (let [messages (bots/send!
                            configuration session
                            (get-in configuration [:a2a :bot-id])
                            (:text admitted)
                            {:isolated? true :source :a2a
                             :run-id (:id working)})
                  completed (save-task!
                             (assoc working
                                    :state "TASK_STATE_COMPLETED"
                                    :text (or (answer-text messages) "")
                                    :updated-at (store/now)))]
              (wire-task completed))
            (catch Exception error
              (let [failed (save-task!
                            (assoc working
                                   :state "TASK_STATE_FAILED"
                                   :text "Task failed"
                                   :updated-at (store/now)
                                   :failure-type (some-> error ex-data :type)))]
                (wire-task failed)))))))))

(defn get-task [session request]
  (let [admitted (protocol-a2a/get-task-request request)]
    (when (:error admitted)
      (throw (ex-info "Invalid A2A GetTask request"
                      {:type :a2a/invalid-request
                       :problems (:problems admitted)})))
    (if-let [task (task-for session (:task-id admitted))]
      (wire-task task)
      (throw (ex-info "A2A task was not found" {:type :a2a/not-found})))))

(defn respond! [configuration session request]
  (let [request-id (protocol-a2a/field request "id")
        method (protocol-a2a/field request "method")]
    (case method
      "SendMessage"
      (protocol-a2a/json-rpc-result
       request-id {:task (send-message! configuration session request)})

      "GetTask"
      (protocol-a2a/json-rpc-result request-id (get-task session request))

      (protocol-a2a/json-rpc-error request-id -32601 "Method not found"))))

(defn slim-status
  ([configuration] (slim-status configuration nil))
  ([configuration publisher]
   (let [slim (get-in configuration [:agent-messaging :slim])
         names? (and (protocol-slim/name-parts? (:from slim))
                     (protocol-slim/name-parts? (:to slim)))
         ready? (and (true? (:enabled? slim)) names? publisher
                     (publisher-ready? publisher))]
     {:profile protocol-slim/profile
      :adopted true
      :enabled (true? (:enabled? slim))
      :ready (boolean ready?)
      :reason (when-not ready?
                "SLIM publisher and names are not configured")})))

(defn slim-envelope [configuration delivery-id payload]
  (let [slim (get-in configuration [:agent-messaging :slim])]
    (when-not (and (true? (:enabled? slim))
                   (protocol-slim/name-parts? (:from slim))
                   (protocol-slim/name-parts? (:to slim)))
      (throw (ex-info "SLIM names are not configured" {:type :slim/not-ready})))
    (protocol-slim/envelope {:delivery-id delivery-id
                             :from (:from slim)
                             :to (:to slim)
                             :payload payload})))

(defn publish-slim! [configuration publisher delivery-id payload]
  (when-not (:ready (slim-status configuration publisher))
    (throw (ex-info "SLIM transport is not ready" {:type :slim/not-ready})))
  (let [envelope (slim-envelope configuration delivery-id payload)]
    (when (:error envelope)
      (throw (ex-info "SLIM envelope was refused"
                      {:type :slim/refused :problem envelope})))
    (publish-envelope! publisher envelope)))
