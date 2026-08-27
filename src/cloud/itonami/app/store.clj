(ns cloud.itonami.app.store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.work-partition-store :as work-partitions]
            [kotoba.kgraph :as kgraph]
            [langchain.edn-persist :as edn-persist]
            [cloud.itonami.app.host :as host])
  (:import [java.time Instant]
           [java.util UUID]))

(def schema "cloud.itonami.app.state.v1")
(def journal-schema "cloud.itonami.app.state-journal.v1")

(def ^:dynamic *journal-max-bytes* (* 4 1024 1024))
(def ^:dynamic *journal-max-entries* 256)
(defonce ^:private journal-entry-counts (atom {}))

(def ^:dynamic *environment*
  "Environment lookup seam. Repository deployments inject the same canonical
  state file used by actors; ordinary desktop runs keep the legacy data dir."
  #(System/getenv %))

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
  (io/file (or (not-empty (*environment* "KOTOBA_REPOSITORY_STATE_FILE"))
               (.getPath (io/file (config/data-dir) "state.edn")))))

(defn- repository-mode? []
  (boolean (not-empty (*environment* "KOTOBA_REPOSITORY_STATE_FILE"))))

(defn journal-file []
  (let [file (state-file)
        name (.getName file)
        stem (if (.endsWith name ".edn")
               (subs name 0 (- (count name) 4))
               name)]
    (io/file (.getParentFile file) (str stem ".journal.edn"))))

(defn- apply-op [state {:keys [op path value]}]
  (case op
    :assoc (if (seq path) (assoc-in state path value) value)
    :dissoc (if (= 1 (count path))
              (dissoc state (first path))
              (update-in state (pop path) dissoc (peek path)))
    (throw (ex-info "Unknown state journal operation"
                    {:type :store/invalid-journal-operation :op op}))))

(defn- state-delta
  "Idempotent path operations from `before` to `after`.

  Persistent Clojure updates retain object identity for untouched branches, so
  this walks only changed paths in the common case. Vectors and scalar leaves
  are replaced as one value; replaying a record twice therefore has the same
  result as replaying it once, which makes snapshot-before-truncate crashes
  harmless."
  [before after]
  (letfn [(walk [path a b]
            (cond
              (identical? a b) []
              (and (map? a) (map? b))
              (mapcat (fn [k]
                        (cond
                          (not (contains? b k)) [{:op :dissoc :path (conj path k)}]
                          (not (contains? a k)) [{:op :assoc :path (conj path k)
                                                  :value (get b k)}]
                          :else (walk (conj path k) (get a k) (get b k))))
                      (set/union (set (keys a)) (set (keys b))))
              (= a b) []
              :else [{:op :assoc :path path :value b}]))]
    (vec (walk [] before after))))

(defn- journal-data [file]
  (if (.isFile file)
    (let [content (slurp file)]
      {:lines (vec (str/split-lines content))
       :terminated? (or (empty? content) (str/ends-with? content "\n"))})
    {:lines [] :terminated? true}))

(defn- replay-journal [base]
  (let [file (journal-file)
        {:keys [lines terminated?]} (journal-data file)
        last-index (dec (count lines))]
    (loop [value base index 0 applied 0]
      (if (= index (count lines))
        (do (swap! journal-entry-counts assoc (.getPath file) applied) value)
        (let [line (nth lines index)]
          (if (str/blank? line)
            (recur value (inc index) applied)
            (let [{:keys [record error]}
                  (try
                    {:record (edn/read-string line)}
                    (catch Exception error {:error error}))]
              (if-not error
                (do
                  (when-not (= journal-schema (:schema record))
                    (throw (ex-info "Unknown state journal schema"
                                    {:type :store/invalid-journal-schema
                                     :schema (:schema record)})))
                  (recur (reduce apply-op value (:ops record))
                         (inc index) (inc applied)))
                ;; `append-durable!` fsyncs complete lines. A power loss can
                ;; still leave only the final line torn; every earlier corrupt
                ;; record is a real integrity failure and must stop startup.
                (if (and (= index last-index) (not terminated?))
                  (do
                    (binding [*out* *err*]
                      (println "WARNING ignored incomplete final state journal record"))
                    (swap! journal-entry-counts assoc (.getPath file) applied)
                    value)
                  (throw (ex-info "Invalid state journal record"
                                  {:type :store/invalid-journal
                                   :file (.getPath file)
                                   :line (inc index)}
                                  error)))))))))))

(defn- load-state []
  (let [file (state-file)
        base (if (.isFile file)
               (merge (initial-state) (edn/read-string (slurp file)))
               (initial-state))
        main (if (repository-mode?) base (replay-journal base))]
    (if-let [work (work-partitions/load-ledger (:work-governance main))]
      (assoc main :work-governance work)
      main)))

(defonce state (atom (load-state)))

(defn snapshot [] @state)

(defn- persist-snapshot! [value]
  (let [file (state-file)
        work (:work-governance value)]
    (.mkdirs (.getParentFile file))
    ;; Governed work has an independent physical tenant boundary. Persist it
    ;; before the main store: a crash may leave an unreferenced AgentRun, but
    ;; can never leave an AgentRun dispatch without its durable intent.
    (when work (work-partitions/persist-ledger! work))
    ;; Confined write under the state file's parent (kotoba-lang/fs), then
    ;; same-directory atomic rename. No ambient spit of the durable bytes.
    (host/write-atomic! file (pr-str (dissoc value :work-governance))
                        host/store-max-bytes))
  value)

(defn- persist-work! [value]
  (when-let [work (:work-governance value)]
    (work-partitions/persist-ledger! work)))

(defn- checkpoint! [value]
  (persist-snapshot! value)
  (host/write-atomic! (journal-file) "" host/store-max-bytes)
  (swap! journal-entry-counts assoc (.getPath (journal-file)) 0)
  value)

(defn- persist-delta! [before after]
  (persist-work! after)
  (let [before-main (dissoc before :work-governance)
        after-main (dissoc after :work-governance)
        ops (state-delta before-main after-main)]
    (when (seq ops)
      (let [file (journal-file)
            record (str (pr-str {:schema journal-schema
                                :at (str (Instant/now))
                                :ops ops}) "\n")]
        (.mkdirs (.getParentFile file))
        (host/append-durable! file record host/store-max-bytes)
        (let [entries (get (swap! journal-entry-counts update (.getPath file)
                                  (fnil inc 0))
                           (.getPath file))]
          (when (or (>= entries *journal-max-entries*)
                    (>= (.length file) *journal-max-bytes*))
            (checkpoint! after))))))
  after)

(defn transact! [f & args]
  (if (repository-mode?)
    (edn-persist/with-state-lock
     (state-file)
     (fn []
       (locking state
         (let [next-value (apply f (load-state) args)]
           (persist-snapshot! next-value)
           (reset! state next-value)))))
    (locking state
      (let [before @state
            next-value (apply f before args)]
        (persist-delta! before next-value)
        (reset! state next-value)))))

(defn update-agent-control!
  "Atomically update the durable Agent Control partition.

  Agent Control used this seam before it was reachable from the server. Keep
  the partition update inside the same state.edn transaction as every other
  app record so an AgentRun cannot be visible only in memory.

  Kept when ADR-0014's kanban runtime was replayed onto main: main's
  `transact!` had moved to `langchain.edn-persist`'s cross-process lock, which
  is the newer of the two, but nothing had ever defined this var — so
  `cloud.itonami.app.agent-control` called it at four sites and could not
  compile. It is listed in `namespaces_test`'s `known-broken` for exactly that
  reason."
  [f & args]
  (transact!
   (fn [s]
     (assoc s :agent-control
            (apply f (or (:agent-control s) {}) args)))))

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

(defn session-context-refs [session-id]
  (vec (get-in @state [:sessions session-id :context-refs] [])))

(defn set-session-context-refs! [session-id refs]
  (transact!
   (fn [s]
     (-> s
         (update-in [:sessions session-id]
                    #(merge {:id session-id :messages []} (or % {})
                            {:updated-at (now) :context-refs (vec refs)})))))
  (session-context-refs session-id))

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
             ;; Context belongs to the conversation, not to one message.
             ;; Preserve all session fields while appending the transcript.
             (update-in [:sessions session-id]
                        #(merge {:id session-id} (or % {})
                                {:updated-at (now) :messages kept}))
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
