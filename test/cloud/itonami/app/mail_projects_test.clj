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
      (is (= 0 (:considered result)))
      (is (= 1 (:manual result))))))

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
