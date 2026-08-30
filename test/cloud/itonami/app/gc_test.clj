(ns cloud.itonami.app.gc-test
  "The collector's decisions, both directions each.

  Every assertion here follows ADR-2608136000's sixth question: a check must
  have refused for the reason it names. So each policy is exercised once in
  the direction that keeps and once in the direction that drops, and the
  pressure levels pin their literals — `:unknown` is asserted to be distinct
  from `:ok`, because 'could not measure' printing the same thing as
  'measured and fine' is the exact defect the receipt exists to prevent."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot-slo :as bot-slo]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.gc :as gc]
            [cloud.itonami.app.store :as store]))

(def day-ms 86400000)
(def now-ms 1787000000000)

(defn- goal-job [bot status finished-days-ago]
  {:job/bot bot
   :job/run {:agent.run/status status
             :agent.run/finished-at (- now-ms (* finished-days-ago day-ms))}})

(def pol gc/defaults)

(deftest terminal-goal-jobs-past-retention-drop
  (let [jobs {"old-done" (goal-job "b1" :succeeded 30)
              "old-failed" (goal-job "b1" :failed 30)
              "fresh-done" (goal-job "b1" :succeeded 1)
              "old-running" (goal-job "b1" :running 30)
              "old-held" (goal-job "b1" :held 30)
              "old-queued" (goal-job "b1" :queued 30)}
        {:keys [goal-jobs dropped]} (gc/plan-goal-jobs jobs now-ms pol)]
    (testing "terminal and past retention drops"
      (is (= 2 dropped))
      (is (not (contains? goal-jobs "old-done")))
      (is (not (contains? goal-jobs "old-failed"))))
    (testing "terminal but fresher than the compact window keeps its full record"
      (is (= (goal-job "b1" :succeeded 1) (get goal-jobs "fresh-done"))))
    (testing "active keeps whatever its age — running, held and queued are
              not garbage, they are work"
      (is (= (goal-job "b1" :running 30) (get goal-jobs "old-running")))
      (is (= (goal-job "b1" :held 30) (get goal-jobs "old-held")))
      (is (= (goal-job "b1" :queued 30) (get goal-jobs "old-queued"))))))

(deftest terminal-goal-jobs-between-compact-and-retention-compact
  (let [full (assoc (goal-job "b1" :succeeded 5)
                    :job/id "run-mid"
                    :job/plan {:steps [:s1 :s2]}
                    :job/events [{:e 1} {:e 2}]
                    :job/objective "a long goal text"
                    :job/resident-workforce? true
                    :job/session {:user-id "u1" :token "SECRET"}
                    :job/created-at "2026-08-19T00:00:00.000000Z"
                    :job/updated-at "2026-08-19T00:05:00.000000Z")
        full (assoc-in full [:job/run :agent.run/goal] "the same long text")
        full (assoc-in full [:job/run :agent.run/result] "a page of prose")
        {:keys [goal-jobs compacted compacted-bytes]}
        (gc/plan-goal-jobs {"run-mid" full} now-ms pol)
        skeleton (get goal-jobs "run-mid")]
    (is (= 1 compacted))
    (is (pos? compacted-bytes))
    (testing "the heavy fields are gone"
      (is (not (contains? skeleton :job/plan)))
      (is (not (contains? skeleton :job/events)))
      (is (not (contains? skeleton :job/objective)))
      (is (not (contains? (:job/run skeleton) :agent.run/goal)))
      (is (not (contains? (:job/run skeleton) :agent.run/result))
          "a string result is a transcript, and the transcript's home is the
          turn history"))
    (testing "every field a remaining reader consults survives"
      (is (true? (:job/compacted? skeleton)))
      (is (true? (:job/resident-workforce? skeleton)))
      (is (= {:user-id "u1"} (:job/session skeleton))
          "the owner survives, the rest of the session does not")
      (is (= "b1" (:job/bot skeleton)))
      (is (= :succeeded (get-in skeleton [:job/run :agent.run/status])))
      (is (= (get-in full [:job/run :agent.run/finished-at])
             (get-in skeleton [:job/run :agent.run/finished-at]))))
    (testing "a keyword result survives — run-outcome compares it to :safe-no-op"
      (let [job (assoc-in (goal-job "b1" :succeeded 5)
                          [:job/run :agent.run/result] :safe-no-op)
            r (gc/plan-goal-jobs {"r" job} now-ms pol)]
        (is (= :safe-no-op
               (get-in r [:goal-jobs "r" :job/run :agent.run/result])))))
    (testing "an already-compacted job is not compacted again"
      (let [again (gc/plan-goal-jobs goal-jobs now-ms pol)]
        (is (zero? (:compacted again)))
        (is (= skeleton (get-in again [:goal-jobs "run-mid"])))))))

(deftest goal-jobs-beyond-per-bot-cap-drop-newest-kept
  (let [pol (assoc pol :goal-jobs-keep-per-bot 2)
        jobs (into {} (for [i (range 5)]
                        [(str "run-" i) (goal-job "b1" :succeeded i)]))
        {:keys [goal-jobs dropped]} (gc/plan-goal-jobs jobs now-ms pol)]
    (is (= 3 dropped))
    (testing "the newest two survive, the cap drops from the old end"
      (is (contains? goal-jobs "run-0"))
      (is (contains? goal-jobs "run-1"))
      (is (not (contains? goal-jobs "run-4"))))
    (testing "the cap is per bot, not global"
      (let [two-bots (merge jobs
                            (into {} (for [i (range 2)]
                                       [(str "b2-run-" i)
                                        (goal-job "b2" :succeeded i)])))
            result (gc/plan-goal-jobs two-bots now-ms pol)]
        (is (contains? (:goal-jobs result) "b2-run-0"))
        (is (contains? (:goal-jobs result) "b2-run-1"))))))

(deftest undatable-goal-job-is-never-an-age-candidate
  ;; Age cannot judge what it cannot date. The per-bot cap still bounds
  ;; undatable records — exempting them from it would make them an
  ;; unbounded class — but under the cap they sort oldest.
  (let [jobs {"no-dates" {:job/bot "b1"
                          :job/run {:agent.run/status :succeeded}}}
        {:keys [goal-jobs dropped]} (gc/plan-goal-jobs jobs now-ms pol)]
    (is (zero? dropped))
    (is (contains? goal-jobs "no-dates"))))

(defn- message [id days-ago body archive-path]
  [id (cond-> {:id id
               :snippet "snip"
               :subject "s"
               :synced-at (str (java.time.Instant/ofEpochMilli
                                (- now-ms (* days-ago day-ms))))}
        body (assoc :body body)
        archive-path (assoc :archive-path archive-path))])

(deftest mail-bodies-evict-only-old-and-archived
  (let [messages (into {} [(message "old-archived" 60 "BODY" "/a/x.json")
                           (message "old-unarchived" 60 "BODY" nil)
                           (message "old-archive-missing" 60 "BODY" "/a/gone.json")
                           (message "fresh-archived" 1 "BODY" "/a/y.json")
                           (message "old-no-body" 60 nil "/a/z.json")])
        archived? (fn [m] (not= "/a/gone.json" (:archive-path m)))
        {:keys [messages evicted evicted-bytes]}
        (gc/plan-mail-bodies messages now-ms pol archived?)]
    (is (= 1 evicted))
    (is (= 4 evicted-bytes))
    (testing "the evicted message loses its body and keeps everything else"
      (let [m (get messages "old-archived")]
        (is (nil? (:body m)))
        (is (true? (:body-evicted? m)))
        (is (= "snip" (:snippet m)))
        (is (= "/a/x.json" (:archive-path m)))))
    (testing "no confirmable archive means the body stays — eviction is a
              cache decision and a cache may only forget what something else
              still holds"
      (is (= "BODY" (:body (get messages "old-unarchived"))))
      (is (= "BODY" (:body (get messages "old-archive-missing")))))
    (testing "fresh stays whole"
      (is (= "BODY" (:body (get messages "fresh-archived")))))))

(deftest mail-age-is-the-message-age-not-the-sync-age
  ;; The regression the first live dry-run measured: every body judged by
  ;; :synced-at survived, because the whole mailbox had been synced recently.
  ;; An old message freshly synced IS a candidate; a fresh message can never
  ;; become one by having been synced long ago.
  (let [old-received {:id "m" :body "BODY" :archive-path "/a/m.json"
                      :received-at (str (java.time.Instant/ofEpochMilli
                                         (- now-ms (* 60 day-ms))))
                      :synced-at (str (java.time.Instant/ofEpochMilli
                                       (- now-ms day-ms)))}
        fresh-received (assoc old-received
                              :received-at
                              (str (java.time.Instant/ofEpochMilli
                                    (- now-ms day-ms)))
                              :synced-at
                              (str (java.time.Instant/ofEpochMilli
                                    (- now-ms (* 60 day-ms)))))]
    (is (= 1 (:evicted (gc/plan-mail-bodies {"m" old-received} now-ms pol
                                            (constantly true)))))
    (is (= 0 (:evicted (gc/plan-mail-bodies {"m" fresh-received} now-ms pol
                                            (constantly true)))))))

(deftest pressure-levels-pin-their-literals
  (let [pol {:soft-floor-bytes 2000 :hard-floor-bytes 1000}]
    (testing "measured and fine"
      (is (= :ok (:level (gc/pressure* 5000 0 pol)))))
    (testing "below soft, above hard"
      (is (= :soft (:level (gc/pressure* 1500 0 pol)))))
    (testing "below hard"
      (is (= :hard (:level (gc/pressure* 500 0 pol)))))
    (testing "could not measure is its own level, not :ok and not :hard"
      (is (= :unknown (:level (gc/pressure* 0 0 pol))))
      (is (= :unknown (:level (gc/pressure* nil 0 pol))))
      (is (not= :ok (:level (gc/pressure* 0 0 pol))))
      (is (not= :hard (:level (gc/pressure* 0 0 pol)))))
    (testing "the hard floor scales with the store file: an atomic rewrite
              needs the file's own size free for its tmp sibling"
      (is (= :hard (:level (gc/pressure* 3000 1000 pol))))
      (is (= 4000 (:hard-floor-bytes (gc/pressure* 3000 1000 pol)))))))

(deftest plan-composes-and-reports
  (let [state {:bots {:goal-jobs {"old" (goal-job "b1" :succeeded 30)
                                  "live" (goal-job "b1" :running 30)}}
               :mail {:messages (into {} [(message "old" 60 "AB" "/a/x.json")])}
               :sessions {"untouched" {:id "untouched"}}}
        {:keys [state receipt]} (gc/plan state now-ms pol (constantly true))]
    (is (= {"live" (goal-job "b1" :running 30)}
           (get-in state [:bots :goal-jobs])))
    (is (nil? (get-in state [:mail :messages "old" :body])))
    (is (= {"untouched" {:id "untouched"}} (:sessions state))
        "the plan touches the two planes it names and nothing else")
    (is (= 1 (:goal-jobs-dropped receipt)))
    (is (= 0 (:goal-jobs-compacted receipt)))
    (is (= 1 (:mail-bodies-evicted receipt)))
    (is (= 2 (:mail-bodies-evicted-bytes receipt))))
  (testing "a sweep with nothing to do reports zeros, which is distinct from
            not having measured"
    (let [{:keys [receipt]} (gc/plan {} now-ms pol (constantly true))]
      (is (= 0 (:goal-jobs-dropped receipt)))
      (is (= 0 (:goal-jobs-compacted receipt)))
      (is (= 0 (:mail-bodies-evicted receipt))))))

(deftest receipts-are-capped
  (let [state (reduce #(gc/record-receipt %1 {:n %2} 3) {} (range 10))]
    (is (= [{:n 7} {:n 8} {:n 9}] (get-in state [:gc :receipts])))))


;; ── the cold archive (ADR-2608291500 Phase 2) ──────────────────────────────

(deftest what-leaves-the-hot-store-is-named-in-the-archive-plan
  (let [jobs {"drop-raw" (goal-job "b1" :succeeded 30)
              "drop-compacted" (assoc (goal-job "b1" :succeeded 30)
                                      :job/compacted? true)
              "compact-now" (goal-job "b1" :succeeded 2)
              "live" (goal-job "b1" :running 30)}
        {:keys [archive goal-jobs]} (gc/plan-goal-jobs jobs now-ms pol)]
    (is (= #{"drop-raw" "compact-now"} (into #{} (map :id) archive))
        "dropped-uncompacted and freshly-compacted are archived whole")
    (is (= (get jobs "compact-now")
           (:value (first (filter #(= "compact-now" (:id %)) archive))))
        "the archived value is the FULL pre-skeleton job")
    (is (:job/compacted? (get goal-jobs "compact-now")))
    (is (nil? (get goal-jobs "drop-compacted"))
        "an already-compacted drop still drops; its full copy was archived when it compacted")))

(deftest saved-runs-die-with-their-jobs-and-not-before
  (let [jobs {"done" (goal-job "b1" :succeeded 1)
              "paused" (goal-job "b2" :checkpointed 1)}
        runs {"bot-1" {:goal? true :id "done" :messages [:m]}
              "bot-2" {:goal? true :id "paused" :messages [:m]}
              "bot-3" {:goal? true :id "gone" :messages [:m]}
              "bot-4" {:goal? true :id "done" :pending-call {:name "x"}}
              "bot-5" {:goal? false :id "chat"}}
        planned (gc/plan-runs runs jobs)]
    (is (= #{"bot-2" "bot-4" "bot-5"} (set (keys (:runs planned))))
        "checkpointed job, pending approval, and interactive runs survive")
    (is (= 2 (:dropped planned)))
    (is (= #{"done" "gone"} (into #{} (map :id) (:archive planned))))
    (is (= #{"bot-1" "bot-3"} (into #{} (map :bot) (:archive planned))))))

(deftest turns-outside-every-slo-window-move-to-the-archive
  ;; Retention is 8 days OR the newest 50 per bot, whichever keeps more:
  ;; a quiet Bot keeps its recent history however old, so aging only ever
  ;; trims Bots whose ledger has outgrown the keep-count.
  (let [turn (fn [id days-ago]
               {:turn/id id
                :turn/started-at (str (java.time.Instant/ofEpochMilli
                                       (- now-ms (long (* days-ago day-ms)))))})
        fresh (mapv #(turn (str "fresh-" %) 1) (range 52))
        history {"bot-1" (into [(turn "ancient-a" 30) (turn "ancient-b" 20)
                                (turn "stale" 9)]
                               fresh)
                 "bot-2" [(turn "lone-ancient" 30) {:turn/id "undated"}]}
        planned (gc/plan-turn-history history now-ms pol)]
    (is (= (mapv :turn/id fresh)
           (mapv :turn/id (get-in planned [:turn-history "bot-1"])))
        "the 52 fresh fill the keep-count; everything past the cutoff and outside it goes")
    (is (= 3 (:archived planned)))
    (is (= #{"ancient-a" "ancient-b" "stale"} (into #{} (map :id) (:archive planned)))
        "only bot-1 trims -- bot-2 is under the keep-count entirely")
    (is (= [(turn "lone-ancient" 30) {:turn/id "undated"}]
           (get-in planned [:turn-history "bot-2"]))
        "a quiet Bot keeps even ancient turns, and an undated one is never aged")))


(deftest the-slo-answers-identically-across-the-cold-split
  ;; ADR-2608291500 Phase 2 acceptance gate: everything the split moves is
  ;; outside every window the SLO asks about, so `evaluate` must not be able
  ;; to tell a swept store from an unswept one.
  (let [session {:user-id "u" :organization-id "o" :kind :person}
        at (fn [days-ago] (str (java.time.Instant/ofEpochMilli
                                (- now-ms (long (* days-ago day-ms))))))
        fresh-turns (mapv (fn [i] {:turn/id (str "t" i) :turn/state :completed
                                   :turn/phase :done
                                   :turn/started-at (at 0.2)
                                   :turn/updated-at (at 0.2)
                                   :turn/finished-at (at 0.19)})
                          (range 52))
        ancient {:turn/id "t-ancient" :turn/state :failed :turn/phase :failed
                 :turn/started-at (at 30) :turn/updated-at (at 30)
                 :turn/finished-at (at 30)}
        state {:bots {:bots {"b" {:bot/owner "u" :bot/organization "o"}}
                      :turn-history {"b" (conj fresh-turns ancient)}
                      :goal-jobs {"t0" {:job/id "t0" :job/bot "b"
                                        :job/resident-workforce? true
                                        :job/session {:user-id "u"}
                                        :job/run {:agent.run/status :succeeded
                                                  :agent.run/finished-at
                                                  (- now-ms (* 30 day-ms))}}}
                      :runs {"b" {:goal? true :id "t0" :messages [:m]}}
                      :workforce-jobs {}}}
        now-inst (java.time.Instant/ofEpochMilli now-ms)
        receipt {:score nil :sampled 0}
        swept (:state (gc/plan state now-ms pol (constantly true)))]
    (is (not= (get-in state [:bots :turn-history "b"])
              (get-in swept [:bots :turn-history "b"]))
        "positive control: the sweep DID move something")
    (is (nil? (get-in swept [:bots :runs "b"]))
        "positive control: the terminal run's checkpoint is gone from hot")
    (is (= (bot-slo/evaluate (:bots state) session now-inst receipt)
           (bot-slo/evaluate (:bots swept) session now-inst receipt))
        "and no number the SLO reports moved")))

(defn- with-tmp-store
  "The bots-test store fixture, locally: a throwaway data-dir and an in-memory
  transact!, so `sweep!` exercises its real plan-archive-drop path without
  touching the developer store."
  [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-gc-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))
                    store/transact! (fn [f & args]
                                      (apply swap! store/state f args))]
        (f))
      (finally (reset! store/state previous)))))

(deftest a-sweep-that-cannot-archive-drops-nothing
  ;; The contract on `archive-append!`: a throw means the hot copies stay.
  ;; Losing a sweep is a delay; dropping unarchived history is a deletion.
  (with-tmp-store
    (fn []
      (store/transact!
       (fn [state]
         (merge state
                {:bots {:goal-jobs {"dead" (goal-job "b" :succeeded 30)}
                        :runs {"b" {:goal? true :id "dead" :messages [:m]}}
                        :turn-history {}}})))
      (testing "the failing archive aborts the sweep with its own reason"
        (with-redefs [store/archive-append!
                      (fn [_] (throw (ex-info "disk said no"
                                              {:type :fs/disk-pressure})))]
          (is (= :fs/disk-pressure
                 (try (gc/sweep! {}) :no-throw
                      (catch clojure.lang.ExceptionInfo e
                        (:type (ex-data e))))))))
      (testing "and nothing was dropped"
        (is (some? (get-in (store/snapshot) [:bots :goal-jobs "dead"])))
        (is (some? (get-in (store/snapshot) [:bots :runs "b"]))))
      (testing "the same sweep with a working archive drops and records"
        (let [receipt (gc/sweep! {})]
          (is (nil? (get-in (store/snapshot) [:bots :goal-jobs "dead"])))
          (is (nil? (get-in (store/snapshot) [:bots :runs "b"])))
          (is (= 2 (:archived-records receipt)))
          (let [lines (->> (slurp (store/archive-file))
                           str/split-lines
                           (remove str/blank?)
                           (map edn/read-string))]
            (is (= #{:goal-job :run} (into #{} (map :kind) lines)))
            (is (every? #(= store/archive-schema (:schema %)) lines))))))))

(deftest compaction-keeps-why-a-run-failed-and-drops-its-transcript
  ;; Both directions on one call, as this namespace's docstring requires.
  ;;
  ;; `:agent.run/error-message` was added to the failure path on 2026-08-21,
  ;; when a measurement found it written zero times against 141 for
  ;; `:turn/error-message`. It was not added to `compact-run-keys`, so the
  ;; collector deleted every one of them two days later. Measured 2026-08-30
  ;; on the resident store: 1,079 runs carried an error type and 108 still
  ;; carried its message.
  (let [job {:job/id "run-1"
             :job/bot "bot-1"
             :job/created-at 1 :job/updated-at 2
             :job/resident-workforce? true
             :job/session {:user-id "user-1" :organization-id "org-1"}
             :job/plan [{:step "s1"}]
             :job/events [{:event/kind :turn/failed}]
             :job/run {:agent.run/id "run-1"
                       :agent.run/status :failed
                       :agent.run/error-type :provider/http-error
                       :agent.run/error-message "HTTP 503 from api.example: upstream busy"
                       :agent.run/result "a long transcript"
                       :agent.run/goal "the whole objective text"
                       :agent.run/created-at 1
                       :agent.run/finished-at 2
                       :agent.run/updated-at 2}}
        compacted (gc/compact-goal-job job)
        run (:job/run compacted)]
    (testing "the reason survives, because a type alone cannot be acted on"
      (is (= "HTTP 503 from api.example: upstream busy"
             (:agent.run/error-message run))))
    (testing "the type survives beside it"
      (is (= :provider/http-error (:agent.run/error-type run))))
    (testing "the transcript and the goal text do not, which is the point of compacting"
      (is (nil? (:agent.run/result run)))
      (is (nil? (:agent.run/goal run))))
    (testing "and the heavy job fields are gone"
      (is (nil? (:job/plan compacted)))
      (is (nil? (:job/events compacted))))))
