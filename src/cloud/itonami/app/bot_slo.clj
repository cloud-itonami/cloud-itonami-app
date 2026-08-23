(ns cloud.itonami.app.bot-slo
  "Fail-closed Bot stability and output-quality scoring.

  This namespace reads a snapshot; it never changes Bot state. A score is a
  description of observed work, not authority to call a Bot healthy. PASS is
  therefore the conjunction of every ADR-2608212400 gate. Missing telemetry
  remains `:unmeasured` and fails its gate instead of becoming zero or green."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Duration Instant]))

(def schema "cloud.itonami.app.bot-slo.v1")
(def quality-receipt-resource "cloud-itonami-bot-quality-receipt.edn")

(defn quality-receipt []
  (some-> quality-receipt-resource io/resource slurp edn/read-string))

(defn- instant [value]
  (cond
    (instance? Instant value) value
    (some? value) (try (Instant/parse (str value)) (catch Exception _ nil))))

(defn- pct [n d]
  (if (pos? d) (/ (double n) d) 0.0))

(defn- round1 [value]
  (/ (Math/round (* 10.0 (double value))) 10.0))

(defn- percentile [values p]
  (when (seq values)
    (let [ordered (vec (sort values))
          rank (max 1 (long (Math/ceil (* p (count ordered)))))]
      (nth ordered (dec rank)))))

(defn- elapsed-seconds [turn]
  (when-let [started (instant (:turn/started-at turn))]
    ;; A running turn's updated-at is progress telemetry, not response latency.
    ;; Including it would make an in-flight turn look fast until it stalls.
    (when-let [finished (instant (:turn/finished-at turn))]
      (max 0 (.getSeconds (Duration/between started finished))))))

(defn- error-name [turn]
  (some-> (:turn/error-type turn) str (str/replace #"^:" "")))

(defn- within? [^Instant now hours timestamp]
  (when-let [at (instant timestamp)]
    (and (not (.isAfter at now))
         (not (.isBefore at (.minusSeconds now (* 3600 hours)))))))

(defn- owner-bot-ids [partition session]
  (into #{}
        (keep (fn [[bot-id bot]]
                (when (and (= (:user-id session) (:bot/owner bot))
                           (= (:organization-id session) (:bot/organization bot)))
                  bot-id)))
        (:bots partition)))

(defn- owner-turns [partition session]
  (mapcat #(get-in partition [:turn-history %] [])
          (owner-bot-ids partition session)))

(defn- resident-turn? [partition turn]
  (boolean (get-in partition [:goal-jobs (:turn/id turn)
                              :job/resident-workforce?])))

(defn- window [partition session ^Instant now hours]
  (let [turns (->> (owner-turns partition session)
                   (filter #(within? now hours (:turn/started-at %)))
                   vec)
        completed (count (filter #(= :completed (:turn/state %)) turns))
        failed (count (filter #(contains? #{:failed :blocked :cancelled :interrupted}
                                           (:turn/state %)) turns))
        interactive (remove #(resident-turn? partition %) turns)
        elapsed (keep elapsed-seconds turns)
        interactive-elapsed (keep elapsed-seconds interactive)
        provider-timeouts (count (filter #(= "provider/timeout" (error-name %)) turns))
        tool-budget (count (filter #(contains? #{"bot/tool-budget-exhausted"
                                                "tool-budget-exhausted"}
                                              (error-name %))
                                   turns))
        observed (count (filter #(every? some? ((juxt :turn/state :turn/phase
                                                       :turn/started-at :turn/updated-at) %))
                                turns))]
    {:hours hours
     :turns (count turns)
     :completed completed
     :failed failed
     :completion-rate (round1 (* 100.0 (pct completed (count turns))))
     :provider-timeouts provider-timeouts
     :provider-timeout-rate (round1 (* 100.0 (pct provider-timeouts (count turns))))
     :tool-budget-exhausted tool-budget
     :tool-budget-rate (round1 (* 100.0 (pct tool-budget (count turns))))
     :p50-seconds (percentile elapsed 0.50)
     :p90-seconds (percentile elapsed 0.90)
     :interactive-turns (count interactive)
     :interactive-p90-seconds (percentile interactive-elapsed 0.90)
     :observability-coverage (round1 (* 100.0 (pct observed (count turns))))}))

(defn- overdue-jobs [partition session ^Instant now]
  (->> (:workforce-jobs partition)
       vals
       (filter #(and (:workforce.job/enabled? %)
                     (= (:user-id session) (:workforce.job/owner %))
                     (= (:organization-id session) (:workforce.job/organization %))))
       (filter (fn [job]
                 (when-let [next-at (instant (:workforce.job/next-run-at job))]
                   (let [cadence (max 1 (long (or (:workforce.job/cadence-minutes job) 1)))]
                     (.isAfter now (.plusSeconds next-at (* 120 cadence)))))))
       count))

(defn- stale-running [partition session ^Instant now]
  (->> (owner-turns partition session)
       (filter #(= :running (:turn/state %)))
       (filter (fn [turn]
                 (when-let [at (instant (or (:turn/updated-at turn)
                                            (:turn/started-at turn)))]
                   (.isAfter now (.plusSeconds at 1800)))))
       count))

(defn- latency-points [p90]
  (cond
    (nil? p90) 0.0
    (<= p90 120) 20.0
    (<= p90 300) 15.0
    (<= p90 600) 10.0
    (<= p90 1800) 5.0
    :else 0.0))

(defn- recovery-points [stale]
  (case stale 0 15.0, 1 11.0, 2 8.0, 5.0))

(defn- quality [receipt]
  (when receipt
    (let [components (:components receipt)
          score (reduce + 0 (map :points (vals components)))]
      {:state (if (>= (long (or (:sample-size receipt) 0))
                      (long (or (:required-sample-size receipt) 20)))
                :measured :baseline)
       :score score
       :as-of (:as-of receipt)
       :sample-size (long (or (:sample-size receipt) 0))
       :required-sample-size (long (or (:required-sample-size receipt) 20))
       :components components
       :metrics (:metrics receipt)
       :source (:source receipt)})))

(defn- gate [id pass? actual target & [state]]
  {:id id :pass? (true? pass?) :state (or state (if pass? :pass :fail))
   :actual actual :target target})

(defn evaluate
  "Evaluate one owner's Bots from a complete app state snapshot.

  `now` and the quality receipt are injectable so boundary and missing-data
  behaviour are executable tests."
  ([state session] (evaluate state session (Instant/now) (quality-receipt)))
  ([state session now receipt]
   (let [now (or (instant now) (Instant/now))
         partition (or (:bots state) {})
         w24 (window partition session now 24)
         w7 (window partition session now (* 24 7))
         overdue (overdue-jobs partition session now)
         stale (stale-running partition session now)
         q (quality receipt)
         completion-ratio (/ (:completion-rate w24) 100.0)
         ;; We currently have point-in-time scheduler evidence, not continuous
         ;; process uptime. Keep the component capped and make overdue work
         ;; visible in the score instead of awarding it unconditionally.
         availability (if (zero? overdue) 18.0 10.0)
         completion-points (* 30.0 completion-ratio)
         latency (latency-points (:p90-seconds w24))
         recovery (recovery-points stale)
         observability (* 7.0 (/ (:observability-coverage w24) 100.0))
         stability (long (Math/round (+ availability completion-points latency
                                        recovery observability)))
         effective (when q (long (Math/round (* completion-ratio (:score q)))))
         duplicate-instrumented? (true? (get-in partition [:slo :duplicate-no-op-suppressed?]))
         stage-timing? (true? (get-in partition [:slo :stage-timing-complete?]))
         quality-metrics (:metrics q)
         quality-pass? (and q
                            (>= (:sample-size q) (:required-sample-size q))
                            (>= (double (or (:factual-grounding-rate quality-metrics) 0)) 0.95)
                            (>= (double (or (:instruction-adherence-rate quality-metrics) 0)) 0.90)
                            (>= (double (or (:actionable-answer-rate quality-metrics) 0)) 0.80))
         gates [(gate :sample-size (>= (:turns w24) 100) (:turns w24) ">= 100 turns")
                (gate :completion-rate (>= (:completion-rate w24) 90.0)
                      (:completion-rate w24) ">= 90%")
                (gate :interactive-p90 (and stage-timing?
                                             (some? (:interactive-p90-seconds w24))
                                             (<= (:interactive-p90-seconds w24) 120))
                      (:interactive-p90-seconds w24) "<= 120 seconds"
                      (when-not stage-timing? :unmeasured))
                (gate :provider-timeout (< (:provider-timeout-rate w24) 2.0)
                      (:provider-timeout-rate w24) "< 2%")
                (gate :tool-budget (< (:tool-budget-rate w24) 1.0)
                      (:tool-budget-rate w24) "< 1%")
                (gate :stale-running (zero? stale) stale "0 older than 30 minutes")
                (gate :duplicate-no-op duplicate-instrumented?
                      (when duplicate-instrumented? "suppressed") "same evidence digest is not reposted"
                      (when-not duplicate-instrumented? :unmeasured))
                (gate :quality-suite quality-pass?
                      (select-keys q [:sample-size :metrics])
                      ">=20 tasks; grounding 95%; adherence 90%; actionable 80%"
                      (when-not quality-pass? (if q :insufficient-sample :unmeasured)))
                (gate :seven-day (and (>= (:turns w7) 100)
                                      (>= (:completion-rate w7) 90.0)
                                      (zero? stale))
                      {:turns (:turns w7) :completion-rate (:completion-rate w7)
                       :stale-running stale}
                      ">=100 turns; completion >=90%; stale 0")]
         enough? (>= (:turns w24) 100)
         pass? (every? :pass? gates)]
     {:schema schema
      :as-of (str now)
      :status (cond pass? :pass enough? :fail :else :insufficient-sample)
      :scores {:stability stability
               :quality (:score q)
               :effective effective}
      :score-components {:availability-scheduler availability
                         :completion (round1 completion-points)
                         :latency latency
                         :recovery-cleanup recovery
                         :observability (round1 observability)}
      :quality q
      :windows {:hours-24 w24 :days-7 w7}
      :workforce {:overdue-two-cadences overdue :stale-running stale}
      :gates gates})))
