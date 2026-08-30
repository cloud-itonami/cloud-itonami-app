(ns cloud.itonami.app.bot-import-test
  "The fixtures are the shapes the two sources actually returned on
  2026-08-30, not shapes invented to be convenient: Hermes's single job is a
  paused script with an empty prompt and a failure streak of 111, and Grok's
  runtime reports one held bot with `budget-exhausted`. A converter tested
  only against a healthy bot would look correct and would still enable both
  of those on a fifteen-minute cadence."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot-import :as subject]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- hermes-home! [jobs]
  (let [home (temp-dir "itonami-bot-import-hermes-")
        file (io/file home "cron" "jobs.json")]
    (.mkdirs (.getParentFile file))
    (spit file (json/write-str {:jobs jobs :updated_at "2026-08-30T00:00:00Z"}))
    home))

(def ^:private measured-hermes-job
  {:id "8cf8421b60f9" :name "mailbox-triage" :prompt "" :script "mailbox-triage.py"
   :no_agent true :schedule {:kind "interval" :minutes 60} :enabled false
   :state "paused" :last_status "error" :failure_streak 111
   :last_error "Script exited with code 1\nstdout:\nmailbox-triage: m365 is logged out"})

(def ^:private healthy-hermes-job
  {:id "aa11" :name "Release Watch" :enabled true :state "running"
   :schedule {:kind "interval" :minutes 120} :last_status "ok" :failure_streak 0
   :prompt (str "Read the release channel each pass and report whether the "
                "published version matches the tag the repository declares, "
                "naming the mismatch when there is one.")})

(deftest a-script-job-is-not-importable-and-says-why
  (let [report (subject/import-report "hermes" {:home (hermes-home! [measured-hermes-job])})]
    (is (= 1 (:available report)))
    (is (empty? (:importable report)))
    (is (= ["mailbox-triage"] (mapv :name (:not-importable report))))
    (is (str/includes? (:reason (first (:not-importable report))) "script")
        "the reason names the thing an operator would have to change")
    (is (empty? (:proposals report)))))

(deftest a-healthy-job-becomes-one-reviewable-role
  (let [report (subject/import-report "hermes" {:home (hermes-home! [healthy-hermes-job])})
        proposal (first (:proposals report))]
    (is (= 1 (count (:importable report))))
    (is (= :cloud-itonami/release-watch (:yakuwari/id proposal)))
    (is (= "Release Watch" (:bot/name proposal)))
    (is (= 120 (:bot/cadence-minutes proposal)))
    (is (= {:source "hermes" :id "aa11"} (:yakuwari/imported-from proposal)))
    (testing "a healthy source bot is proposed as running"
      (is (= {:min 0 :desired 1 :max 1} (:yakuwari/scale proposal))))
    (testing "the source bot's own reach is not carried across"
      (is (= :approval-required
             (:decision (first (filter #(= :patch.create (:capability %))
                                       (:yakuwari/capabilities proposal))))))
      (is (= :blocked
             (:decision (first (filter #(= :spend.commit (:capability %))
                                       (:yakuwari/capabilities proposal)))))))))

(deftest a-failing-job-is-imported-stopped-rather-than-inherited
  (let [failing (assoc healthy-hermes-job :last_status "error" :failure_streak 7)
        report (subject/import-report "hermes" {:home (hermes-home! [failing])})]
    (is (= 1 (count (:importable report))))
    (is (= {:min 0 :desired 0 :max 1}
           (:yakuwari/scale (first (:proposals report))))
        "111 failed runs in the source is not a reason to start it here")))

(deftest an-already-present-bot-is-not-proposed-twice
  (let [report (subject/import-report
                "hermes" {:home (hermes-home! [healthy-hermes-job])
                          :existing ["release watch"]})]
    (is (= ["Release Watch"] (:already-present report)))
    (is (empty? (:proposals report)))
    (testing "matching is case-insensitive, because the registry is written by hand"
      (is (empty? (:importable report))))))

(deftest cadence-is-clamped-to-what-the-scheduler-can-honour
  (let [fast (assoc-in healthy-hermes-job [:schedule :minutes] 1)
        slow (assoc-in (assoc healthy-hermes-job :id "bb22" :name "Slow Watch")
                       [:schedule :minutes] 100000)
        report (subject/import-report "hermes" {:home (hermes-home! [fast slow])})
        by-name (into {} (map (juxt :bot/name :bot/cadence-minutes)) (:proposals report))]
    (is (= 15 (get by-name "Release Watch")))
    (is (= 1440 (get by-name "Slow Watch")))))

(deftest an-objective-over-the-consumer-limit-is-refused-before-provision
  (let [long-job (assoc healthy-hermes-job :prompt (apply str (repeat 1001 "x")))
        report (subject/import-report "hermes" {:home (hermes-home! [long-job])})]
    (is (empty? (:importable report)))
    (is (str/includes? (:reason (first (:not-importable report))) "1000")
        "provision refuses the whole workforce over this limit; import says so first")))

(deftest a-source-it-cannot-read-refuses-instead-of-reporting-none
  (testing "no hermes home"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cron jobs"
                          (subject/import-report
                           "hermes" {:home (temp-dir "itonami-bot-import-empty-")}))))
  (testing "a jobs file that is not JSON"
    (let [home (temp-dir "itonami-bot-import-broken-")
          file (io/file home "cron" "jobs.json")]
      (.mkdirs (.getParentFile file))
      (spit file "{not json")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"壊れています"
                            (subject/import-report "hermes" {:home home})))))
  (testing "grok without the service token"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"MURAKUMO_SERVICE_TOKEN"
                          (subject/require-token nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"MURAKUMO_SERVICE_TOKEN"
                          (subject/require-token "  ")))))

(deftest an-unknown-source-names-the-ones-that-exist
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"grok / hermes"
                        (subject/read-source "chatgpt" {}))))

(deftest an-empty-source-reports-none-only-after-a-successful-read
  (let [report (subject/import-report "hermes" {:home (hermes-home! [])})]
    (is (= 0 (:available report)))
    (is (empty? (:proposals report)))
    (is (str/includes? (:next report) "ありません"))))

(def ^:private measured-grok-runtime
  "GET https://itonami.cloud/api/v1/grok-bots/runtime, 2026-08-30. Unauthenticated
  and therefore reproducible; the `/bots` management row beside it needs a
  bearer this workspace does not hold, so that half stays synthetic and is
  named as such."
  {:object "grok_bot.runtime" :configured true :bot_id "default" :status "held"
   :model "murakumo-main" :interval_ms 3600000 :tick_count 24
   :budget_tokens_remaining 0 :last_checkpoint_at 1786783559768
   :last_output "Heartbeat recorded at 2026-08-15T08:45:57.170Z"
   :last_error "budget-exhausted"})

(deftest a-held-grok-bot-carries-its-runtime-health-across
  (let [bot (subject/grok->bot measured-grok-runtime {:bot_id "default"})]
    (is (= "grok" (:source bot)))
    (is (= "default" (:source-id bot)))
    (is (= 60 (:cadence-minutes bot)) "interval_ms 3600000 is one hour")
    (is (false? (:enabled? bot)) "held is not running")
    (is (= "budget-exhausted" (:last-error (:health bot))))
    (is (= {:min 0 :desired 0 :max 1} (subject/desired-scale bot))
        "a held, budget-exhausted bot is not imported into a running role")))

(deftest a-grok-row-that-is-not-the-runtime-bot-does-not-borrow-its-health
  (let [bot (subject/grok->bot measured-grok-runtime
                               {:bot_id "other" :status "running"
                                :interval_ms 900000})]
    (is (= "other" (:source-id bot)))
    (is (= 15 (:cadence-minutes bot)))
    (is (true? (:enabled? bot)))
    (is (nil? (:last-error (:health bot)))
        "the runtime projection describes one bot; the others are not it")))
