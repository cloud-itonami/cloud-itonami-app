(ns cloud.itonami.app.store
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store-core :as core]
            [kotoba.kgraph :as kgraph]
            [cloud.itonami.app.work-partition-store :as work-partitions]
            [langchain.edn-persist :as edn-persist]
            [cloud.itonami.app.host :as host]
            [cloud.itonami.app.host-bounds :as host-bounds])
  (:import [java.time Instant]))

;; The schema string and the shape of a fresh state live in `store-core`, which
;; is `.cljc`. Re-exported here so the fifty-odd callers that say `store/schema`
;; and `store/initial-state` keep working: this namespace is the one they know,
;; and moving the definition should not move the name.
(def schema core/schema)

(def ^:dynamic *environment*
  "Environment lookup seam. Repository deployments inject the same canonical
  state file used by actors; ordinary desktop runs keep the legacy data dir."
  #(System/getenv %))

(defn initial-state [] (core/initial-state))

(defn state-file []
  (io/file (or (not-empty (*environment* "KOTOBA_REPOSITORY_STATE_FILE"))
               (.getPath (io/file (config/data-dir) "state.edn")))))

(defn- repository-mode? []
  (boolean (not-empty (*environment* "KOTOBA_REPOSITORY_STATE_FILE"))))

(def journal-schema "cloud.itonami.app.state-journal.v1")

(defn journal-file
  "The write-ahead journal beside the snapshot: `state.edn` -> `state.journal.edn`."
  []
  (let [file (state-file)
        name (.getName file)
        stem (if (str/ends-with? name ".edn")
               (subs name 0 (- (count name) 4))
               name)]
    (io/file (.getParentFile file) (str stem ".journal.edn"))))

(defonce ^:private journal-entry-count (atom 0))

(defn- snapshot-bytes [] (host/file-size (.getPath (state-file))))

(defn- sha256-hex
  "Hex SHA-256 of the UTF-8 bytes of S -- the same bytes `write-atomic!` puts
  on disk, so hashing the in-memory string at write time identifies the file
  without ever reading it back."
  [^String s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes s java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" %) digest))))

(defonce ^:private snapshot-digest
  ;; SHA-256 of the snapshot this process last wrote or loaded, or nil before
  ;; either. Journal records carry it as `:base-sha256` so `replay-journal`
  ;; can tell two snapshots apart even when they are coincidentally the same
  ;; LENGTH -- the one case the `:base-bytes` check cannot see
  ;; (ADR-2608291500 Phase 0).
  (atom nil))

(defn- apply-op [state {:keys [op path value from]}]
  (case op
    :assoc (if (seq path) (assoc-in state path value) value)
    :dissoc (if (= 1 (count path))
              (dissoc state (first path))
              (update-in state (pop path) dissoc (peek path)))
    ;; The grow-only-vector op (ADR-2608291500 Phase 1). Not a blind `into`:
    ;; replay must stay idempotent across a crash between the snapshot write
    ;; and the journal truncation, and an unconditional append would duplicate
    ;; the tail on that path. So the CURRENT length decides -- exactly `from`
    ;; means this record has not been applied; long enough AND carrying this
    ;; very tail means it already has; anything else means this journal is not
    ;; an increment of this state, and pretending otherwise corrupts it.
    :append
    (let [current (if (seq path) (get-in state path) state)]
      (cond
        (and (vector? current) (= (count current) from))
        (if (seq path)
          (update-in state path into value)
          (into current value))

        (and (vector? current)
             (>= (count current) (+ from (count value)))
             (= value (subvec current from (+ from (count value)))))
        state

        :else
        (throw (ex-info "state journal append does not fit this state"
                        {:type :store/invalid-journal-append
                         :path path :from from
                         :found (if (vector? current) (count current)
                                    (some-> current class str))}))))
    (throw (ex-info "Unknown state journal operation"
                    {:type :store/invalid-journal-operation :op op}))))

(defn state-delta
  "Idempotent path operations turning BEFORE into AFTER.

  Persistent Clojure updates keep object identity for untouched branches, so
  this walks only what actually changed. Scalar leaves are replaced whole; a
  vector that merely GREW at the end becomes an `:append` of its tail, and any
  other vector change is replaced whole. Both shapes keep replay idempotent --
  whole `:assoc` trivially, `:append` through the length-and-content guard in
  `apply-op` -- so a crash between the snapshot write and the journal
  truncation stays harmless.

  The append case is why this function exists in its current form: measured
  2026-08-29 (ADR-2608291500), 95% of journal bytes were whole-vector rewrites
  of `:messages` and `:job/events` vectors that had only grown, O(n²) over a
  run's life. The prefix test is cheap for the same reason the map walk is:
  structural sharing lets `=` win by identity on the shared prefix."
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
              (and (vector? a) (vector? b)
                   (< (count a) (count b))
                   (= a (subvec b 0 (count a))))
              [{:op :append :path path :from (count a)
                :value (vec (subvec b (count a)))}]
              :else [{:op :assoc :path path :value b}]))]
    (vec (walk [] before after))))

(defn- journal-records
  "Parsed records, and whether the file ended on a record boundary.

  `append-durable!` fsyncs whole lines, so only the FINAL line can be torn by a
  power loss. Any earlier unreadable record is a real integrity failure."
  [file]
  (if (.isFile file)
    (let [content (slurp file)
          lines (vec (remove str/blank? (str/split-lines content)))
          terminated? (or (empty? content) (str/ends-with? content "\n"))]
      {:lines lines :terminated? terminated?})
    {:lines [] :terminated? true}))

(defn- archive-orphan-journal!
  "Move a journal that does not belong to this snapshot aside, and say so.

  Not deleted: it is the only remaining copy of whatever the previous build
  recorded, and a person may want it. Not replayed either -- see
  `host-bounds/journal-belongs-to-snapshot?` for the hour-long silent rollback
  that is."
  [file base-bytes actual-bytes]
  (let [target (io/file (.getParentFile file)
                        (str (.getName file) ".orphan-"
                             (str/replace (str (Instant/now)) #"[:.]" "")))]
    (binding [*out* *err*]
      (println (str "WARNING state journal does not belong to this snapshot -- "
                    "NOT replaying it. The journal "
                    (if base-bytes
                      (str "was opened against a " base-bytes "-byte snapshot")
                      "records no base snapshot size (written before this check existed)")
                    "; the snapshot on disk is " actual-bytes " bytes. "
                    "Something without journalling wrote it. Replaying would "
                    "roll the snapshot back. Moved to " (.getName target) ".")))
    (try (.renameTo file target) (catch Exception _ nil))
    nil))

(defn replay-journal
  "BASE with every journal record applied, or BASE alone when the journal does
  not belong to it.

  Takes FILE and SNAPSHOT-BYTES rather than reaching for them, so the decision
  this function exists to make can be exercised without a store, a data
  directory, or a process that has already loaded one. `journal-entry-count` is
  set as a side effect because the checkpoint counter has to survive the load
  that produced it."
  ([base file snapshot-bytes]
   (replay-journal base file snapshot-bytes nil))
  ([base file snapshot-bytes snapshot-sha]
  (let [{:keys [lines terminated?]} (journal-records file)]
    (if (empty? lines)
      (do (reset! journal-entry-count 0) base)
      (let [head (try (edn/read-string (first lines)) (catch Exception _ nil))]
        (if-not (host-bounds/journal-belongs-to-snapshot?
                 (:base-bytes head) snapshot-bytes
                 (:base-sha256 head) snapshot-sha)
          (do (archive-orphan-journal! file (:base-bytes head) snapshot-bytes)
              (reset! journal-entry-count 0)
              base)
          (let [last-index (dec (count lines))]
            (loop [value base index 0 applied 0]
              (if (= index (count lines))
                (do (reset! journal-entry-count applied) value)
                (let [line (nth lines index)
                      {:keys [record error]}
                      (try {:record (edn/read-string line)}
                           (catch Exception error {:error error}))]
                  (if-not error
                    (do
                      (when-not (= journal-schema (:schema record))
                        (throw (ex-info "Unknown state journal schema"
                                        {:type :store/invalid-journal-schema
                                         :schema (:schema record)})))
                      (recur (reduce apply-op value (:ops record))
                             (inc index) (inc applied)))
                    (if (and (= index last-index) (not terminated?))
                      (do (binding [*out* *err*]
                            (println "WARNING ignored incomplete final state journal record"))
                          (reset! journal-entry-count applied)
                          value)
                      (throw (ex-info "Invalid state journal record"
                                      {:type :store/invalid-journal
                                       :file (.getPath file)
                                       :line (inc index)}
                                      error))))))))))))))

(defn- load-state []
  (let [file (state-file)
        content (when (.isFile file) (slurp file))
        base (if content
               (merge (initial-state) (edn/read-string content))
               (initial-state))
        ;; The digest of what is actually on disk, taken from the string just
        ;; read rather than a second file read. Nil when no snapshot exists --
        ;; journal records then carry no `:base-sha256` and the belongs check
        ;; falls back to byte length alone, which is also how journals written
        ;; before this field existed keep replaying.
        sha (when content (reset! snapshot-digest (sha256-hex content)))
        main (if (repository-mode?)
               base
               (replay-journal base (journal-file) (snapshot-bytes) sha))]
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
    ;; The digest is taken from the very string being written -- identifying
    ;; the snapshot costs a hash of bytes already in memory, never a re-read.
    (let [serialized (pr-str (dissoc value :work-governance))]
      (host/write-atomic! (.getPath file) serialized host/store-max-bytes)
      (reset! snapshot-digest (sha256-hex serialized))))
  value)

(defn- checkpoint!
  "Fold the journal into the snapshot and empty it, in that order.

  Order matters and is the reason replay is idempotent: a crash between the two
  leaves records that are already in the snapshot, and applying them again
  changes nothing."
  [value]
  (persist-snapshot! value)
  (host/write-atomic! (.getPath (journal-file)) "" host/store-max-bytes)
  (reset! journal-entry-count 0)
  value)

(defn- persist-delta!
  "Append what changed, checkpointing whenever the journal has no room for it.

  ## The bound is a TRIGGER, not a wall

  The first version of this appended and THEN asked whether the journal had
  earned a checkpoint. Measured in production 2026-08-27, nine hours after it
  was deployed: the journal stopped 218 bytes below its 4 MiB bound and stayed
  there. Every record after that was larger than the gap, so `append-durable!`
  refused it -- and the refusal happened BEFORE the line that would have
  relieved it. The store recorded nothing for eight and a half hours, five runs
  sat in `:running` that could never finish, and the only outward sign was
  `content-too-large` appearing among the turn outcomes.

  `host-bounds/store-max-bytes` had already written this failure down for the
  snapshot -- writes succeed at 99% of a bound and the process dies at 101% --
  and this reproduced it one file over. A ceiling with no relief valve is not a
  bound, it is a deadline.

  So the room is checked BEFORE the append, and a record that does not fit
  triggers the checkpoint that makes room. `before` is what gets snapshotted,
  not `after`: every record already in the journal produced `before`, and this
  record is the increment from there.

  ## A record can also be bigger than the whole budget

  Then no amount of checkpointing helps, and the journal is the wrong shape for
  this write. It is written as a snapshot instead. That is not a fallback into
  the old behaviour -- it is the correct answer: a delta larger than the delta
  budget saves nothing, and one mail-sync transaction has already been measured
  at 393 KB across 2,041 operations."
  [before after]
  (when-let [work (:work-governance after)]
    (work-partitions/persist-ledger! work))
  (let [ops (state-delta (dissoc before :work-governance)
                         (dissoc after :work-governance))]
    (when (seq ops)
      (let [file (journal-file)
            path (.getPath file)
            build (fn [] (str (pr-str (cond-> {:schema journal-schema
                                               :at (str (Instant/now))
                                               :base-bytes (snapshot-bytes)
                                               :ops ops}
                                        @snapshot-digest
                                        (assoc :base-sha256 @snapshot-digest)))
                              "\n"))]
        (.mkdirs (.getParentFile file))
        (let [record (build)]
          (if (host-bounds/record-needs-its-own-snapshot?
               (count record) host/journal-max-bytes)
            (checkpoint! after)
            (do
              (when (host-bounds/append-exceeds-bound?
                     (host/file-size path) (count record) host/journal-max-bytes)
                (checkpoint! before))
              ;; Rebuilt, because a checkpoint moved the snapshot this record
              ;; declares itself an increment of. Recording the old length here
              ;; would make `journal-belongs-to-snapshot?` reject a journal that
              ;; is in fact correct.
              (let [record (build)]
                (host/append-durable! path record host/journal-max-bytes)
                (let [entries (swap! journal-entry-count inc)]
                  (when (host-bounds/checkpoint-due? entries (host/file-size path))
                    (checkpoint! after))))))))))
  after)

(defn fold-journal!
  "Fold any replayed journal into the snapshot, once, at startup.

  Without this a journal outlives the process that wrote it, and that is the
  whole hazard: the next build to read this directory may not understand
  journals, will rewrite the snapshot without folding, and will leave records
  on disk that are increments of a state nobody holds any more. Folding at
  start bounds a journal's life to one process.

  A no-op when the journal is already empty, so it costs a `stat` on a normal
  start."
  []
  (when (and (not (repository-mode?)) (pos? @journal-entry-count))
    (locking state (checkpoint! @state))
    true))

(defn transact! [f & args]
  (if (repository-mode?)
    (edn-persist/with-state-lock
     (state-file)
     (fn []
       (locking state
         (let [next-value (apply f (load-state) args)]
           (reset! state next-value)
           (persist-snapshot! next-value)))))
    (locking state
      (let [before @state
            next-value (apply f before args)]
        (persist-delta! before next-value)
        (reset! state next-value)
        next-value))))

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

(defn new-id [prefix] (core/new-id prefix))

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


(def archive-schema "cloud.itonami.app.state-archive.v1")

(defn archive-file
  "The append-only cold archive beside the snapshot:
  `state.edn` -> `state-archive.edn`.

  ADR-2608291500 Phase 2. The snapshot is rewritten whole at every journal
  fold, so terminal work parked inside it is paid for again on every fold --
  measured 2026-08-29, ~65% of the snapshot was terminal goal-jobs and
  finished runs. This file is where that history goes instead: one record per
  line, appended and fsynced, never rewritten. It is also the durable answer
  to `what happened before the current SLO window` -- the journal compacts
  and the hot store forgets, this file does neither."
  []
  (let [file (state-file)
        name (.getName file)
        stem (if (str/ends-with? name ".edn")
               (subs name 0 (- (count name) 4))
               name)]
    (io/file (.getParentFile file) (str stem "-archive.edn"))))

(def ^:private archive-rotate-bytes
  "When the live archive file reaches this, it is renamed aside and a fresh
  one starts. Rotation by rename keeps the append path append-only: nothing
  ever rewrites an archive file, closed ones are just files."
  (* 64 1024 1024))

(defn archive-append!
  "Durably append cold RECORDS -- maps carrying at least :kind, :id and
  :value -- before their hot copies are dropped. Returns how many were
  written.

  Throws on any failure, and the contract with callers is exactly that: a
  caller that could not archive MUST NOT drop the hot copy. Losing a sweep is
  a delay; dropping unarchived history is a deletion."
  [records]
  (if (empty? records)
    0
    (let [file (archive-file)
          path (.getPath file)]
      (.mkdirs (.getParentFile file))
      (when (>= (host/file-size path) archive-rotate-bytes)
        (let [aside (io/file (.getParentFile file)
                             (str (.getName file) ".closed-"
                                  (System/currentTimeMillis)))]
          (when-not (.renameTo file aside)
            (throw (ex-info "archive rotation failed"
                            {:type :store/archive-rotate-failed
                             :file path :aside (.getPath aside)})))))
      (let [at (now)
            body (apply str (map #(str (pr-str (assoc % :schema archive-schema
                                                      :at at))
                                       "
")
                               records))]
        (host/append-durable! path body
                              (+ archive-rotate-bytes (* 16 1024 1024)))
        (count records)))))

(defn session-messages [session-id]
  (core/session-messages @state session-id))

(defn session-context-refs [session-id]
  (core/session-context-refs @state session-id))

(defn set-session-context-refs! [session-id refs]
  (let [at (now)]
    (transact! #(core/set-context-refs % session-id refs at)))
  (session-context-refs session-id))

(defn append-message!
  [session-id {:keys [role content] :as message} max-messages]
  (let [message-id (or (:id message) (new-id "msg"))
        recorded (assoc message :id message-id :at (or (:at message) (now)))]
    ;; The id and the timestamp are minted HERE and handed down. `store-core`
    ;; has no clock and no id generator, which is what lets a test assert on
    ;; the exact transcript a known message produces.
;; The transcript window is `store-core`'s; the datoms are not, because
    ;; `kotoba.kgraph` is `.clj` in an external library and a portable
    ;; namespace cannot reach it. Both halves stay in one transaction.
    (transact! (fn [s]
                 (-> (core/append-message s session-id recorded max-messages)
                     (update :datoms
                             #(-> %
                                  (kgraph/assert-datom [message-id :message/session session-id])
                                  (kgraph/assert-datom [message-id :message/role role])
                                  (kgraph/assert-datom [message-id :message/content content]))))))
    recorded))

(defn record-response! [response]
  (let [at (now)]
    (transact! #(core/record-response % response at))))

(defn clear-session! [session-id]
  (transact! #(core/clear-session % session-id)))
