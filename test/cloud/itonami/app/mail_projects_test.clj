(ns cloud.itonami.app.mail-projects-test
  "Filing mail against local projects.

  The behaviours worth pinning are the refusals and the arithmetic, not the
  happy path: a filing system is trusted in proportion to how loudly it admits
  what it did not file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.mail-projects :as mail-projects]
            [cloud.itonami.app.mail-account]
            [cloud.itonami.app.mail-sync]
            [cloud.itonami.app.project-repository :as projects]
            [cloud.itonami.app.store :as store]))

(def ^:private organization "org-mail-test")

(def ^:private run-id
  "A suffix making each run's project directories fresh.

  Filing is idempotent — a message already filed to the same project is not
  rewritten — so a test asserting 'three messages were written' passes once and
  then reports zero forever after. `target/test-data` survives between runs, and
  annexed object directories are read-only, so they are not casually deleted
  either. A new project per run is cheaper than fighting either fact."
  (subs (str (random-uuid)) 0 8))

(defn- fresh [name] (str name "-" run-id))

(defn- message [id from subject & [labels]]
  {:id id :from-email from :from from :subject subject
   :body (str "body of " id " — 本文")
   :received-at (str "2026-08-0" (inc (mod (count id) 9)) "T00:00:00Z")
   :labels (set (or labels []))})

(defn- project-directory
  "Where a project's Git repository is, found by walking for its slug: the path
  is `projects/<organization-storage-id>/<slug>` and that middle segment is a
  private hash."
  [project-id]
  (->> (file-seq (io/file (config/data-dir) "projects"))
       (filter #(and (.isDirectory %) (= project-id (.getName %))))
       first))

(defn- git
  "Run git and read only its stdout.

  Deliberately NOT merging stderr: this machine's git prints
  `error: could not read IPC response` from its filesystem monitor, and merged
  in it makes a clean tree look dirty and a log look one line longer."
  [directory & args]
  (let [builder (doto (ProcessBuilder.
                       ^java.util.List (into ["/usr/bin/git"] args))
                  (.directory directory))
        process (.start builder)
        output (slurp (.getInputStream process))]
    (.waitFor process)
    output))

(defn- filing-commits
  "Commits this code made, not every commit in the repository.

  `datalad create` contributes two of its own — the dataset and the text2git
  configuration — so a plain total measures DataLad's initialization as well as
  the filing, and the number changes if DataLad ever changes its setup."
  [directory]
  (->> (str/split-lines (git directory "log" "--oneline"))
       (filter #(str/includes? % "mail: file"))
       count))

(defn- seed-messages! [& messages]
  (store/transact! assoc-in [:mail :messages]
                   (into {} (map (juxt :id identity)) messages)))

(defn- reset! []
  (store/transact!
   (fn [state]
     (-> state
         (update :mail dissoc :messages :project-rules :project-assignments)
         (dissoc :chat-projects :project-workspaces :drive-artifacts)))))

(defn- age-keygen!
  "A throwaway age identity for the suite.

  Generated once per run rather than checked in: a committed private key is a
  committed private key even when it only ever guarded test fixtures."
  []
  (let [directory (io/file (config/data-dir) "age-test")
        identity-file (io/file directory "identity.txt")]
    (when-not (.isFile identity-file)
      (.mkdirs directory)
      (let [process (.start (doto (ProcessBuilder.
                                   ^java.util.List ["/opt/homebrew/bin/age-keygen"
                                                    "-o" (.getPath identity-file)])
                              (.redirectErrorStream true)))]
        (slurp (.getInputStream process))
        (.waitFor process)))
    (let [recipient (->> (str/split-lines (slurp identity-file))
                         (some #(second (re-matches #"# public key: (age1\S+)" %))))]
      {:identity (.getPath identity-file) :recipient recipient})))

(def ^:private age-key (delay (age-keygen!)))

(defn- with-recipients [run]
  (with-redefs [projects/age-recipients (constantly [(:recipient @age-key)])]
    (run)))

(use-fixtures :each (fn [run] (reset!) (with-recipients run) (reset!)))

(defn- project! [id]
  (projects/create-project! {:organization-id organization
                             :user-id "user-1" :project-id id}
                            {:title id}))

;; ---------------------------------------------------------------------------
;; rules

(deftest a-rule-cannot-name-a-project-that-does-not-exist
  (testing "a typo would otherwise file mail into a project nobody can open,
            and it would look like it worked"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"project がありません"
         (mail-projects/add-rule! organization
                                  {:project "alpah"
                                   :match {:from-domain "example.com"}})))
    (is (empty? (mail-projects/rules organization)))))

(deftest a-rule-must-say-something
  (project! "alpha")
  (testing "a rule with no clauses would match every message ever received"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"条件を1つ以上"
         (mail-projects/add-rule! organization {:project "alpha" :match {}})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"条件を1つ以上"
         (mail-projects/add-rule! organization
                                  {:project "alpha"
                                   :match {:from-domain "   "}})))))

(deftest a-domain-clause-is-anchored
  (project! "alpha")
  (let [rule (:rule (mail-projects/add-rule!
                     organization
                     {:project "alpha" :match {:from-domain "example.com"}}))]
    (testing "subdomains match"
      (is (mail-projects/matches? rule (message "1" "a@mail.example.com" "x")))
      (is (mail-projects/matches? rule (message "2" "a@example.com" "x"))))
    (testing "a domain that merely ends with those characters does not"
      (is (not (mail-projects/matches? rule (message "3" "a@notexample.com" "x"))))
      (is (not (mail-projects/matches? rule (message "4" "a@example.com.cn" "x")))))))

(deftest every-clause-must-hold
  (project! "alpha")
  (let [rule (:rule (mail-projects/add-rule!
                     organization
                     {:project "alpha"
                      :match {:from-domain "example.com"
                              :subject-contains "invoice"}}))]
    (testing "narrowing is what writing a second clause is for"
      (is (mail-projects/matches? rule (message "1" "a@example.com" "Your INVOICE")))
      (is (not (mail-projects/matches? rule (message "2" "a@example.com" "hello"))))
      (is (not (mail-projects/matches? rule (message "3" "a@other.com" "invoice")))))))

(deftest a-label-clause-reads-what-classify-derived
  (project! "alpha")
  (let [rule (:rule (mail-projects/add-rule! organization
                                             {:project "alpha"
                                              :match {:label "finance"}}))]
    (is (mail-projects/matches? rule (message "1" "a@x.com" "hi" [:finance])))
    (is (not (mail-projects/matches? rule (message "2" "a@x.com" "hi" [:newsletter]))))))

(deftest the-first-matching-rule-wins
  (project! "alpha")
  (project! "beta")
  (mail-projects/add-rule! organization
                           {:project "alpha" :match {:from-domain "example.com"}})
  (mail-projects/add-rule! organization
                           {:project "beta" :match {:from-domain "example.com"}})
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/apply-rules! organization)
  (is (= "alpha" (:project-id (get (mail-projects/assignments organization) "m1")))
      "order is the organization's own and must be visible in the outcome"))

(deftest a-removed-rule-is-named-when-it-was-not-there
  (project! "alpha")
  (let [rule (:rule (mail-projects/add-rule! organization
                                             {:project "alpha"
                                              :match {:label "finance"}}))]
    (is (:ok? (mail-projects/remove-rule! organization (:rule/id rule))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rule がありません"
                          (mail-projects/remove-rule! organization "rule-nope")))))

;; ---------------------------------------------------------------------------
;; applying

(deftest applying-reports-what-it-did-not-file
  (project! "alpha")
  (mail-projects/add-rule! organization
                           {:project "alpha" :match {:from-domain "example.com"}})
  (seed-messages! (message "m1" "a@example.com" "x")
                  (message "m2" "b@example.com" "y")
                  (message "m3" "c@elsewhere.jp" "z"))
  (let [result (mail-projects/apply-rules! organization)]
    (is (= 3 (:considered result)))
    (is (= 2 (:assigned result)))
    (is (= 1 (:unmatched result))
        "the pile the rules do not catch is the number worth reading")
    (is (= 2 (:changed result))))

  (testing "running it again changes nothing"
    (let [again (mail-projects/apply-rules! organization)]
      (is (= 2 (:assigned again)))
      (is (= 0 (:changed again))))))

(deftest a-rule-never-undoes-a-human-decision
  (project! "alpha")
  (project! "beta")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/assign! organization "m1" "beta" "user-1")
  (mail-projects/add-rule! organization
                           {:project "alpha" :match {:from-domain "example.com"}})
  (let [result (mail-projects/apply-rules! organization)]
    (testing "filing something by hand would otherwise last until the next sync"
      (is (= "beta" (:project-id (get (mail-projects/assignments organization) "m1"))))
      (is (= 1 (:manual result))))

    (testing "the message IS considered now — exclusion moved from the message
              to the filing, so a hand-filed message can still be picked up by a
              rule aimed at a DIFFERENT project. What must not change is the
              hand-filed project itself."
      (is (= 1 (:considered result)))
      (is (= "alpha" (:project-id (get-in (mail-projects/filings organization)
                                          ["m1" "alpha"])))
          "the rule filed into its own project")
      (is (= :manual (:by (get-in (mail-projects/filings organization)
                                  ["m1" "beta"])))
          "and left the human decision alone"))))

;; ---------------------------------------------------------------------------
;; manual assignment

(deftest assignment-refuses-what-it-cannot-resolve
  (project! "alpha")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"そのメールはありません"
                        (mail-projects/assign! organization "nope" "alpha" "user-1")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"project がありません"
                        (mail-projects/assign! organization "m1" "nope" "user-1"))))

(deftest unassigning-does-not-delete-the-message
  (project! "alpha")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/assign! organization "m1" "alpha" "user-1")
  (mail-projects/unassign! organization "m1")
  (is (empty? (mail-projects/assignments organization)))
  (is (some? (get-in (store/snapshot) [:mail :messages "m1"]))
      "it returns to the inbox it never left"))

;; ---------------------------------------------------------------------------
;; reading

(deftest the-overview-counts-both-sides
  (project! "alpha")
  (mail-projects/add-rule! organization
                           {:project "alpha" :match {:from-domain "example.com"}})
  (seed-messages! (message "m1" "a@example.com" "x")
                  (message "m2" "c@elsewhere.jp" "z"))
  (mail-projects/apply-rules! organization)
  (let [overview (mail-projects/overview organization)]
    (is (= 2 (:messages overview)))
    (is (= 1 (:assigned overview)))
    (is (= 1 (:unassigned overview)))
    (is (= [{:project-id "alpha" :count 1 :manual 0}] (:projects overview)))))

(deftest project-mail-lists-only-that-project
  (project! "alpha")
  (project! "beta")
  (seed-messages! (message "m1" "a@example.com" "one")
                  (message "m2" "b@example.com" "two"))
  (mail-projects/assign! organization "m1" "alpha" "user-1")
  (mail-projects/assign! organization "m2" "beta" "user-1")
  (let [alpha (mail-projects/project-mail organization "alpha")]
    (is (= 1 (count (:items alpha))))
    (is (= "one" (:subject (first (:items alpha)))))
    (is (= "manual" (:assigned-by (first (:items alpha)))))))

(deftest unassigned-groups-the-senders-worth-a-rule
  (project! "alpha")
  (seed-messages! (message "m1" "a@loud.example" "x")
                  (message "m2" "b@loud.example" "y")
                  (message "m3" "c@quiet.jp" "z"))
  (let [loose (mail-projects/unassigned organization)]
    (is (= 3 (:count loose)))
    (is (= {:from-domain "loud.example" :count 2} (first (:senders loose)))
        "the useful next action is one more rule for the domain that keeps
         appearing, so the senders are ranked")))

(deftest one-organization-does-not-file-into-another
  (project! "alpha")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/assign! organization "m1" "alpha" "user-1")
  (is (empty? (mail-projects/assignments "org-other")))
  (is (= 0 (:assigned (mail-projects/overview "org-other")))))

;; ---------------------------------------------------------------------------
;; artifacts

(deftest filing-writes-the-message-into-the-project-and-commits
  (project! (fresh "artifacts"))
  (seed-messages! (message "m1" "a@example.com" "Quarterly invoice"))
  (mail-projects/assign! organization "m1" (fresh "artifacts") "user-1")
  (let [directory (project-directory (fresh "artifacts"))
        envelopes (->> (file-seq (io/file directory "mail"))
                       (filter #(str/ends-with? (.getName %) ".edn")))]
    (testing "the envelope is tracked source"
      (is (= 1 (count envelopes)))
      (let [written (read-string (slurp (first envelopes)))]
        (is (= "m1" (:mail/id written)))
        (is (= "Quarterly invoice" (:mail/subject written)))
        (is (= (fresh "artifacts") (:filed/project written)))
        (is (= "manual" (:filed/by written)))))

    (testing "the commit is the app's, not the operator's.

              DataLad has no `-c` of its own and picks up whatever git config
              the machine has — measured, it signed as the owner's personal
              iCloud relay address. Filing mail must not write a person's name
              into a commit they did not author."
      (is (= "Cloud Itonami <itonami@localhost>"
             (str/trim (git directory "log" "-1" "--pretty=%an <%ae>")))))

    (testing "and there is a commit, not just a file"
      (is (str/includes? (git directory "log" "--oneline") "file 1 message"))
      (is (str/blank? (str/trim (git directory "status" "--porcelain")))))))

(deftest the-body-is-in-git-and-is-ciphertext
  (project! (fresh "private"))
  (seed-messages! (message "m1" "a@example.com" "Subject line"))
  (mail-projects/assign! organization "m1" (fresh "private") "user-1")
  (let [directory (project-directory (fresh "private"))
        tracked (git directory "ls-files")]
    (testing "the body IS tracked — encrypting it is what let it stop being
              excluded, and an excluded body travels with nothing"
      (is (str/includes? tracked ".eml.age"))
      (is (str/includes? tracked "mail/")))

    (testing "and git-annex holds it, so the bytes are not in every clone"
      (is (str/includes? (git directory "check-attr" "-a"
                              (->> (str/split-lines tracked)
                                   (filter #(str/ends-with? % ".eml.age"))
                                   first))
                         "annex")))

    (testing "what Git carries is unreadable: the plaintext is not in HEAD"
      (is (str/blank? (git directory "grep" "-r" "本文" "HEAD"))))

    (testing "the file on disk is an age envelope, not the message"
      (let [body (->> (file-seq (io/file directory "mail"))
                      (filter #(str/ends-with? (.getName %) ".eml.age"))
                      first)
            ;; Annexed content is a symlink into .git/annex; follow it.
            content (slurp body)]
        (is (str/starts-with? content "age-encryption.org/v1"))
        (is (not (str/includes? content "本文")))))

    (testing "and it decrypts back to exactly what arrived"
      (let [body (->> (file-seq (io/file directory "mail"))
                      (filter #(str/ends-with? (.getName %) ".eml.age"))
                      first)
            process (.start (doto (ProcessBuilder.
                                   ^java.util.List
                                   ["/opt/homebrew/bin/age" "-d"
                                    "-i" (:identity @age-key)
                                    (.getCanonicalPath body)])
                              (.redirectErrorStream true)))
            plaintext (slurp (.getInputStream process))]
        (.waitFor process)
        (is (= "body of m1 — 本文" (str/trim plaintext)))))

    (testing "and the envelope says how it was sealed and to whom"
      (let [written (->> (file-seq (io/file directory "mail"))
                         (filter #(str/ends-with? (.getName %) ".edn"))
                         first slurp read-string)]
        (is (= "age" (:mail/body-encryption written)))
        (is (= [(:recipient @age-key)] (:mail/body-recipients written)))
        (is (= 64 (count (:mail/body-sha256 written))))))))

(deftest with-no-recipient-the-body-is-skipped-not-written-in-the-clear
  (project! (fresh "norecipient"))
  (seed-messages! (message "m1" "a@example.com" "Subject line"))
  (with-redefs [projects/age-recipients (constantly [])]
    (let [result (mail-projects/assign! organization "m1" (fresh "norecipient") "user-1")
          artifact (first (:artifacts result))]
      (testing "a filing system that silently downgrades to plaintext is worse
                than one that refuses, because nothing about the result looks
                different"
        (is (= 1 (:written artifact)))
        (is (= 0 (:bodies artifact)))
        (is (= 1 (:bodies-skipped artifact)))
        (is (str/includes? (:reason artifact) "AGE_RECIPIENTS")))))
  (let [directory (project-directory (fresh "norecipient"))]
    (is (not (str/includes? (git directory "ls-files") ".eml.age")))
    (is (str/blank? (git directory "grep" "-r" "本文" "HEAD")))))

(deftest a-project-made-before-mail-filing-still-ignores-bodies
  (testing "such a project already HAS a .gitignore, so a write-if-absent branch
            would never reach it and the first filed body would land in Git"
    (project! (fresh "legacy"))
    (let [directory (project-directory (fresh "legacy"))
          gitignore (io/file directory ".gitignore")]
      (spit gitignore ".itonami/runtime/\n.conversations/\n")
      (seed-messages! (message "m1" "a@example.com" "x"))
      (mail-projects/assign! organization "m1" (fresh "legacy") "user-1")
      (is (str/includes? (slurp gitignore) ".mail/"))
      (is (not (str/includes? (git directory "ls-files") ".mail/"))))))

(deftest applying-rules-commits-once-per-project-and-only-what-changed
  (project! (fresh "bulk"))
  (mail-projects/add-rule! organization
                           {:project (fresh "bulk") :match {:from-domain "example.com"}})
  (seed-messages! (message "m1" "a@example.com" "one")
                  (message "m2" "b@example.com" "two")
                  (message "m3" "c@example.com" "three"))
  (let [directory (project-directory (fresh "bulk"))
        ;; Counted as a DELTA. `target/test-data` survives between runs, so the
        ;; repository may already carry commits from an earlier one — an
        ;; absolute count tests the machine's history, not this code.
        before (filing-commits directory)
        result (mail-projects/apply-rules! organization "user-1")]
    (is (nil? (:error (first (:artifacts result)))))
    (is (= 3 (:written (first (:artifacts result)))))
    (testing "three messages, one commit — it was one act"
      (is (= 1 (- (filing-commits directory) before))))

    (testing "re-applying writes nothing, or every sync would add an empty
              revision to every project"
      (let [again (mail-projects/apply-rules! organization "user-1")]
        (is (= 0 (:changed again)))
        (is (empty? (:artifacts again))))
      (is (= 1 (- (filing-commits directory) before))))))

(deftest a-git-failure-does-not-lose-the-assignment
  (project! "resilient")
  (seed-messages! (message "m1" "a@example.com" "x"))
  (with-redefs [projects/file-mail!
                (fn [& _] (throw (ex-info "git exploded" {:type :test/boom})))]
    (let [result (mail-projects/assign! organization "m1" "resilient" "user-1")]
      (testing "the decision is recorded and the failure is reported, not thrown —
                the artifact is a projection of the decision, not the decision"
        (is (:ok? result))
        (is (= "git exploded" (:error (first (:artifacts result))))))))
  (is (= "resilient"
         (:project-id (get (mail-projects/assignments organization) "m1")))))

;; ---------------------------------------------------------------------------
;; filing as mail arrives

(deftest only-organizations-with-rules-are-run
  (testing "the rule set is what says an organization wants filing, so it is
            also what says whose rules to run after a sync"
    (is (empty? (mail-projects/organizations-with-rules)))
    (project! (fresh "auto"))
    (mail-projects/add-rule! organization
                             {:project (fresh "auto")
                              :match {:from-domain "example.com"}})
    (is (= [organization] (mail-projects/organizations-with-rules)))))

(deftest apply-all-reports-per-organization
  (project! (fresh "auto"))
  (mail-projects/add-rule! organization
                           {:project (fresh "auto")
                            :match {:from-domain "example.com"}})
  (seed-messages! (message "m1" "a@example.com" "one")
                  (message "m2" "b@elsewhere.jp" "two"))
  (let [result (mail-projects/apply-all! "user-1")]
    (is (= 1 (:organizations result)))
    (let [[only] (:results result)]
      (is (= organization (:organization-id only))
          "a total would hide two organizations whose rules behave differently")
      (is (= 1 (:assigned only)))
      (is (= 1 (:unmatched only))))))

(deftest apply-all-with-no-rules-anywhere-does-nothing
  (let [result (mail-projects/apply-all!)]
    (is (= 0 (:organizations result)))
    (is (empty? (:results result)))))

(deftest a-sync-files-what-it-just-fetched
  (testing "the wiring itself: sync-all! must reach the rules, or mail syncs
            every minute and is filed whenever a person remembers"
    (project! (fresh "on-sync"))
    (mail-projects/add-rule! organization
                             {:project (fresh "on-sync")
                              :match {:from-domain "example.com"}})
    (with-redefs [cloud.itonami.app.mail-account/accounts (constantly [])]
      ;; No accounts, so nothing is fetched — but the filing half must still run
      ;; and report, which is what proves it is wired rather than reachable.
      (let [result (cloud.itonami.app.mail-sync/sync-all!)]
        (is (= :completed (:status result)))
        (is (some? (:filed result)))
        (is (= 1 (:organizations (:filed result))))))

    (testing "and mail already in the store is filed by that run"
      (seed-messages! (message "m1" "a@example.com" "arrived"))
      (with-redefs [cloud.itonami.app.mail-account/accounts (constantly [])]
        (cloud.itonami.app.mail-sync/sync-all!))
      (is (= (fresh "on-sync")
             (:project-id (get (mail-projects/assignments organization) "m1")))))))

(deftest a-filing-failure-does-not-fail-the-sync
  (testing "the mail is already in the store; losing that because a project
            repository was busy would be the wrong trade"
    (with-redefs [cloud.itonami.app.mail-account/accounts (constantly [])
                  mail-projects/apply-all!
                  (fn [& _] (throw (ex-info "annex exploded" {})))]
      (let [result (cloud.itonami.app.mail-sync/sync-all!)]
        (is (= :completed (:status result)))
        (is (= "annex exploded" (:error (:filed result))))))))

;; ---------------------------------------------------------------------------
;; Apple private relay

(deftest a-relay-address-is-read-back-to-its-real-sender
  (testing "measured on this inbox: 37% of messages arrive through Apple's
            relay, and to a rule matching on domain they all look like one
            sender called icloud.com"
    (is (= "notify.cloudflare.com"
           (mail-projects/relay-origin
            "noreply_at_notify_cloudflare_com_2kwm5vmzyx9343_388566c2@icloud.com")))
    (is (= "mailmagazine.asoview.com"
           (mail-projects/relay-origin
            "noreply_at_mailmagazine_asoview_com_kmv9c9ghdw@icloud.com")))
    (is (= "ticketboard.jp"
           (mail-projects/relay-origin
            "pr_tickebo_at_ticketboard_jp_gfb2tabcwp_46s@icloud.com"))))

  (testing "a two-part suffix stays whole — cutting at the first TLD segment
            would turn example.co.jp into example.co"
    (is (= "example.co.jp"
           (mail-projects/relay-origin
            "someone_at_example_co_jp_zzz@privaterelay.appleid.com"))))

  (testing "and a hostname of several labels keeps all of them"
    (is (= "mk.ooedoonsen.jp"
           (mail-projects/relay-origin
            "iifuro_at_mk_ooedoonsen_jp_wpgabc123gvx2e_1ehz@icloud.com"))))

  (testing "anything that is not a relay address is left alone"
    (is (nil? (mail-projects/relay-origin "plain@rakuten-bank.co.jp")))
    (is (nil? (mail-projects/relay-origin "nothing-encoded@icloud.com")))
    (is (nil? (mail-projects/relay-origin nil)))))

(deftest a-domain-rule-matches-through-the-relay
  (project! (fresh "relay"))
  (let [rule (:rule (mail-projects/add-rule!
                     organization
                     {:project (fresh "relay")
                      :match {:from-domain "cloudflare.com"}}))]
    (testing "the rule names the real domain and never has to know about Apple"
      (is (mail-projects/matches?
           rule
           (message "m1"
                    "noreply_at_notify_cloudflare_com_2kwm5vmzyx9343_388@icloud.com"
                    "Your domain"))))

    (testing "and it still does not match a different sender behind the same relay"
      (is (not (mail-projects/matches?
                rule
                (message "m2"
                         "noreply_at_service_alibaba_com_b8m7kvtbzk_x@icloud.com"
                         "Order")))))))

(deftest relay-mail-is-grouped-by-its-real-sender-in-the-unassigned-report
  (testing "the report exists to say which rule to write next, and one bucket
            called icloud.com is the one answer that helps nobody"
    (seed-messages!
     (message "m1" "noreply_at_notify_cloudflare_com_aaa@icloud.com" "one")
     (message "m2" "noreply_at_notify_cloudflare_com_bbb@icloud.com" "two")
     (message "m3" "noreply_at_service_alibaba_com_ccc@icloud.com" "three"))
    (let [senders (:senders (mail-projects/unassigned organization))
          by-domain (into {} (map (juxt :from-domain :count)) senders)]
      (is (= 2 (get by-domain "notify.cloudflare.com")))
      (is (= 1 (get by-domain "service.alibaba.com")))
      (is (nil? (get by-domain "icloud.com"))))))

;; ---------------------------------------------------------------------------
;; a message belongs to more than one project

(deftest a-message-can-be-filed-into-several-projects
  (testing "an invoice from a law firm belongs in billing AND legal, and filing
            it once means whoever opens the other project does not have it"
    (project! (fresh "billing"))
    (project! (fresh "legal"))
    (seed-messages! (message "m1" "billing@lawfirm.example" "Invoice"))
    (mail-projects/assign! organization "m1" (fresh "billing") "user-1")
    (mail-projects/assign! organization "m1" (fresh "legal") "user-1")
    (is (= [(fresh "billing") (fresh "legal")]
           (mail-projects/projects-of organization "m1")))
    (testing "and each project lists it"
      (is (= 1 (count (:items (mail-projects/project-mail
                               organization (fresh "billing"))))))
      (is (= 1 (count (:items (mail-projects/project-mail
                               organization (fresh "legal")))))))))

(deftest unassigning-names-which-project
  (project! (fresh "a"))
  (project! (fresh "b"))
  (seed-messages! (message "m1" "x@example.com" "both"))
  (mail-projects/assign! organization "m1" (fresh "a") "user-1")
  (mail-projects/assign! organization "m1" (fresh "b") "user-1")

  (testing "removing one filing leaves the other"
    (mail-projects/unassign! organization "m1" (fresh "a"))
    (is (= [(fresh "b")] (mail-projects/projects-of organization "m1"))))

  (testing "and removing with no project removes it from all — what the
            single-filing version meant"
    (mail-projects/unassign! organization "m1")
    (is (empty? (mail-projects/projects-of organization "m1")))
    (is (some? (get-in (store/snapshot) [:mail :messages "m1"]))
        "the message itself is still there")))

(deftest every-matching-rule-files-not-only-the-first
  (project! (fresh "billing"))
  (project! (fresh "legal"))
  (mail-projects/add-rule! organization
                           {:project (fresh "billing")
                            :match {:subject-contains "invoice"}})
  (mail-projects/add-rule! organization
                           {:project (fresh "legal")
                            :match {:from-domain "lawfirm.example"}})
  (seed-messages! (message "m1" "billing@lawfirm.example" "Your invoice"))
  (let [result (mail-projects/apply-rules! organization "user-1")]
    (testing "stopping at the first match made rule ORDER encode a priority
              nobody had decided on"
      (is (= 2 (:filings result)))
      (is (= 1 (:messages-filed result)))
      (is (= [(fresh "billing") (fresh "legal")]
             (mail-projects/projects-of organization "m1"))))))

(deftest two-rules-for-one-project-file-once
  (project! (fresh "one"))
  (mail-projects/add-rule! organization
                           {:project (fresh "one") :match {:from-domain "example.com"}})
  (mail-projects/add-rule! organization
                           {:project (fresh "one") :match {:subject-contains "hello"}})
  (seed-messages! (message "m1" "a@example.com" "hello there"))
  (let [result (mail-projects/apply-rules! organization "user-1")]
    (is (= 1 (:filings result)))
    (is (= [(fresh "one")] (mail-projects/projects-of organization "m1")))))

(deftest a-manual-filing-does-not-stop-rules-filing-elsewhere
  (project! (fresh "byhand"))
  (project! (fresh "byrule"))
  (seed-messages! (message "m1" "a@example.com" "x"))
  (mail-projects/assign! organization "m1" (fresh "byhand") "user-1")
  (mail-projects/add-rule! organization
                           {:project (fresh "byrule")
                            :match {:from-domain "example.com"}})
  (mail-projects/apply-rules! organization "user-1")
  (testing "excluding the message whole meant filing it by hand into one project
            stopped the rules from ever filing it into another"
    (is (= [(fresh "byhand") (fresh "byrule")]
           (mail-projects/projects-of organization "m1"))))
  (testing "and the manual filing is still manual"
    (is (= :manual (:by (get-in (mail-projects/filings organization)
                                ["m1" (fresh "byhand")]))))))

(deftest the-old-single-filing-shape-is-still-readable
  (testing "988 messages were filed before the shape changed; a one-shot rewrite
            of somebody's filing is a worse risk than reading both shapes"
    (store/transact! assoc-in [:mail :project-assignments organization "old"]
                     {:project-id "legacy" :by :rule :rule-id "r-1" :at "then"})
    (is (= ["legacy"] (mail-projects/projects-of organization "old")))
    (is (= "legacy" (:project-id (get (mail-projects/assignments organization)
                                      "old"))))))

;; ---------------------------------------------------------------------------
;; threads

(deftest a-whole-conversation-is-filed-at-once
  (project! (fresh "thread"))
  (store/transact! assoc-in [:mail :messages "t1"]
                   {:id "t1" :thread-id "T" :from-email "a@x.com" :from "a"
                    :subject "first" :body "1" :received-at "2026-08-01T00:00:00Z"
                    :labels #{}})
  (store/transact! assoc-in [:mail :messages "t2"]
                   {:id "t2" :thread-id "T" :from-email "b@x.com" :from "b"
                    :subject "Re: first" :body "2" :received-at "2026-08-02T00:00:00Z"
                    :labels #{}})
  (store/transact! assoc-in [:mail :messages "other"]
                   {:id "other" :thread-id "U" :from-email "c@x.com" :from "c"
                    :subject "unrelated" :body "3" :received-at "2026-08-03T00:00:00Z"
                    :labels #{}})
  (let [result (mail-projects/assign-thread! organization "T" (fresh "thread") "user-1")]
    (is (= 2 (:messages result))
        "nobody decides that the third reply belongs to legal and the fourth
         does not")
    (is (= [(fresh "thread")] (mail-projects/projects-of organization "t1")))
    (is (= [(fresh "thread")] (mail-projects/projects-of organization "t2")))
    (is (empty? (mail-projects/projects-of organization "other")))))

(deftest an-unknown-thread-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"そのスレッドはありません"
                        (mail-projects/assign-thread! organization "nope"
                                                      "p" "user-1"))))
