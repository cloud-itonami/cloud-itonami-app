(ns cloud.itonami.app.store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cloud.itonami.app.config :as config]
            [kotoba.kgraph :as kgraph])
  (:import [java.nio.file Files StandardCopyOption]
           [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.state.v1")

(defn initial-state []
  {:schema schema
   :agents [{:id "local" :name "Local" :system-prompt
             "You are a private, local-first assistant. Be concise and useful."}]
   :sessions {}
   ;; One `drive.workspace` per principal — the tree, the ACL, the quota and
   ;; the version history. The bytes those versions point at are not in here;
   ;; they are in an object store. See `cloud.itonami.app.documents`.
   :drive {:workspaces {}}
   :datoms []
   :events []
   :last-response nil})

(defn state-file []
  (io/file (config/data-dir) "state.edn"))

(defn- load-state []
  (let [file (state-file)]
    (if (.isFile file)
      (merge (initial-state) (edn/read-string (slurp file)))
      (initial-state))))

(defonce state (atom (load-state)))

(defn snapshot [] @state)

(defn- persist! [value]
  (let [file (state-file)
        temporary (io/file (.getParentFile file) "state.edn.tmp")]
    (.mkdirs (.getParentFile file))
    (spit temporary (pr-str value))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE])))
  value)

(defn transact! [f & args]
  (locking state
    (let [next-value (apply swap! state f args)]
      (persist! next-value))))

(defn new-id [prefix]
  (str prefix "-" (UUID/randomUUID)))

(defn now [] (str (Instant/now)))

(defn session-messages [session-id]
  (get-in @state [:sessions session-id :messages] []))

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
  (transact! update :sessions dissoc session-id))
