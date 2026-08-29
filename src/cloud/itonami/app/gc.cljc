(ns cloud.itonami.app.gc
  "The store collects its own garbage.

  On 2026-08-23 this machine's disk filled and the resident spent nine hours
  failing every write: `state.edn.tmp -> state.edn: No space left on device`,
  then a 23:40 resident batch of sixteen jobs that all stuck in `:running`.
  Nothing in the app had caused the exhaustion — and nothing in the app could
  survive it either, because the store rewrites its whole file on every
  transaction, so the moment free space fell under the file's own size every
  turn died at its first write.

  `host/store-max-bytes` already says where the actual gap is: 'Retention is
  the actual gap; this only stops the ceiling from being the wrong one.'
  Measured 2026-08-24, state.edn was 40 MB: 9.2 MB of it was 1,391
  `:goal-jobs` (1,372 terminal, never pruned), 17.3 MB was 2,385 mail
  messages carrying full bodies that `mail-sync` had ALREADY archived to
  disk before putting them in the store (`:archive-path` is written by
  `upsert-messages!` unconditionally).

  Three parts, in order of what they defend:

  1. RETENTION (`plan`, pure): terminal goal-jobs are COMPACTED after two
     days — the plan, the events, the goal text and the run's heavy fields go,
     the skeleton every remaining reader needs stays (`bot_slo/resident-turn?`
     wants `:job/resident-workforce?`, `resident-outcomes` wants the owner and
     `run-outcome`'s fields, the restart sweeps want the status) — and DROPPED
     entirely after the retention window. Compaction cannot happen inside the
     SLO's seven-day window's readers' backs because the skeleton keeps every
     field those readers consult; dropping waits out the window with room to
     spare. Mail bodies whose archive file demonstrably exists are evicted
     from the hot store, keeping the snippet, the metadata and the pointer.
     Nothing here deletes custody data: a goal-job past retention has served
     its audit window, and an evicted body remains byte-complete in
     `mail-archive/` and at the provider.

  2. DISK FLOOR (`pressure*`, pure over an injected measurement): below the
     soft floor a sweep is triggered; below the hard floor
     `refuse-admission?` tells `bots/fire-due-workforce!` to skip the batch
     with `:reason :disk-pressure` instead of launching sixteen jobs into a
     wall. The hard floor scales with the store file itself — an atomic
     rewrite needs the file's size free just for its tmp sibling.

  3. RECEIPTS: every sweep records what it measured and what it did into
     `[:gc :receipts]` (capped). A sweep that could not measure the disk
     writes `:pressure :unknown` — 'could not measure' must never print the
     same thing as 'measured and fine'.

  The decision functions are portable on purpose; every effect — measuring a
  filesystem, deleting a backup, transacting the store, scheduling — sits in
  the `:clj` half and takes the decisions as values."
  (:require [agent.run :as agent-run]
            #?@(:clj [[clojure.java.io :as io]
                      [cloud.itonami.app.config :as config]
                      [cloud.itonami.app.store :as store]]))
  #?(:clj (:import [java.time Instant])))

(def defaults
  "Policy when the deployment says nothing. Overridable under `[:gc …]` in the
  configuration map, key for key."
  {:sweep-minutes 360
   :goal-job-compact-days 1
   :turn-retention-days 8
   :turns-keep-per-bot 50
   :goal-job-retention-days 14
   :goal-jobs-keep-per-bot 50
   :mail-body-retention-days 30
   :config-backups-keep 5
   :soft-floor-bytes (* 2 1024 1024 1024)
   :hard-floor-bytes (* 512 1024 1024)
   :receipts-keep 50})

(defn policy [configuration]
  (merge defaults (get configuration :gc)))

;; ---------------------------------------------------------------------------
;; Disk floor

(defn pressure*
  "Classify one measurement. Pure; the caller measures.

  `usable-bytes` nil or zero means the probe could not answer —
  `File/getUsableSpace` returns 0 for 'unable to determine' — and that is
  `:unknown`, not `:ok` and not `:hard`. Unknown does not refuse admission:
  a broken probe must not stop the company for ever, but it must say so in
  every receipt it touches.

  The hard floor is the configured floor or four times the store file,
  whichever is larger: the rewrite needs the file's own size for the tmp
  sibling, and a floor that ignored the file would approve writes the
  filesystem is about to refuse."
  [usable-bytes store-bytes {:keys [soft-floor-bytes hard-floor-bytes]}]
  (let [hard (max (long hard-floor-bytes) (* 4 (long (or store-bytes 0))))
        soft (max (long soft-floor-bytes) hard)
        level (cond
                (or (nil? usable-bytes) (zero? (long usable-bytes))) :unknown
                (< (long usable-bytes) hard) :hard
                (< (long usable-bytes) soft) :soft
                :else :ok)]
    {:level level :usable-bytes usable-bytes :store-bytes store-bytes
     :hard-floor-bytes hard :soft-floor-bytes soft}))

;; ---------------------------------------------------------------------------
;; Retention, pure

(defn- epoch-ms
  "An ISO instant string or an epoch-ms number, as epoch ms. Nil when the
  value cannot be dated — and an undatable record is never an AGE candidate.
  (The per-bot cap still bounds it: exempting undatable records from the cap
  would make them an unbounded class.)"
  [v]
  (cond
    (number? v) (long v)
    (string? v) #?(:clj (try (.toEpochMilli (Instant/parse v))
                             (catch Exception _ nil))
                   :cljs (let [ms (js/Date.parse v)]
                           (when-not (js/isNaN ms) (long ms))))
    :else nil))

(defn- goal-job-finished-ms [job]
  (or (epoch-ms (get-in job [:job/run :agent.run/finished-at]))
      (epoch-ms (get-in job [:job/run :agent.run/updated-at]))
      (epoch-ms (:job/updated-at job))))

(def ^:private compact-run-keys
  "The `:job/run` fields the remaining readers of a terminal run consult:
  `run-outcome` reads status and error-type, `resident-outcomes` and the SLO
  window read the timestamps, everything else checks `agent-run/active?`
  against the status. The goal text, the lease, the budget and the artifacts
  are for a run somebody is still working with, and a terminal run two days
  old is not one."
  [:agent.run/id :agent.run/status :agent.run/error-type
   :agent.run/created-at :agent.run/finished-at :agent.run/updated-at])

(defn compact-goal-job
  "The skeleton of a terminal goal-job: what every remaining reader needs and
  nothing else. `:agent.run/result` survives only as a keyword —
  `run-outcome` compares it against `:safe-no-op`; a string result is a
  transcript, and the transcript's home is the turn history, not this map."
  [job]
  (let [run (:job/run job)
        result (:agent.run/result run)
        user-id (get-in job [:job/session :user-id])]
    (cond-> {:job/id (:job/id job)
             :job/bot (:job/bot job)
             :job/created-at (:job/created-at job)
             :job/updated-at (:job/updated-at job)
             :job/compacted? true
             :job/run (cond-> (select-keys run compact-run-keys)
                        (keyword? result) (assoc :agent.run/result result))}
      (contains? job :job/resident-workforce?)
      (assoc :job/resident-workforce? (:job/resident-workforce? job))
      user-id (assoc :job/session {:user-id user-id}))))

(defn plan-goal-jobs
  "Compact terminal goal-jobs after `:goal-job-compact-days`, drop them after
  `:goal-job-retention-days` or beyond the newest N per bot. Active runs —
  anything `agent.run/active?` still counts — are never candidates, whatever
  their age.

  Two stages because two readers want different things. Size wants the heavy
  fields gone quickly — measured 2026-08-24, a terminal job averaged 6.6 KB,
  almost all of it plan, events and goal text. The SLO's seven-day window
  wants the job's identity fields for that long — `resident-turn?` classifies
  a week of turns through them. The skeleton serves the second; the first
  does not have to wait for it."
  [goal-jobs now-ms {:keys [goal-job-compact-days goal-job-retention-days
                            goal-jobs-keep-per-bot]}]
  (let [drop-cutoff (- (long now-ms) (* (long goal-job-retention-days) 86400000))
        compact-cutoff (- (long now-ms) (* (long goal-job-compact-days) 86400000))
        terminal? (fn [[_ job]]
                    (contains? agent-run/terminal-statuses
                               (get-in job [:job/run :agent.run/status])))
        terminal (filter terminal? goal-jobs)
        over-cap                             ; ids beyond the newest N per bot
        (into #{}
              (mapcat (fn [[_ entries]]
                        (map first
                             (drop (long goal-jobs-keep-per-bot)
                                   (sort-by (fn [[_ job]]
                                              (- (or (goal-job-finished-ms job) 0)))
                                            entries)))))
              (group-by (fn [[_ job]] (:job/bot job)) terminal))
        drop? (fn [[run-id job :as entry]]
                (and (terminal? entry)
                     (let [finished (goal-job-finished-ms job)]
                       (or (and finished (< finished drop-cutoff))
                           (contains? over-cap run-id)))))
        compact? (fn [[_ job :as entry]]
                   (and (terminal? entry)
                        (not (:job/compacted? job))
                        (when-let [finished (goal-job-finished-ms job)]
                          (< finished compact-cutoff))))
        dropped (filter drop? goal-jobs)
        kept (remove drop? goal-jobs)
        compacted (filter compact? kept)
        ;; What leaves the hot store goes to the cold archive FIRST
        ;; (ADR-2608291500 Phase 2). The full value is archived at the moment
        ;; of compaction -- that is when the events, the plan and the goal
        ;; text stop existing anywhere hot -- and a job dropped without ever
        ;; being compacted is archived whole on its way out. A job that was
        ;; already compacted was already archived; its skeleton adds nothing.
        archive (into (mapv (fn [[run-id job]]
                              {:kind :goal-job :id run-id :value job})
                            compacted)
                      (keep (fn [[run-id job]]
                              (when-not (:job/compacted? job)
                                {:kind :goal-job :id run-id :value job}))
                            dropped))]
    {:goal-jobs (into {}
                      (map (fn [[run-id job :as entry]]
                             (if (compact? entry)
                               [run-id (compact-goal-job job)]
                               entry)))
                      kept)
     :archive archive
     :dropped (count dropped)
     :dropped-bytes (reduce + 0 (map #(count (pr-str (val %))) dropped))
     :compacted (count compacted)
     :compacted-bytes (reduce + 0
                              (map #(- (count (pr-str (val %)))
                                       (count (pr-str (compact-goal-job (val %)))))
                                   compacted))}))

(defn plan-runs
  "Drop saved runs that nothing can consume any more, archiving them first.

  A `[:bots :runs]` entry is the durable Goal checkpoint `resume-goal-turn!`
  reads, so it is LIVE while its goal-job can still resume -- any non-terminal
  status, `:checkpointed` and `:held` included. It is dead the moment its job
  is terminal or gone: resumption requires `(= run-id (:id saved))` against a
  job that will never run again. Measured 2026-08-29: 74 saved runs, 1.77 MB
  -- 25% of the snapshot -- almost all of them full transcripts of finished
  work, rewritten at every fold.

  Kept unconditionally: runs holding a `:pending-call` (an approval card is
  waiting on a person -- `decide-card!` consumes exactly this entry) and
  non-Goal runs (interactive turns clear their own entry; a leftover one is
  not this planner's to age)."
  [runs goal-jobs]
  (let [droppable?
        (fn [[_ run]]
          (and (:goal? run)
               (nil? (:pending-call run))
               (let [job (get goal-jobs (:id run))]
                 (or (nil? job)
                     (contains? agent-run/terminal-statuses
                                (get-in job [:job/run :agent.run/status]))))))
        dropped (filter droppable? runs)]
    {:runs (into {} (remove droppable? runs))
     :archive (mapv (fn [[bot-id run]]
                      {:kind :run :id (:id run) :bot bot-id :value run})
                    dropped)
     :dropped (count dropped)
     :dropped-bytes (reduce + 0 (map #(count (pr-str (val %))) dropped))}))

(defn plan-turn-history
  "Trim each Bot's turn ledger to what its readers still consult,
  archiving the rest.

  The SLO evaluates 24-hour and 7-day windows over `:turn/started-at`
  (`bot_slo/window`), so a turn older than `:turn-retention-days` (default 8
  -- the 7-day window plus a day of slack) is outside every question the hot
  store answers. `:turns-keep-per-bot` newest are kept regardless of age so a
  Bot that has been quiet for a month still shows its history. A turn with no
  `:turn/started-at` cannot be aged and is kept.

  Order within each vector is preserved, so on the sweep cadence this is one
  whole-vector journal write every few hours, not a sliding window."
  [turn-history now-ms {:keys [turn-retention-days turns-keep-per-bot]}]
  (let [cutoff (- (long now-ms) (* (long turn-retention-days) 86400000))
        results
        (map (fn [[bot-id turns]]
               (let [turns (vec turns)
                     newest (into #{}
                                  (take (long turns-keep-per-bot))
                                  (sort-by #(- (or (epoch-ms (:turn/started-at %)) 0))
                                           turns))
                     keep? (fn [turn]
                             (or (contains? newest turn)
                                 (let [at (epoch-ms (:turn/started-at turn))]
                                   (or (nil? at) (>= at cutoff)))))
                     removed (remove keep? turns)]
                 {:entry [bot-id (filterv keep? turns)]
                  :archive (mapv (fn [turn]
                                   {:kind :turn :id (:turn/id turn)
                                    :bot bot-id :value turn})
                                 removed)
                  :removed-bytes (reduce + 0 (map #(count (pr-str %)) removed))}))
             turn-history)]
    {:turn-history (into {} (map :entry) results)
     :archive (into [] (mapcat :archive) results)
     :archived (reduce + 0 (map (comp count :archive) results))
     :archived-bytes (reduce + 0 (map :removed-bytes results))}))

(defn plan-mail-bodies
  "Evict `:body` from messages that are old enough AND whose archive file the
  injected `archived?` predicate confirms. The snippet, the metadata and
  `:archive-path` stay; the bytes stay in `mail-archive/` and at the
  provider. A message without a confirmable archive keeps its body — eviction
  is a cache decision, and a cache may only forget what something else still
  holds."
  [messages now-ms {:keys [mail-body-retention-days]} archived?]
  (let [cutoff (- (long now-ms) (* (long mail-body-retention-days) 86400000))
        ;; `:received-at` first: the age that predicts whether a reader still
        ;; wants this body hot is the MESSAGE's age, not the sync's. A mailbox
        ;; connected yesterday syncs years of history with a fresh
        ;; `:synced-at`, and judging by that kept every one of those bodies —
        ;; measured 2026-08-24, the first dry-run against the live store
        ;; evicted exactly zero of 2,385 for this reason.
        candidate? (fn [[_ m]]
                     (and (seq (:body m))
                          (:archive-path m)
                          (when-let [at (or (epoch-ms (:received-at m))
                                            (epoch-ms (:synced-at m)))]
                            (< at cutoff))
                          (archived? m)))
        candidates (filter candidate? messages)
        evicted-bytes (reduce + 0 (map #(count (str (:body (val %)))) candidates))]
    {:messages (reduce (fn [ms [id _]]
                         (update ms id #(-> %
                                            (dissoc :body)
                                            (assoc :body-evicted? true))))
                       (into {} messages) candidates)
     :evicted (count candidates)
     :evicted-bytes evicted-bytes}))

(defn plan
  "One retention pass over the whole state. Pure given `archived?`."
  [state now-ms pol archived?]
  (let [gj (plan-goal-jobs (get-in state [:bots :goal-jobs] {}) now-ms pol)
        ;; Runs are planned against the goal-jobs BEFORE this sweep's own
        ;; drops: a run whose job this very sweep removes must still be
        ;; archived through its own plan, not silently orphaned by ordering.
        runs (plan-runs (get-in state [:bots :runs] {})
                        (get-in state [:bots :goal-jobs] {}))
        turns (plan-turn-history (get-in state [:bots :turn-history] {})
                                 now-ms pol)
        mail (plan-mail-bodies (get-in state [:mail :messages] {}) now-ms
                               pol archived?)]
    {:state (-> state
                (assoc-in [:bots :goal-jobs] (:goal-jobs gj))
                (assoc-in [:bots :runs] (:runs runs))
                (assoc-in [:bots :turn-history] (:turn-history turns))
                (assoc-in [:mail :messages] (:messages mail)))
     :archive (into [] cat [(:archive gj) (:archive runs) (:archive turns)])
     :receipt {:goal-jobs-dropped (:dropped gj)
               :goal-jobs-dropped-bytes (:dropped-bytes gj)
               :goal-jobs-compacted (:compacted gj)
               :goal-jobs-compacted-bytes (:compacted-bytes gj)
               :runs-dropped (:dropped runs)
               :runs-dropped-bytes (:dropped-bytes runs)
               :turns-archived (:archived turns)
               :turns-archived-bytes (:archived-bytes turns)
               :mail-bodies-evicted (:evicted mail)
               :mail-bodies-evicted-bytes (:evicted-bytes mail)}}))

(defn record-receipt
  "Append one receipt, keeping the newest `keep`."
  [state receipt keep]
  (update-in state [:gc :receipts]
             (fn [receipts]
               (vec (take-last (long keep) (conj (vec receipts) receipt))))))

;; ---------------------------------------------------------------------------
;; Effects: measurement, file sweep, transaction, schedule

#?(:clj
   (do

(defn pressure
  "Measure the store's filesystem and classify it."
  [configuration]
  (let [file (store/state-file)
        dir (or (.getParentFile file) (io/file "."))
        usable (try (.getUsableSpace ^java.io.File dir)
                    (catch Exception _ 0))
        store-bytes (when (.isFile file) (.length file))]
    (pressure* usable store-bytes (policy configuration))))

(defn refuse-admission?
  "The map `fire-due-workforce!` puts in its skip entry, or nil.

  Only `:hard` refuses. `:soft` and `:unknown` admit — the first still has
  headroom and the second is a broken probe, and both are reported by the
  sweep receipts rather than by stopping work."
  [configuration]
  (let [p (pressure configuration)]
    (when (= :hard (:level p))
      (assoc p :reason :disk-pressure))))

(defn sweep-config-backups!
  "Keep the newest N `config.edn.bak-*` siblings of the store, delete the
  rest. These are written on every config save and never read back by
  anything; five of them is a history, fifty is a leak."
  [{:keys [config-backups-keep]}]
  (let [dir (io/file (config/data-dir))
        baks (->> (.listFiles dir)
                  (filter #(and (.isFile ^java.io.File %)
                                (.startsWith (.getName ^java.io.File %)
                                             "config.edn.bak-")))
                  (sort-by #(- (.lastModified ^java.io.File %))))
        stale (drop (long config-backups-keep) baks)]
    (doseq [^java.io.File f stale] (.delete f))
    (count stale)))

(defn sweep!
  "Measure, retain, record. Returns the receipt it stored."
  [configuration]
  (let [pol (policy configuration)
        pressure-before (pressure configuration)
        baks (try (sweep-config-backups! pol)
                  (catch Exception _ 0))
        archived? (fn [m]
                    (let [p (:archive-path m)]
                      (boolean (and p (.isFile (io/file (str p)))))))
        now-ms (System/currentTimeMillis)
        receipt-box (volatile! nil)]
    (store/transact!
     (fn [state]
       (let [{:keys [state receipt archive]} (plan state now-ms pol archived?)
             ;; Cold copies are written and fsynced BEFORE the hot drops
             ;; commit. A throw here aborts the whole transaction: the store
             ;; is unchanged, nothing was lost, the sweep just runs again
             ;; later (ADR-2608291500 Phase 2 -- losing a sweep is a delay,
             ;; dropping unarchived history is a deletion).
             archived-count (store/archive-append! archive)
             receipt (merge receipt
                            {:archived-records archived-count}
                            {:at (store/now)
                             :pressure (:level pressure-before)
                             :usable-bytes (:usable-bytes pressure-before)
                             :store-bytes (:store-bytes pressure-before)
                             :config-backups-deleted baks})]
         (vreset! receipt-box receipt)
         (record-receipt state receipt (:receipts-keep pol)))))
    (let [receipt @receipt-box]
      (binding [*out* *err*]
        (println (format (str "gc: pressure=%s goal-jobs dropped=%d "
                              "compacted=%d runs-dropped=%d turns-archived=%d "
                              "archived-records=%d mail-bodies-evicted=%d "
                              "(~%.1f MiB) config-backups-deleted=%d")
                         (name (or (:pressure receipt) :unknown))
                         (long (:goal-jobs-dropped receipt 0))
                         (long (:goal-jobs-compacted receipt 0))
                         (long (:runs-dropped receipt 0))
                         (long (:turns-archived receipt 0))
                         (long (:archived-records receipt 0))
                         (long (:mail-bodies-evicted receipt 0))
                         (/ (double (+ (:goal-jobs-dropped-bytes receipt 0)
                                       (:goal-jobs-compacted-bytes receipt 0)
                                       (:runs-dropped-bytes receipt 0)
                                       (:turns-archived-bytes receipt 0)
                                       (:mail-bodies-evicted-bytes receipt 0)))
                            1048576.0)
                         (long (:config-backups-deleted receipt 0)))))
      receipt)))

(defonce ^:private sweeper (atom nil))

(defn start!
  "Begin sweeping. Idempotent; `{:gc {:enabled? false}}` turns it off.

  The same executor discipline as `bots/start-tick!`: fixed DELAY so a slow
  sweep never queues the next one behind it, and a catch of Throwable because
  a timer thread that dies on the first failure is a collector that silently
  stops collecting. The first sweep runs one minute after start — the store
  most in need of collection is the one that just crashed on a full disk, and
  waiting a full interval to relieve it would repeat the outage in slow
  motion."
  [configuration]
  (when (and (not @sweeper)
             (not (false? (get-in configuration [:gc :enabled?]))))
    (let [interval (long (* 60 (or (get-in configuration [:gc :sweep-minutes])
                                   (:sweep-minutes defaults))))
          executor (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
                    (reify java.util.concurrent.ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "cloud-itonami-gc")
                          (.setDaemon true)))))]
      (.scheduleWithFixedDelay
       ^java.util.concurrent.ScheduledExecutorService executor
       ^Runnable (fn [] (try (sweep! configuration)
                             (catch Throwable _ nil)))
       60 interval java.util.concurrent.TimeUnit/SECONDS)
      (reset! sweeper executor)))
  true)

(defn stop! []
  (when-let [^java.util.concurrent.ScheduledExecutorService executor @sweeper]
    (.shutdownNow executor)
    (reset! sweeper nil))
  true)

))
