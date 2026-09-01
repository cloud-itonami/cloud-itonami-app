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
            [cloud.itonami.app.bot-import :as subject]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.hermes-migration :as migration]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- hermes-home! [jobs]
  (let [home (temp-dir "itonami-bot-import-hermes-")
        file (io/file home "cron" "jobs.json")]
    (.mkdirs (.getParentFile file))
    (spit file (json/write-str {:jobs jobs :updated_at "2026-08-30T00:00:00Z"}))
    home))

(defn- full-hermes-home! []
  (let [home (temp-dir "itonami-hermes-full-")
        named (io/file home "profiles" "research")
        executable (io/file home "hermes-agent" "venv" "bin" "hermes")]
    (doseq [[path content]
            [["SOUL.md" "default persona"]
             ["MEMORY.md" "default memory"]
             ["cron/jobs.json" "{\"jobs\":[]}"]
             ["sessions/session-1.jsonl" "{\"role\":\"user\",\"content\":\"hello\"}"]
             [".env" "SECRET=must-not-cross"]]]
      (let [file (io/file home path)]
        (.mkdirs (.getParentFile file))
        (spit file content)))
    (doseq [[path content]
            [["config.yaml" "model: example/model"]
             ["SOUL.md" "research persona"]
             ["state.db" "fixture database bytes"]
             ["skills/research/SKILL.md" "research skill"]
             ["auth.json" "{\"token\":\"must-not-cross\"}"]]]
      (let [file (io/file named path)]
        (.mkdirs (.getParentFile file))
        (spit file content)))
    (.mkdirs (.getParentFile executable))
    (spit executable "#!/bin/sh\nexit 0\n")
    (.setExecutable executable true)
    home))

(deftest full-hermes-preview-covers-every-profile-and-keeps-authority-out
  (let [home (full-hermes-home!)
        manifest (migration/preview
                  {:home home :business "test-business"
                   :migration-id "hermes-test"
                   :captured-at "2026-09-01T00:00:00Z"})]
    (is (= migration/schema (:schema manifest)))
    (is (= ["default" "research"] (mapv :id (:profiles manifest))))
    (is (= 6 (count (:coverage manifest)))
        "persona/config, memory, skills, cron and complete sessions are named")
    (is (= 2 (get-in manifest [:summary :profiles])))
    (is (pos? (get-in manifest [:summary :portable-files])))
    (testing "credential values and destination grants never enter the bundle"
      (is (false? (get-in manifest [:safety :copies-credentials])))
      (is (false? (get-in manifest [:safety :copies-grants])))
      (is (= #{"credential-file" "provider-and-account-bindings"
               "cloud-itonami-grants"}
             (set (map :kind (get-in manifest [:profiles 0 :rebind-required])))))
      (is (= 3 (count (get-in manifest [:profiles 1 :rebind-required])))))
    (testing "preview is an optimistic lock over all portable source files"
      (let [before (get-in manifest [:source :revision])]
        (spit (io/file home "profiles" "research" "SOUL.md") "changed persona")
        (is (not= before
                  (get-in (migration/preview {:home home}) [:source :revision])))))))

(deftest stage-uses-the-same-manifest-and-source-native-redacted-artifacts
  (let [home (full-hermes-home!)
        data-dir (temp-dir "itonami-hermes-stage-")
        preview (migration/preview
                 {:home home :migration-id "hermes-stage-test"
                  :captured-at "2026-09-01T00:00:00Z"})]
    (binding [migration/*export-profile!*
              (fn [_ _ profile-id output]
                (spit output (str "portable:" profile-id)))
              migration/*write-runtime-context!*
              (fn [profile-id _ output]
                (spit output (str "redacted context:" profile-id)))
              migration/*export-sessions!*
              (fn [_ profile-home output]
                (spit output (json/write-str
                              {:profile (.getName (io/file profile-home))
                               :messages [{:content "redacted"}]})))]
      (let [staged (migration/stage!
                    {:home home :data-dir data-dir :manifest preview
                     :staged-by {:user-id "user-1" :organization-id "org-1"}})
            bundle (io/file data-dir "bot-imports" "hermes-stage-test")]
        (is (= (:migration-id preview) (:migration-id staged)))
        (is (= (:schema preview) (:schema staged)))
        (is (= "staged" (:status staged)))
        (is (= 6 (count (mapcat :artifacts (:profiles staged)))))
        (is (.isFile (io/file bundle "manifest.json")))
        (is (every? #(and (= "staged" (:state %))
                          (pos? (:bytes %))
                          (= 64 (count (:sha256 %))))
                    (mapcat :artifacts (:profiles staged))))
        (is (= 2 (count (filter #(= "hermes-runtime-context" (:kind %))
                                (mapcat :artifacts (:profiles staged)))))
        (is (false? (get-in staged [:safety :creates-bots]))))))))

(deftest provision-connects-every-profile-to-an-inert-bot-and-scores-the-result
  (let [home (full-hermes-home!)
        data-dir (temp-dir "itonami-hermes-provision-")
        preview (migration/preview
                 {:home home :migration-id "hermes-provision-test"})
        captured (atom [])]
    (binding [migration/*export-profile!*
              (fn [_ _ profile-id output]
                (spit output (str "portable:" profile-id)))
              migration/*write-runtime-context!*
              (fn [profile-id _ output]
                (spit output (str "redacted context:" profile-id)))
              migration/*export-sessions!*
              (fn [_ profile-home output]
                (spit output
                      (json/write-str
                       {:id (str "session-" (.getName (io/file profile-home)))
                        :last_active "2026-09-01T00:00:00Z"
                        :messages [{:id "u" :role "user" :content "hello"}
                                   {:id "a" :role "assistant" :content "hi"}]})))]
      (let [staged (migration/stage!
                    {:home home :data-dir data-dir :manifest preview})]
        (with-redefs [bots/create-hermes-import!
                      (fn [_ _ request]
                        (swap! captured conj request)
                        {:id (str "bot-" (:profile-id request))})]
          (let [result
                (migration/provision!
                 {:configuration
                  {:providers [{:id "murakumo"
                                :models ["example/model"]}]}
                  :session {:user-id "u" :organization-id "o"}
                  :data-dir data-dir :manifest staged})]
            (is (= "provisioned" (:status result)))
            (is (= 2 (count (:profiles result))))
            (is (= ["default" "research"] (mapv :profile-id @captured)))
            (is (every? #(= 2 (count (:seed %))) @captured))
            (is (every? empty? (map #(select-keys % [:tools :accounts]) @captured)))
            (is (= 85 (get-in result [:compatibility :execution-model :percent])))
            (is (= 75 (get-in result [:compatibility :semantic-system :percent])))
            (is (= 65 (get-in result [:compatibility :zero-adjustment-runtime
                                      :percent])))
            (is (= 100 (get-in result [:compatibility :drop-in-core-api :percent])))
            (is (= {:exact 1 :profiles 2 :percent 50}
                   (get-in result [:compatibility :model-preservation])))))))))

(deftest stage-refuses-a-stale-or-different-manifest
  (let [home (full-hermes-home!)
        preview (migration/preview {:home home :migration-id "hermes-stale"})]
    (spit (io/file home "MEMORY.md") "changed after preview")
    (is (= :bot-import/source-changed
           (:type (ex-data
                   (try
                     (migration/stage! {:home home
                                        :data-dir (temp-dir "itonami-stale-")
                                        :manifest preview})
                     (catch clojure.lang.ExceptionInfo error error))))))))

(deftest migration-ids-and-profile-links-cannot-escape-the-bundle
  (let [home (full-hermes-home!)]
    (testing "the manifest id cannot become a destination path"
      (is (= :bot-import/invalid-migration-id
             (:type (ex-data
                     (try
                       (migration/preview {:home home
                                           :migration-id "hermes-../../outside"})
                       (catch clojure.lang.ExceptionInfo error error)))))))
    (testing "inventory does not follow a profile symlink outside Hermes"
      (let [before (migration/preview {:home home :migration-id "hermes-before"})
            external (temp-dir "itonami-external-")
            _ (spit (io/file external "must-not-cross.txt") "outside")
            link (.toPath (io/file home "profiles" "research" "linked-out"))]
        (java.nio.file.Files/createSymbolicLink
         link (.toPath external) (make-array java.nio.file.attribute.FileAttribute 0))
        (let [after (migration/preview {:home home :migration-id "hermes-after"})]
          (is (= (get-in before [:profiles 1 :source])
                 (get-in after [:profiles 1 :source]))))))))

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

(def ^:private declaring-hermes-job
  ;; The shape every real hermes prompt has: a short stated purpose followed by
  ;; kilobytes of operating instructions. Measured 2026-08-31, the manual alone
  ;; ran 1.2x to 7.0x the cap on all twelve prompt-bearing jobs.
  {:id "dd44" :name "Ingest Scout" :enabled true :state "running"
   :schedule {:kind "interval" :minutes 720} :last_status "ok" :failure_streak 0
   :prompt (str "## Objective\n\n"
                "Raise axis-ingest on one repo by giving it a regulatory source "
                "register whose every citation was fetched in that run.\n\n"
                "## Your one job this run\n\n"
                (apply str (repeat 200 "operating instructions that are not a purpose. ")))})

(deftest a-prompt-may-declare-its-own-objective-and-only-that-crosses
  (let [report (subject/import-report "hermes" {:home (hermes-home! [declaring-hermes-job])})
        proposal (first (:proposals report))]
    (is (= 1 (count (:importable report)))
        "a job whose manual is 7x the cap still imports when it states a purpose")
    (is (empty? (:not-importable report)))
    (is (str/starts-with? (:yakuwari/objective proposal) "Raise axis-ingest"))
    (is (not (str/includes? (:yakuwari/objective proposal) "operating instructions"))
        "the manual around the section does not cross -- only the section does")
    (is (< (count (:yakuwari/objective proposal)) 1000))))

(deftest a-declared-objective-is-not-exempt-from-the-cap
  ;; Asserting only "it was refused" would pass with or without the change --
  ;; without it the whole prompt is over the cap too, so the same verdict comes
  ;; back for a different reason and the test would be counting a refusal it did
  ;; not cause. So pin the NUMBER the refusal reports: it has to be the length of
  ;; the declared section, not of the manual around it. Measured 2026-08-31 with
  ;; the preference reverted, this is the assertion that goes red.
  (let [declared (apply str (repeat 60 "a purpose stated at some length. "))  ; 1980
        manual (apply str (repeat 300 "operating instructions that are not a purpose. "))
        job (assoc declaring-hermes-job
                   :prompt (str "## Objective\n\n" declared
                                "\n\n## Your one job this run\n\n" manual))
        report (subject/import-report "hermes" {:home (hermes-home! [job])})
        reason (:reason (first (:not-importable report)))]
    (is (empty? (:importable report))
        "declaring a section is a way to satisfy the cap, not a way around it")
    (is (str/includes? reason (str (count (str/trim declared))))
        "the refusal names the declared section's length")
    (is (not (str/includes? reason (str (count (str/trim (str "## Objective\n\n" declared
                                                             "\n\n## Your one job this run\n\n"
                                                             manual))))))
        "and not the whole prompt's -- which is what it would report without the preference")))

(deftest a-prompt-without-the-section-behaves-exactly-as-before
  (let [report (subject/import-report "hermes" {:home (hermes-home! [healthy-hermes-job])})
        proposal (first (:proposals report))]
    (is (= 1 (count (:importable report))))
    (is (= (str/trim (:prompt healthy-hermes-job)) (:yakuwari/objective proposal))
        "with no declared section the whole prompt is still what is judged")))

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
