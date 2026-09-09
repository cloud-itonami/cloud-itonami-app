#!/usr/bin/env bb
;; Read-only monitoring: never require app/store or display stored content.
(require '[clojure.edn :as edn] '[clojure.string :as str])

(defn fail [kind] (throw (ex-info "Status unavailable" {:kind kind})))
(defn digest [s]
  (apply str (map #(format "%02x" %) (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                            (.getBytes s "UTF-8")))))
(defn apply-op [state {:keys [op path value from]}]
  (when-not (vector? path) (fail :invalid-operation))
  (case op
    :assoc (if (seq path) (assoc-in state path value) value)
    :dissoc (if (seq path)
              (if (= 1 (count path)) (dissoc state (first path))
                  (update-in state (pop path) dissoc (peek path)))
              (fail :invalid-operation))
    :append (let [current (if (seq path) (get-in state path) state)]
              (when-not (and (vector? current) (vector? value)
                             (integer? from) (<= 0 from)) (fail :invalid-append))
              (cond (= (count current) from)
                    (if (seq path) (update-in state path into value) (into current value))
                    (and (>= (count current) (+ from (count value)))
                         (= value (subvec current from (+ from (count value))))) state
                    :else (fail :invalid-append)))
    (fail :unknown-operation)))

(defn coherent-files [root]
  (let [snapshot (str root "/state.edn") journal (str root "/state.journal.edn")]
    (loop [attempt 0]
      (let [s (slurp snapshot) j (slurp journal)]
        (if (and (= s (slurp snapshot)) (= j (slurp journal))) [s j]
            (if (< attempt 3) (recur (inc attempt)) (fail :concurrent-write)))))))

(defn replay [raw journal]
  (when (and (not (str/blank? journal)) (not (str/ends-with? journal "\n")))
    (fail :incomplete-journal))
  (let [records (mapv edn/read-string (remove str/blank? (str/split-lines journal)))
        size (alength (.getBytes raw "UTF-8")) sha (digest raw)]
    (doseq [record records]
      (when-not (and (= "cloud.itonami.app.state-journal.v1" (:schema record))
                     (= size (:base-bytes record)) (= sha (:base-sha256 record))
                     (vector? (:ops record))) (fail :journal-base-or-schema-mismatch)))
    {:state (reduce (fn [s r] (reduce apply-op s (:ops r))) (edn/read-string raw) records)
     :records (count records) :snapshot-bytes size :snapshot-sha256 sha}))

(defn instant [v]
  (cond (string? v) (java.time.Instant/parse v)
        (integer? v) (java.time.Instant/ofEpochMilli v)
        :else (fail :invalid-timestamp)))
(defn age [now v] (.getSeconds (java.time.Duration/between (instant v) now)))
(defn status [state now]
  (let [b (:bots state)
        _ (when-not (and (map? b) (map? (:goal-jobs b))
                         (map? (:workforce-jobs b))) (fail :missing-bot-partition))
        resident (filter :job/resident-workforce? (vals (:goal-jobs b)))
        recent (take-last 50 (sort-by #(instant (:job/created-at %)) resident))
        running (filter #(contains? #{:running :leased} (get-in % [:job/run :agent.run/status])) resident)
        enabled (filter :workforce.job/enabled? (vals (:workforce-jobs b)))
        overdue (map (fn [j]
                       (let [interval (or (:workforce.job/interval-minutes j)
                                          (:workforce.job/cadence-minutes j))]
                         (when-not (and (number? interval) (pos? interval)) (fail :invalid-cadence))
                         (/ (double (max 0 (age now (:workforce.job/next-run-at j)))) (* 60 interval)))) enabled)
        outcome (fn [j] (let [r (:job/run j)]
                         (cond (= :safe-no-op (:agent.run/result r)) :no-op
                               (= :succeeded (:agent.run/status r)) :completed
                               :else (or (:agent.run/status r) :unknown))))]
    {:bot-count (count (:bots b)) :resident-job-count (count resident)
     :recent {:count (count recent) :outcomes (frequencies (map outcome recent))
              :first-created-at (some-> recent first :job/created-at instant str)
              :last-created-at (some-> recent last :job/created-at instant str)}
     :running {:count (count running)
               :max-age-seconds (when (seq running)
                                  (apply max (map #(age now (or (get-in % [:job/run :agent.run/started-at])
                                                               (:job/created-at %))) running)))}
     :schedules {:enabled (count enabled) :overdue-two-intervals (count (filter #(> % 2) overdue))
                 :max-overdue-intervals (when (seq overdue) (apply max overdue))}}))

(try
  (when-not (= 1 (count *command-line-args*)) (fail :expected-data-directory))
  (let [[raw journal] (coherent-files (first *command-line-args*))
        {:keys [state] :as verified} (replay raw journal)
        now (java.time.Instant/now)]
    (prn (merge {:ok true :schema "cloud.itonami.controller-status.v1" :at (str now)
                 :journal (dissoc verified :state)} (status state now))))
  (catch Exception error
    (prn {:ok false :schema "cloud.itonami.controller-status.v1"
          :at (str (java.time.Instant/now))
          :error (or (:kind (ex-data error)) :unreadable-status)})
    (System/exit 1)))
