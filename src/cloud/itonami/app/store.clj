(ns cloud.itonami.app.store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.installation :as installation]
            [cloud.itonami.app.recovery :as recovery]
            [kotoba.kgraph :as kgraph])
  (:import [java.nio.file Files StandardCopyOption]
           [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.state.v1")

(defn initial-state []
  {:schema schema
   :installation {:id (str "installation-" (UUID/randomUUID))
                  :created-at (str (Instant/now))
                  :storage-schema "cloud.itonami.app.installation-storage.v1"}
   :agents [{:id "local" :name "Local" :system-prompt
             "You are a private, local-first assistant. Be concise and useful."}]
   :sessions {}
   :runner-sessions {}
   :agent-loops {:runs {} :events []}
   :agent-control {:runs {} :events []}
   ;; One `drive.workspace` per principal — the tree, the ACL, the quota and
   ;; the version history. The bytes those versions point at are not in here;
   ;; they are in an object store. See `cloud.itonami.app.documents`.
   :drive {:workspaces {}}
   :datoms []
   :events []
   :last-response nil})

(defn state-file []
  (io/file (config/data-dir) "state.edn"))

(defn- write-state-file! [value backup?]
  (let [file (state-file)
        temporary (io/file (.getParentFile file) "state.edn.tmp")]
    (.mkdirs (.getParentFile file))
    (installation/restrict-directory! (.getParentFile file))
    (when (and backup? (.isFile file))
      (recovery/backup! (.getParentFile file)
                        (Files/readAllBytes (.toPath file))))
    (spit temporary (pr-str value))
    (installation/restrict-file! temporary)
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    (installation/restrict-file! file))
  value)

(defn- load-state []
  (let [file (state-file)]
    (if (.isFile file)
      (let [loaded (edn/read-string (slurp file))
            value (merge (initial-state) loaded)]
        (when-not (:installation loaded)
          (write-state-file! value false))
        value)
      (initial-state))))

(defonce state (atom (load-state)))

(defn snapshot [] @state)

(defn- persist! [value]
  (write-state-file! value true))

(defn transact! [f & args]
  (locking state
    (let [next-value (apply swap! state f args)]
      (persist! next-value))))

(defn new-id [prefix]
  (str prefix "-" (UUID/randomUUID)))

(defn now [] (str (Instant/now)))

(defn session-messages [session-id]
  (get-in @state [:sessions session-id :messages] []))

(defn- summary-text [value limit fallback]
  (let [text (some-> value str (str/replace #"\s+" " ") str/trim)
        text (if (str/blank? text) fallback text)]
    (if (> (count text) limit)
      (str (subs text 0 (max 0 (dec limit))) "…")
      text)))

(defn session-summaries
  ([]
   (session-summaries @state))
  ([value]
   (->> (:sessions value)
        (map (fn [[session-id session]]
               (let [messages (:messages session)
                     first-user (some #(when (= "user" (:role %)) %) messages)
                     latest (last messages)]
                 {:id session-id
                  :title (summary-text (:content first-user) 48 "新しい会話")
                  :preview (summary-text (:content latest) 80 "まだメッセージはありません")
                  :updated-at (:updated-at session)
                  :message-count (count messages)
                  :providers (->> (keys (get-in value [:runner-sessions session-id]))
                                  (map name)
                                  sort
                                  vec)})))
        (sort-by (juxt :updated-at :id) #(compare %2 %1))
        vec)))

(defn runner-session [session-id provider-id]
  (get-in @state [:runner-sessions session-id provider-id]))

(defn record-runner-session! [session-id provider-id runner-session-id]
  (when (and session-id provider-id runner-session-id)
    (transact! assoc-in
               [:runner-sessions session-id provider-id]
               {:id runner-session-id :updated-at (now)}))
  runner-session-id)

(defn record-agent-event! [event max-events]
  (transact!
   (fn [value]
     (let [run-id (:run/id event)
           event-type (:event/type event)
           event-data (:event/data event)]
       (-> value
           (update-in [:agent-loops :events]
                      #(vec (take-last max-events (conj (or % []) event))))
           (assoc-in [:agent-loops :runs run-id]
                     (merge (get-in value [:agent-loops :runs run-id])
                            {:schema "cloud.itonami.app.agent-loop.v1"
                             :id run-id
                             :session-id (:session/id event)
                             :status (or (:status event-data)
                                         (if (= event-type :run/started)
                                           :running
                                           (get-in value
                                                   [:agent-loops :runs run-id
                                                    :status])))
                             :phase (or (:phase event-data)
                                        (get-in value
                                                [:agent-loops :runs run-id
                                                 :phase]))
                             :updated-at (:event/at event)}))))))
  event)

(defn agent-events
  ([session-id]
   (agent-events session-id 100))
  ([session-id limit]
   (->> (get-in @state [:agent-loops :events] [])
        (filter #(= session-id (:session/id %)))
        (take-last limit)
        vec)))

(defn update-agent-control! [f & args]
  (apply transact! update :agent-control f args))

(defn append-message!
  [session-id {:keys [role content] :as message} max-messages]
  (let [message-id (or (:id message) (new-id "msg"))
        recorded (assoc message :id message-id :at (or (:at message) (now)))]
    (transact!
     (fn [s]
       (let [messages (conj (vec (get-in s [:sessions session-id :messages] []))
                            recorded)
             kept (vec (take-last max-messages messages))
             datoms (-> (:datoms s)
                        (kgraph/assert-datom [message-id :message/session session-id])
                        (kgraph/assert-datom [message-id :message/role role])
                        (kgraph/assert-datom [message-id :message/content content]))]
         (-> s
             (assoc-in [:sessions session-id]
                       {:id session-id :updated-at (now) :messages kept})
             (assoc :datoms datoms)))))
    recorded))

(defn record-response! [response]
  (transact!
   (fn [s]
     (-> s
         (assoc :last-response response)
         (update :events #(vec (take-last 100
                                         (conj (or % [])
                                               {:type :chat/completed
                                                :at (now)
                                                :provider (:provider response)
                                                :model (:model response)}))))))))

(defn clear-session! [session-id]
  (transact!
   (fn [state]
     (-> state
         (update :sessions dissoc session-id)
         (update :runner-sessions dissoc session-id)))))
