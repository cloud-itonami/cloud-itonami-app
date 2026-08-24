(ns cloud.itonami.app.gc-test
  "The collector's decisions, both directions each.

  Every assertion here follows ADR-2608136000's sixth question: a check must
  have refused for the reason it names. So each policy is exercised once in
  the direction that keeps and once in the direction that drops, and the
  pressure levels pin their literals — `:unknown` is asserted to be distinct
  from `:ok`, because 'could not measure' printing the same thing as
  'measured and fine' is the exact defect the receipt exists to prevent."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.gc :as gc]))

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
