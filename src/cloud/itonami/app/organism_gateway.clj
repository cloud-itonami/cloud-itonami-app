(ns cloud.itonami.app.organism-gateway
  "Read-only, organization-scoped projection of externally supervised AOs.

  Tamaki's append-only EDN stream remains authoritative. The gateway uses an
  opaque byte cursor and projects only allow-listed lifecycle metadata."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.organism-worker :as organism-worker])
  (:import [java.io RandomAccessFile]
           [java.nio.charset StandardCharsets]))

(def activity-schema "cloud.itonami.app.organism-activity.v1")
(def snapshot-schema "cloud.itonami.app.organism-snapshot.v1")
(def default-tail-bytes (* 256 1024))
(def max-activity-limit 200)

(defn workspace-root []
  (io/file
   (or (System/getenv "CLOUD_ITONAMI_WORKSPACE_ROOT")
       (.getCanonicalPath
        (io/file (System/getProperty "user.dir") "../../..")))))

(defn tamaki-root []
  (io/file
   (or (System/getenv "CLOUD_ITONAMI_TAMAKI_ROOT")
       (io/file (workspace-root) "orgs/etzhayyim/tamaki"))))

(defn- assignment-file []
  (io/file (tamaki-root) "organisms/cloud-itonami-worker.edn"))

(defn- events-file []
  (io/file (or (System/getenv "CLOUD_ITONAMI_TAMAKI_STATE_DIR")
               (io/file (tamaki-root) ".tamaki"))
           "events.edn"))

(defn assignment []
  (let [file (assignment-file)]
    (when (.isFile file)
      (-> file slurp edn/read-string organism-worker/assignment))))

(defn directory
  "List externally assigned AOs visible to one organization slug."
  [organization]
  (if-let [worker (assignment)]
    (if (= (str/lower-case (str organization))
           (str/lower-case (str (:ao.worker/organization worker))))
      {:schema "cloud.itonami.app.organism-directory.v1"
       :organization organization
       :items [(organism-worker/public-assignment worker)]}
      {:schema "cloud.itonami.app.organism-directory.v1"
       :organization organization
       :items []})
    {:schema "cloud.itonami.app.organism-directory.v1"
     :organization organization
     :items []
     :state :not-configured}))

(def safe-event-data-keys
  #{:actor/id :actor/replica :loop/cycle :reason
    :service/id :service/domain :service/open-issues
    :evaluation/status :evaluation/score :evaluation/confidence
    :agent.run/actor :agent.run/runner :agent.run/model :agent.run/status
    :agent.run/replica :agent.run/exit})

(defn- safe-event-data [data]
  (let [run (:run data)]
    (cond-> (select-keys data safe-event-data-keys)
      (map? run)
      (assoc :agent
             (select-keys run
                          [:agent.run/id :agent.run/actor :agent.run/runner
                           :agent.run/model :agent.run/status
                           :agent.run/replica])))))

(defn- stream-for [kind]
  (let [prefix (some-> kind namespace)]
    (case prefix
      "agent.activity" :activity
      "run" :lifecycle
      "loop" :system
      "organism" :system
      "result" :result
      "review" :review
      "patch" :source
      "issue" :issue
      :system)))

(defn- project-event [event cursor]
  {:activity/id (:tamaki.event/id event)
   :activity/cursor (str cursor)
   :activity/at (:tamaki.event/at event)
   :activity/run (:tamaki.event/run event)
   :activity/parent (:tamaki.event/parent event)
   :activity/kind (:tamaki.event/kind event)
   :activity/stream (stream-for (:tamaki.event/kind event))
   :activity/data (safe-event-data (:tamaki.event/data event))})

(defn- parse-event [line]
  (try
    (edn/read-string line)
    (catch Exception _ nil)))

(defn- cursor-value [cursor length]
  (try
    (let [value (if (str/blank? (str cursor))
                  (max 0 (- length default-tail-bytes))
                  (Long/parseLong (str cursor)))]
      (min length (max 0 value)))
    (catch Exception _ length)))

(defn activity
  "Read at most `limit` complete events after an opaque byte cursor.
  An absent cursor starts near the tail so opening the UI never folds the
  complete organism memory."
  ([cursor] (activity cursor 100))
  ([cursor limit]
   (let [file (events-file)
         limit (min max-activity-limit (max 1 (long limit)))]
     (if-not (.isFile file)
       {:schema activity-schema :cursor "0" :items [] :state :offline}
       (with-open [reader (RandomAccessFile. file "r")]
         (let [length (.length reader)
               requested (cursor-value cursor length)]
           (.seek reader requested)
           ;; A tail-derived cursor may begin in the middle of a UTF-8 line.
           ;; A cursor returned by this function is always at a line boundary.
           (when (and (nil? cursor) (pos? requested))
             (.readLine reader))
           (loop [items []]
             (if (>= (count items) limit)
               {:schema activity-schema
                :cursor (str (.getFilePointer reader))
                :items items}
               (let [line (.readLine reader)]
                 (if (nil? line)
                   {:schema activity-schema
                    :cursor (str (.getFilePointer reader))
                    :items items}
                   (let [cursor (.getFilePointer reader)
                         utf8 (String. (.getBytes line
                                                 StandardCharsets/ISO_8859_1)
                                       StandardCharsets/UTF_8)
                         event (parse-event utf8)]
                     (recur (cond-> items
                              event (conj (project-event event cursor)))))))))))))))

(defn snapshot [worker-id]
  (let [worker (assignment)]
    (when-not (= worker-id (:ao.worker/id worker))
      (throw (ex-info "organism worker was not found"
                      {:type :ao.worker/not-found :id worker-id})))
    (let [recent (activity nil 50)
          items (:items recent)
          latest (last items)
          actor-runs (->> items
                          (keep #(get-in % [:activity/data :agent]))
                          (map :agent.run/id)
                          distinct
                          count)]
      {:schema snapshot-schema
       :worker (organism-worker/public-assignment worker)
       :connection {:state (if (= :offline (:state recent))
                             :offline :observed)
                    :cursor (:cursor recent)
                    :event-authority :tamaki-append-only}
       :activity {:recent (count items)
                  :agent-runs actor-runs
                  :last-at (:activity/at latest)
                  :last-kind (:activity/kind latest)}})))
