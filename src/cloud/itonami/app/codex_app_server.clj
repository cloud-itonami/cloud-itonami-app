(ns cloud.itonami.app.codex-app-server
  "Minimal stdio client for the stable Codex app-server thread/turn/item API."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.approval-broker :as approval])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(def schema "cloud.itonami.app.codex-app-server.v1")

(defn- send! [^OutputStreamWriter writer value]
  (.write writer (json/write-str value))
  (.write writer "\n")
  (.flush writer))

(defn- stop-process! [^Process process]
  (doseq [descendant
          (reverse (vec (iterator-seq
                         (.iterator (.descendants (.toHandle process))))))]
    (try (.destroyForcibly descendant) (catch Exception _)))
  (try (.destroy process) (catch Exception _))
  (when-not (try (.waitFor process 2 TimeUnit/SECONDS)
                 (catch Exception _ true))
    (try (.destroyForcibly process) (catch Exception _))
    (try (.waitFor process 2 TimeUnit/SECONDS) (catch Exception _)))
  (try (.close (.getErrorStream process)) (catch Exception _))
  (try (.close (.getInputStream process)) (catch Exception _)))

(defn- model-id [model]
  (when model (last (str/split model #":" 2))))

(defn- item-event [wire-type item]
  (let [item-type (:type item)
        tool (case item-type
               "commandExecution" :shell
               "mcpToolCall" :mcp
               "webSearch" :web-search
               nil)]
    (cond
      (and (= wire-type "item/started") tool)
      {:type :tool/started :tool tool :item-type (keyword item-type)}

      (and (= wire-type "item/completed") tool)
      {:type (if (contains? #{"failed" "declined"} (:status item))
               :tool/failed :tool/completed)
       :tool tool :item-type (keyword item-type)
       :exit-code (:exitCode item)}

      (and (= wire-type "item/completed") (= item-type "fileChange"))
      {:type :artifact/changed :item-type :file-change
       :paths (vec (keep :path (:changes item)))}

      :else nil)))

(defn- approval-kind [method]
  (case method
    "item/commandExecution/requestApproval" :command-execution
    "item/fileChange/requestApproval" :file-change
    "item/permissions/requestApproval" :permissions
    nil))

(defn- approval-result [kind decision params]
  (if (= kind :permissions)
    {:permissions (if (#{:accept :accept-for-session} decision)
                    (or (:permissions params) [])
                    [])
     :scope (if (= decision :accept-for-session) "session" "turn")}
    {:decision (approval/codex-decision decision)}))

(defn run!
  [{:keys [binary prompt cwd model effort read-only? runner-session-id
           timeout-seconds on-delta on-event approval-handler]}]
  (let [process (.start (ProcessBuilder. ^java.util.List [binary "app-server"]))
        writer (OutputStreamWriter. (.getOutputStream process)
                                    StandardCharsets/UTF_8)
        reader (BufferedReader. (InputStreamReader. (.getInputStream process)
                                                    StandardCharsets/UTF_8))
        stderr (future (slurp (.getErrorStream process)))
        content (StringBuilder.)
        final-content (atom nil)
        usage (atom nil)
        thread-id (atom runner-session-id)
        turn-complete (promise)
        provider-events (atom [])
        emit (fn [event]
               (swap! provider-events conj event)
               (when on-event (on-event event)))
        start-turn!
        (fn [id]
          (send! writer
                 {:method "turn/start" :id 2
                  :params
                  {:threadId id
                   :input [{:type "text" :text prompt}]
                   :cwd cwd
                   :approvalPolicy (if read-only? "never" "on-request")
                   :approvalsReviewer (when-not read-only? "auto_review")
                   :sandboxPolicy (if read-only?
                                    {:type "readOnly"}
                                    {:type "workspaceWrite"
                                     :writableRoots [cwd]
                                     :networkAccess false})
                   :model (model-id model)
                   :effort effort}}))]
    (send! writer
           {:method "initialize" :id 0
            :params {:clientInfo {:name "cloud_itonami"
                                  :title "Cloud Itonami"
                                  :version "0.1.0"}}})
    (send! writer {:method "initialized" :params {}})
    (send! writer
           {:method (if runner-session-id "thread/resume" "thread/start")
            :id 1
            :params (cond-> {:cwd cwd}
                      runner-session-id (assoc :threadId runner-session-id)
                      (not runner-session-id) (assoc :model (model-id model)
                                                     :approvalPolicy
                                                     (if read-only?
                                                       "never" "on-request")
                                                     :sandbox
                                                     (if read-only?
                                                       "read-only"
                                                       "workspace-write")
                                                     :serviceName
                                                     "cloud_itonami"))})
    (let [reader-task
          (future
            (try
              (loop []
                (when-let [line (.readLine reader)]
                  (when-let [message
                             (try (json/read-str line :key-fn keyword)
                                  (catch Exception _ nil))]
                    (let [method (:method message)
                          params (:params message)]
                      (cond
                        (and (:id message) (:error message))
                        (deliver turn-complete
                                 {:status "failed" :error (:error message)})

                        (and (= 1 (:id message)) (:result message))
                        (let [id (get-in message [:result :thread :id])]
                          (reset! thread-id id)
                          (start-turn! id))

                        (= method "item/agentMessage/delta")
                        (let [delta (:delta params)]
                          (when (seq delta)
                            (.append content delta)
                            (when on-delta (on-delta delta))))

                        (contains? #{"item/started" "item/completed"} method)
                        (do
                          (when-let [event (item-event method (:item params))]
                            (emit event))
                          (when (and (= method "item/completed")
                                     (= "agentMessage"
                                        (get-in params [:item :type])))
                            (reset! final-content (get-in params [:item :text]))))

                        (= method "thread/tokenUsage/updated")
                        (reset! usage (or (:tokenUsage params) params))

                        (approval-kind method)
                        (let [kind (approval-kind method)
                              decision
                              (if approval-handler
                                (approval-handler
                                 {:kind kind :request-id (:id message)
                                  :params params
                                  :summary (or (:reason params)
                                               (str "Codex " (name kind)))
                                  :reason (:reason params) :cwd (:cwd params)})
                                :decline)]
                          (send! writer {:id (:id message)
                                         :result (approval-result kind decision
                                                                  params)}))

                        (= method "turn/completed")
                        (deliver turn-complete (:turn params))

                        (= method "error")
                        (deliver turn-complete
                                 {:status "failed" :error (:error params)}))))
                  (when-not (realized? turn-complete) (recur))))
              (catch Exception error
                (deliver turn-complete {:status "failed"
                                        :error {:message (.getMessage error)}}))))
          timeout-ms (* 1000 (long (or timeout-seconds 600)))
          turn (deref turn-complete timeout-ms ::timeout)]
        (when (= ::timeout turn)
          (stop-process! process)
          (throw (ex-info "Codex app-server turn timed out."
                          {:type :codex-app-server/timeout
                           :stderr (subs (deref stderr 1000 "")
                                         0 (min 1000
                                                (count
                                                 (deref stderr 1000 ""))))})))
        (when (= "failed" (:status turn))
          (stop-process! process)
          (throw (ex-info (or (get-in turn [:error :message])
                              "Codex app-server turn failed.")
                          {:type :codex-app-server/failed
                           :error (:error turn)})))
        (try (.close writer) (catch Exception _))
        (stop-process! process)
        (deref reader-task 2000 nil)
        (let [answer (or @final-content (not-empty (.toString content)))]
          (when (str/blank? answer)
            (throw (ex-info "Codex app-server returned no agent message."
                            {:type :codex-app-server/invalid-output
                             :stderr (subs (deref stderr 1000 "")
                                           0 (min 1000
                                                  (count (deref stderr 1000 ""))))})))
          {:content answer :usage @usage :runner-session-id @thread-id
           :events @provider-events :transport :codex-app-server}))))
