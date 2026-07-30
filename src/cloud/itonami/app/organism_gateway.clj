(ns cloud.itonami.app.organism-gateway
  "Read-only, organization-scoped projection of externally supervised AOs.

  Tamaki's append-only EDN stream remains authoritative. The gateway uses an
  opaque byte cursor and projects only allow-listed lifecycle metadata."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.organism-worker :as organism-worker])
  (:import [java.io RandomAccessFile]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util UUID]))

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

(defn tamaki-state-root []
  (io/file
   (or (System/getenv "CLOUD_ITONAMI_TAMAKI_STATE_DIR")
       (io/file (tamaki-root) ".tamaki"))))

(defn- assignment-files []
  (let [directory (io/file (tamaki-root) "organisms")]
    (->> (or (.listFiles directory) (make-array java.io.File 0))
         (filter #(and (.isFile %)
                       (str/ends-with? (.getName %) ".edn")))
         (sort-by #(.getName %)))))

(defn- read-assignment [file]
  (try
    (let [value (-> file slurp edn/read-string)]
      (when (or (= organism-worker/schema (:ao.worker/schema value))
                (= :artificial-organism (:ao.worker/kind value)))
        (organism-worker/assignment value)))
    (catch Exception _ nil)))

(defn assignments []
  (->> (assignment-files) (keep read-assignment) vec))

(defn- events-file []
  (io/file (tamaki-state-root) "events.edn"))

(defn assignment
  ([] (first (assignments)))
  ([worker-id]
   (some #(when (= worker-id (:ao.worker/id %)) %) (assignments))))

(defn directory
  "List externally assigned AOs visible to one organization slug."
  [organization]
  (let [workers (assignments)
        organization (str/lower-case (str organization))
        visible (->> workers
                     (filter #(= organization
                                 (str/lower-case
                                  (str (:ao.worker/organization %)))))
                     (mapv organism-worker/public-assignment))]
    (cond-> {:schema "cloud.itonami.app.organism-directory.v1"
             :organization organization
             :items visible}
      (empty? workers) (assoc :state :not-configured))))

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
  (let [tail (max 0 (- length default-tail-bytes))]
    (try
      (if (str/blank? (str cursor))
        tail
        (let [value (Long/parseLong (str cursor))]
          ;; A cursor beyond EOF means the authority file was truncated or
          ;; rotated. Resume from the bounded tail instead of waiting forever
          ;; at the new EOF.
          (if (<= 0 value length) value tail)))
      (catch Exception _ tail))))

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

(def intent-receipt-schema "kotoba.ao.worker-intent-receipt.v1")
(def allowed-payload-keys
  #{:type :summary :target :reference :decision :reason})
(def max-intent-summary-characters 4000)

(defn- workplace-root []
  (io/file (tamaki-state-root) "workplace"))

(defn- private-directory [kind]
  (io/file (workplace-root) (name kind)))

(defn- safe-id [value]
  (let [value (str value)]
    (when-not (re-matches #"[A-Za-z0-9._:-]{1,160}" value)
      (throw (ex-info "invalid organism intent id"
                      {:type :ao.intent/invalid :id value})))
    value))

(defn- canonical-payload [payload]
  (let [payload (select-keys (or payload {}) allowed-payload-keys)
        summary (some-> (:summary payload) str)]
    (cond-> (into (sorted-map) payload)
      summary
      (assoc :summary
             (subs summary 0 (min (count summary)
                                  max-intent-summary-characters))))))

(defn- sha256 [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value) StandardCharsets/UTF_8))]
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))))

(defn- write-edn-atomically! [file value]
  (let [parent (.getParentFile file)
        temporary (io/file parent (str "." (.getName file) "."
                                        (UUID/randomUUID) ".tmp"))]
    (.mkdirs parent)
    (spit temporary (str (pr-str value) "\n"))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    value))

(defn- safe-receipt-evidence [receipt]
  (let [evidence (:receipt/evidence receipt)]
    (cond-> {}
      (:agent.run/id evidence)
      (assoc :run-id (:agent.run/id evidence))
      (:agent.run/status evidence)
      (assoc :run-status (name (:agent.run/status evidence)))
      (seq (:loops/stopped evidence))
      (assoc :stopped-loops (mapv str (:loops/stopped evidence))))))

(defn- public-receipt [receipt]
  (let [evidence (safe-receipt-evidence receipt)
        public
        (select-keys receipt
                     [:receipt/schema :receipt/id :receipt/worker
                      :receipt/organization :receipt/intent
                      :receipt/capability :receipt/status
                      :receipt/effect-status :receipt/payload-hash
                      :receipt/parent :receipt/decision
                      :receipt/next-gates :receipt/reason
                      :receipt/created-at :receipt/updated-at])
        public (cond-> public
                 (seq evidence) (assoc :receipt/evidence evidence))
        qualified-name
        (fn [value]
          (when value
            (if-let [prefix (namespace value)]
              (str prefix "/" (name value))
              (name value))))]
    (cond-> public
      (:receipt/capability public)
      (update :receipt/capability qualified-name)
      (:receipt/status public)
      (update :receipt/status qualified-name)
      (:receipt/effect-status public)
      (update :receipt/effect-status qualified-name)
      (:receipt/decision public)
      (update :receipt/decision qualified-name)
      (:receipt/reason public)
      (update :receipt/reason qualified-name)
      (:receipt/next-gates public)
      (update :receipt/next-gates #(mapv qualified-name %)))))

(defn submit-intent!
  "Atomically place an admitted intent in Tamaki's private workplace inbox.
  The returned receipt proves admission only; it never claims execution."
  [worker-id intent now-ms]
  (let [worker (assignment worker-id)]
    (when-not worker
      (throw (ex-info "organism worker was not found"
                      {:type :ao.worker/not-found :id worker-id})))
    (let [payload (canonical-payload (:intent/payload intent))
          intent-id (safe-id (or (:intent/id intent)
                                 (str "intent-" (UUID/randomUUID))))
          prepared (-> intent
                       (assoc :intent/id intent-id
                              :intent/worker worker-id
                              :intent/payload payload
                              :intent/payload-hash (sha256 payload))
                       (dissoc :intent/status :intent/effect-status))
          decision (organism-worker/intent-decision worker prepared now-ms)]
      (when-not (= :admitted (:intent/status decision))
        (throw (ex-info "organism intent was rejected"
                        {:type :ao.intent/rejected
                         :reason (:intent/reason decision)})))
      (let [receipt-id (str "receipt-" (UUID/randomUUID))
            envelope (merge prepared decision
                            {:intent/received-at now-ms
                             :intent/next-gates
                             (or (get-in worker
                                         [:ao.worker/intents :required-gates])
                                 [:incarnation-lease :capability :authority
                                  :homeostasis :hil])})
            receipt {:receipt/schema intent-receipt-schema
                     :receipt/id receipt-id
                     :receipt/worker worker-id
                     :receipt/organization (:intent/organization envelope)
                     :receipt/intent intent-id
                     :receipt/capability (:intent/capability envelope)
                     :receipt/status :admitted
                     :receipt/effect-status :not-executed
                     :receipt/payload-hash (:intent/payload-hash envelope)
                     :receipt/parent (:intent/parent envelope)
                     :receipt/decision (get-in envelope
                                               [:intent/payload :decision])
                     :receipt/next-gates (:intent/next-gates envelope)
                     :receipt/created-at now-ms
                     :receipt/updated-at now-ms}]
        (write-edn-atomically!
         (io/file (private-directory :inbox) (str intent-id ".edn"))
         envelope)
        (write-edn-atomically!
         (io/file (private-directory :receipts) (str intent-id ".edn"))
         receipt)
        (public-receipt receipt)))))

(defn receipts
  "Return redacted receipts emitted by admission or the external supervisor."
  [worker-id]
  (when-not (assignment worker-id)
    (throw (ex-info "organism worker was not found"
                    {:type :ao.worker/not-found :id worker-id})))
  (let [directory (private-directory :receipts)]
    {:schema "cloud.itonami.app.organism-receipts.v1"
     :worker worker-id
     :items
     (->> (or (.listFiles directory) (make-array java.io.File 0))
          (filter #(.isFile %))
          (keep (fn [file]
                  (try
                    (let [receipt (-> file slurp edn/read-string)]
                      (when (= worker-id (:receipt/worker receipt))
                        (public-receipt receipt)))
                    (catch Exception _ nil))))
          (sort-by (juxt :receipt/updated-at :receipt/id))
          reverse
          (take 100)
          vec)}))

(defn snapshot [worker-id]
  (let [worker (assignment worker-id)]
    (when-not worker
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
