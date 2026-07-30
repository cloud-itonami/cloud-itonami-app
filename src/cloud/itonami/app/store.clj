(ns cloud.itonami.app.store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cloud.itonami.app.config :as config]
            [kotoba.kgraph :as kgraph])
  (:import [java.nio.file Files StandardCopyOption]
           [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.state.v1")
(def max-state-backups 10)

(defn initial-state []
  {:schema schema
   :agents [{:id "local" :name "Local" :system-prompt
             "You are a private, local-first assistant. Be concise and useful."}]
   :sessions {}
   ;; One `drive.workspace` per principal — the tree, the ACL, the quota and
   ;; the version history. The bytes those versions point at are not in here;
   ;; they are in an object store. See `cloud.itonami.app.documents`.
   :drive {:workspaces {}}
   :agent-control {:settings {} :runs {} :schedules {} :watchers {}
                   :watcher-sources {} :events []}
   :memory-capsules {}
   :memory-distillation {}
   :datoms []
   :events []
   :last-response nil})

(defn state-file []
  (io/file (config/data-dir) "state.edn"))

(defn- backup-directory []
  (io/file (config/data-dir) "state-backups"))

(defn- quarantine-directory []
  (io/file (config/data-dir) "state-quarantine"))

(defn- restrict-to-owner! [file]
  (.setReadable file false false)
  (.setWritable file false false)
  (.setExecutable file false false)
  (.setReadable file true true)
  (.setWritable file true true)
  (when (.isDirectory file)
    (.setExecutable file true true))
  file)

(defn- read-state-file [file]
  (let [value (edn/read-string (slurp file))]
    (when-not (and (map? value) (= schema (:schema value)))
      (throw (ex-info "State file has an unsupported schema."
                      {:path (.getPath file) :schema (:schema value)})))
    (merge (initial-state) value)))

(defn- files-newest-first [directory]
  (if (.isDirectory directory)
    (->> (.listFiles directory)
         (filter #(.isFile %))
         (sort-by #(.lastModified %) >))
    []))

(defn- backup-files []
  (files-newest-first (backup-directory)))

(defn- load-state []
  (let [primary (state-file)]
    (if-not (.isFile primary)
      (initial-state)
      (try
        (read-state-file primary)
        (catch Exception primary-error
          (or (some (fn [backup]
                      (try (read-state-file backup)
                           (catch Exception _ nil)))
                    (backup-files))
              (throw
               (ex-info
                "State file is invalid and no valid backup is available."
                {:path (.getPath primary)
                 :backup-count (count (backup-files))}
                primary-error))))))))

(defonce state (atom (load-state)))

(defn snapshot [] @state)

(defn- preserve-current-state! [file]
  (when (.isFile file)
    (try
      (read-state-file file)
      (let [directory (backup-directory)
            backup (io/file directory
                            (str "state-" (System/currentTimeMillis) "-"
                                 (UUID/randomUUID) ".edn"))]
        (.mkdirs directory)
        (restrict-to-owner! directory)
        (Files/copy (.toPath file) (.toPath backup)
                    (into-array StandardCopyOption
                                [StandardCopyOption/COPY_ATTRIBUTES]))
        (restrict-to-owner! backup))
      (catch Exception _
        (let [directory (quarantine-directory)
              quarantined
              (io/file directory
                       (str "state-corrupt-" (System/currentTimeMillis) "-"
                            (UUID/randomUUID) ".edn"))]
          (.mkdirs directory)
          (restrict-to-owner! directory)
          (Files/copy (.toPath file) (.toPath quarantined)
                      (make-array StandardCopyOption 0))
          (restrict-to-owner! quarantined)
          (doseq [old (drop 3 (files-newest-first directory))]
            (Files/deleteIfExists (.toPath old))))))))

(defn- prune-state-backups! []
  (doseq [file (drop max-state-backups (backup-files))]
    (Files/deleteIfExists (.toPath file))))

(defn- persist! [value]
  (let [file (state-file)
        parent (.getParentFile file)]
    (.mkdirs parent)
    (restrict-to-owner! parent)
    (let [temporary
          (Files/createTempFile
           (.toPath parent) "state-" ".edn.tmp"
           (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (.toFile temporary) (pr-str value))
        (read-state-file (.toFile temporary))
        (restrict-to-owner! (.toFile temporary))
        (preserve-current-state! file)
        (Files/move temporary (.toPath file)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/ATOMIC_MOVE]))
        (restrict-to-owner! file)
        (prune-state-backups!)
        (finally
          (Files/deleteIfExists temporary)))))
  value)

(defn transact! [f & args]
  (locking state
    (let [next-value (apply swap! state f args)]
      (persist! next-value))))

(defn new-id [prefix]
  (str prefix "-" (UUID/randomUUID)))

(def ^:private instant-format
  "ISO-8601 with exactly six fractional digits, always.

  `DateTimeFormatter/ISO_INSTANT` and `Instant/toString` both omit trailing
  zeros, which is fine to read and wrong to sort."
  (-> (java.time.format.DateTimeFormatterBuilder.)
      (.appendInstant 6)
      (.toFormatter)))

(defonce ^:private last-instant
  ;; The last instant `now` handed out, so the next one is always after it.
  (atom nil))

(defn now
  "The current instant, as a string, and strictly after the previous one.

  `Instant/now` is neither of the things this Drive assumes about it. It has
  microsecond resolution, so two operations in the same microsecond get the
  same timestamp — and versions, comments and the document listing are all
  ordered by these, so a tie is an order nothing decides. It is also not
  monotonic: an NTP correction can move it backwards, and a version stamped
  before the one it replaced is a history that reads wrong for ever.

  Stepping by a microsecond on a tie makes every ordering total by
  construction. A burst of a thousand operations in one instant ends a
  millisecond ahead of the clock and converges as soon as real time passes,
  which is a smaller error than two events that cannot be told apart.

  And it is formatted with a **fixed six fractional digits**, because these
  strings are compared as strings. `Instant/toString` drops trailing zeros in
  groups of three, so a timestamp lands on `…:00.123Z` about once in eleven
  hundred instead of `…:00.123456Z` — and `\"Z\"` sorts after `\"4\"`, so that
  one sorts *after* every longer timestamp in its own second. The order is
  then the opposite of the truth, in a listing and in the keyset cursor built
  from it. Measured: 446 of 500,000 printed with three digits and one with
  none.

  Timestamps written before this change keep their own widths and compare
  with each other exactly as badly as they always did; what stops is new ones
  joining them.

  This is not the whole answer to ordering — `documents` sorts by timestamp
  *and* id as well, because two processes do not share this atom. It is the
  answer within one, which is where the ambiguity was observable."
  []
  (.format instant-format
           (swap! last-instant
                  (fn [previous]
                    (let [candidate (Instant/now)]
                      (if (and previous (not (.isAfter candidate ^Instant previous)))
                        (.plusNanos ^Instant previous 1000)
                        candidate))))))

(defn session-messages [session-id]
  (get-in @state [:sessions session-id :messages] []))

(defn- session-entity-ids [datoms session-id]
  (into #{}
        (keep (fn [[entity attribute value]]
                (when (and (= :message/session attribute)
                           (= session-id value))
                  entity)))
        datoms))

(defn- entity-message [datoms entity]
  (let [attributes
        (into {}
              (map (fn [[_ attribute value]] [attribute value]))
              (kgraph/get-objects datoms entity))]
    {:id entity
     :role (:message/role attributes)
     :content (:message/content attributes)
     :at (:message/at attributes)
     :sequence (:message/sequence attributes)}))

(defn- graph-session-messages [datoms session-id]
  (->> (session-entity-ids datoms session-id)
       (map #(entity-message datoms %))
       (sort-by (juxt #(or (:sequence %) -1)
                      #(or (:at %) "")
                      :id))
       vec))

(defn session-memory
  "Return bounded long-term chat history reconstructed from kgraph."
  [session-id]
  (graph-session-messages (:datoms @state) session-id))

(defn- remove-entities [datoms entities]
  (if (empty? entities)
    datoms
    (vec (remove #(contains? entities (first %)) datoms))))

(defn append-message!
  ([session-id message max-messages]
   (append-message! session-id message max-messages 500))
  ([session-id {:keys [role content] :as message}
    max-messages max-memory-messages]
   (when-not (and (pos-int? max-messages)
                  (pos-int? max-memory-messages)
                  (<= max-messages max-memory-messages))
     (throw (ex-info "Message retention limits are invalid."
                     {:type :memory/invalid-retention})))
   (let [message-id (or (:id message) (new-id "msg"))
         recorded-at (or (:at message) (now))
         result (volatile! nil)]
    (transact!
     (fn [s]
       (let [sequence
             (long (get-in s [:sessions session-id :next-sequence] 0))
             recorded (assoc message :id message-id :at recorded-at
                             :sequence sequence)
             messages (conj (vec (get-in s [:sessions session-id :messages] []))
                            recorded)
             kept (vec (take-last max-messages messages))
             datoms (-> (:datoms s)
                        (kgraph/assert-datom [message-id :message/session session-id])
                        (kgraph/assert-datom [message-id :message/role role])
                        (kgraph/assert-datom [message-id :message/content content])
                        (kgraph/assert-datom [message-id :message/at recorded-at])
                        (kgraph/assert-datom
                         [message-id :message/sequence sequence]))
             expired (->> (graph-session-messages datoms session-id)
                          (take (max 0 (- (count
                                           (graph-session-messages datoms session-id))
                                         max-memory-messages)))
                          (map :id)
                          set)
             datoms (remove-entities datoms expired)]
         (vreset! result recorded)
         (-> s
             (assoc-in [:sessions session-id]
                       {:id session-id :updated-at (now) :messages kept
                        :next-sequence (inc sequence)})
             (assoc :datoms datoms)))))
    @result)))

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
   (fn [s]
     (let [entities (session-entity-ids (:datoms s) session-id)]
       (-> s
           (update :sessions dissoc session-id)
           (update :datoms remove-entities entities)
           (update :memory-capsules dissoc session-id)
           (update :memory-distillation dissoc session-id))))))

(defn agent-control [] (:agent-control @state))

(defn update-agent-control! [f & args]
  (apply transact! update :agent-control f args))
