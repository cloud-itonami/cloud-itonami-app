(ns cloud.itonami.app.mail-projects
  "Which local project a message belongs to.

  ## An assignment, not a move

  Mail already has two planes and this is a third laid over them, not a change
  to either. `[:mail :messages]` is what the accounts returned — the same for
  everyone, and not ours to rewrite. `[:mail :marks <principal>]` is what one
  person has done with it. Assignment is organization-scoped, because a project
  is shared: two people looking at the same project see the same mail filed
  against it, while what each has read stays their own.

  Nothing is moved and nothing is deleted. Unassigning a message puts it back in
  the inbox it never left.

  ## Filing also writes the message into the project

  An assignment that existed only in this store would be a filing system whose
  filing you cannot see: the project is a Git repository, and a message that
  belongs to it should be IN it. `project-repository/file-mail!` writes the
  envelope as tracked source and the body as a git-ignored plaintext copy, then
  commits — the same boundary that namespace already draws between project
  source and conversation history.

  The write is best-effort and reported, never fatal. The assignment is the
  decision; the artifact is its projection, and a Git failure must not lose the
  decision it was projecting.

  ## Rules are deterministic, and that is the point

  A rule matches on the envelope: sender address, sender domain, a substring of
  the subject, or one of the labels `mail-sync/classify` already derives. Every
  clause a rule names must hold — narrowing is what makes a rule
  explainable — and the first matching rule wins, so order is meaningful and
  visible.

  There is deliberately no model in this path. An LLM asked \"which project is
  this invoice for?\" will answer confidently for mail that belongs to none of
  them, and a filing system that is confidently wrong is worse than one that
  leaves things unfiled: the unfiled pile is visible, and a wrong assignment is
  not. Anything the rules do not catch stays unassigned and is counted, so the
  gap is a number rather than a silence.

  ## A human decision is not undone by a rule

  Manual assignment records `:by :manual`, and `apply-rules!` never overwrites
  it. Rules are re-run whenever mail arrives; without that, filing something by
  hand would last exactly until the next sync.

  ## A rule cannot name a project that does not exist

  Checked when the rule is written, against this organization's catalogue. A
  typo would otherwise file mail into a project nobody can open, and it would
  look like it worked."
  (:require [clojure.string :as str]
            [cloud.itonami.app.project-repository :as projects]
            [cloud.itonami.app.store :as store]))

(def schema "cloud.itonami.app.mail-projects.v1")

(defonce ^:private write-lock (Object.))

(def match-keys
  "Every clause a rule may name. Listed so the interface and the refusal message
  agree with what `matches?` actually reads."
  [:from :from-domain :subject-contains :label])

;; ---------------------------------------------------------------------------
;; store

(defn- rules-path [organization-id] [:mail :project-rules organization-id])
(defn- assignments-path [organization-id]
  [:mail :project-assignments organization-id])

(defn rules
  "This organization's rules, in the order they are applied."
  [organization-id]
  (vec (get-in (store/snapshot) (rules-path organization-id) [])))

(defn assignments
  "message id -> assignment, for this organization."
  [organization-id]
  (get-in (store/snapshot) (assignments-path organization-id) {}))

;; ---------------------------------------------------------------------------
;; matching

(defn- domain-of [address]
  (some-> address str str/lower-case (str/split #"@") second str/trim not-empty))

(defn- clause-matches? [message [key value]]
  (let [value (str/lower-case (str/trim (str value)))]
    (case key
      :from (= value (str/lower-case (str (:from-email message))))
      ;; Suffix, so `co.jp` catches `mail.rakuten-bank.co.jp`. Anchored at a dot
      ;; boundary or the whole domain, or `example.com` would also catch
      ;; `notexample.com`.
      :from-domain (let [domain (or (domain-of (:from-email message)) "")]
                     (or (= domain value)
                         (str/ends-with? domain (str "." value))))
      :subject-contains (str/includes? (str/lower-case (str (:subject message)))
                                       value)
      :label (contains? (set (map #(str/lower-case (name %))
                                  (:labels message)))
                        value)
      false)))

(defn matches?
  "Whether every clause this rule names holds for this message.

  Every one, not any: a rule that widened as it gained clauses would be the
  opposite of what writing another clause is for."
  [rule message]
  (let [clauses (select-keys (:rule/match rule) match-keys)]
    (and (seq clauses)
         (every? #(clause-matches? message %) clauses))))

(defn first-match
  "The first rule that matches, or nil. Order is the organization's own."
  [rules message]
  (some #(when (matches? % message) %) rules))

;; ---------------------------------------------------------------------------
;; rules

(defn- project-exists? [organization-id project-id]
  (boolean
   (some #(= project-id (:project-id %))
         (projects/projects {:organization-id organization-id}))))

(defn- normalize-match [match]
  (let [clauses (->> (select-keys (or match {}) match-keys)
                     (keep (fn [[k v]]
                             (when-let [v (not-empty (str/trim (str v)))]
                               [k v])))
                     (into {}))]
    (when (empty? clauses)
      (throw (ex-info (str "条件を1つ以上指定してください（"
                           (str/join " / " (map name match-keys)) "）")
                      {:type :mail-projects/empty-rule
                       :match-keys (mapv name match-keys)})))
    clauses))

(defn add-rule!
  "Append a rule. Refuses a project this organization does not have."
  [organization-id {:keys [project match]}]
  (let [project-id (not-empty (str/trim (str project)))]
    (when-not project-id
      (throw (ex-info "project を指定してください。"
                      {:type :mail-projects/no-project})))
    (when-not (project-exists? organization-id project-id)
      (throw (ex-info (str "project がありません: " project-id
                           "（`itonami projects list` で確認できます）")
                      {:type :mail-projects/unknown-project
                       :project project-id})))
    (let [clauses (normalize-match match)
          rule {:rule/id (str "rule-" (subs (str (random-uuid)) 0 8))
                :rule/project project-id
                :rule/match clauses
                :rule/created-at (store/now)}]
      (locking write-lock
        (store/transact! update-in (rules-path organization-id)
                         (fnil conj []) rule))
      {:schema schema :ok? true :rule rule})))

(defn remove-rule! [organization-id rule-id]
  (locking write-lock
    (let [before (count (rules organization-id))]
      (store/transact! update-in (rules-path organization-id)
                       (fn [rules]
                         (vec (remove #(= rule-id (:rule/id %)) (or rules [])))))
      (let [after (count (rules organization-id))]
        (when (= before after)
          (throw (ex-info (str "rule がありません: " rule-id)
                          {:type :mail-projects/unknown-rule :rule rule-id})))
        {:schema schema :ok? true :removed rule-id}))))

;; ---------------------------------------------------------------------------
;; assignment

(defn- messages []
  (vals (get-in (store/snapshot) [:mail :messages] {})))

(defn- message-by-id [id]
  (get-in (store/snapshot) [:mail :messages id]))

(defn- materialize!
  "Project assignments into the projects' repositories, grouped by project.

  Grouped, so filing twenty messages across three projects is three commits
  rather than twenty. Failures are collected per project rather than thrown: the
  decision is already recorded, and losing it because Git was busy would be the
  wrong trade."
  [organization-id user-id assignment-pairs]
  (->> assignment-pairs
       (group-by (comp :project-id second))
       (mapv (fn [[project-id pairs]]
               (try
                 (projects/file-mail!
                  {:organization-id organization-id
                   :user-id user-id
                   :project-id project-id}
                  (keep (fn [[message-id assignment]]
                          (when-let [message (message-by-id message-id)]
                            {:message message :assignment assignment}))
                        pairs))
                 (catch Exception error
                   {:project-id project-id
                    :written 0
                    :error (.getMessage error)}))))))

(defn assign!
  "File a message against a project by hand.

  `:by :manual` is what makes it survive the next `apply-rules!`. Refuses an
  unknown message rather than storing a mark against a string that will never
  resolve — the same rule `mailbox/known!` applies to its own marks."
  [organization-id message-id project-id actor]
  (let [message-id (not-empty (str/trim (str message-id)))
        project-id (not-empty (str/trim (str project-id)))]
    (when-not (message-by-id message-id)
      (throw (ex-info (str "そのメールはありません: " message-id)
                      {:type :mail/not-found :id message-id})))
    (when-not (project-exists? organization-id project-id)
      (throw (ex-info (str "project がありません: " project-id)
                      {:type :mail-projects/unknown-project
                       :project project-id})))
    (let [assignment {:project-id project-id :by :manual
                      :actor actor :at (store/now)}]
      (locking write-lock
        (store/transact! assoc-in
                         (conj (assignments-path organization-id) message-id)
                         assignment))
      {:schema schema :ok? true :message message-id :assignment assignment
       :artifacts (materialize! organization-id actor
                                [[message-id assignment]])})))

(defn unassign!
  "Take a message out of its project. It returns to the inbox it never left."
  [organization-id message-id]
  (locking write-lock
    (store/transact! update-in (assignments-path organization-id)
                     (fn [current] (dissoc (or current {}) message-id))))
  {:schema schema :ok? true :message message-id :assignment nil})

(defn apply-rules!
  "Run the rules over every message this organization has not filed by hand.

  Returns what changed and what did not, because 'applied' with no numbers is
  indistinguishable from a rule set that matches nothing."
  ([organization-id] (apply-rules! organization-id nil))
  ([organization-id actor]
   (let [rules (rules organization-id)
         current (assignments organization-id)
         candidates (remove #(= :manual (:by (get current (:id %)))) (messages))
         decided (keep (fn [message]
                         (when-let [rule (first-match rules message)]
                           [(:id message)
                            {:project-id (:rule/project rule)
                             :by :rule
                             :rule-id (:rule/id rule)
                             :at (store/now)}]))
                       candidates)
         changed (remove (fn [[id assignment]]
                           (= (:project-id assignment)
                              (:project-id (get current id))))
                         decided)]
     (locking write-lock
       (when (seq decided)
         (store/transact! update-in (assignments-path organization-id)
                          (fn [existing] (into (or existing {}) decided)))))
     {:schema schema :ok? true
      ;; Only what CHANGED is written into a repository. Re-running the rules
      ;; over mail that is already filed should produce no commit at all, or
      ;; every sync would add an empty-but-noisy revision to every project.
      :artifacts (materialize! organization-id actor changed)
      :rules (count rules)
      :considered (count candidates)
      :assigned (count decided)
      :changed (count changed)
      :unmatched (- (count candidates) (count decided))
      :manual (count (filter #(= :manual (:by %)) (vals current)))})))

;; ---------------------------------------------------------------------------
;; reading

(defn- summarize [message assignment]
  {:id (:id message)
   :subject (:subject message)
   :from (:from message)
   :from-email (:from-email message)
   :received-at (:received-at message)
   :labels (vec (sort (map name (:labels message))))
   :project-id (:project-id assignment)
   :assigned-by (some-> (:by assignment) name)
   :rule-id (:rule-id assignment)})

(defn overview
  "The rules, and how much mail each project is holding.

  `unassigned` is reported rather than left to be inferred from a subtraction:
  the pile the rules do not catch is the thing worth looking at."
  [organization-id]
  (let [current (assignments organization-id)
        all (messages)]
    {:schema schema :ok? true
     :rules (rules organization-id)
     :messages (count all)
     :assigned (count current)
     :unassigned (- (count all) (count current))
     :projects (->> current
                    (group-by (comp :project-id val))
                    (map (fn [[project-id entries]]
                           {:project-id project-id
                            :count (count entries)
                            :manual (count (filter #(= :manual (:by (val %)))
                                                   entries))}))
                    (sort-by :project-id)
                    vec)}))

(defn project-mail
  "Every message filed against one project, newest first."
  [organization-id project-id]
  (let [current (assignments organization-id)]
    {:schema schema :ok? true
     :project-id project-id
     :items (->> current
                 (keep (fn [[message-id assignment]]
                         (when (= project-id (:project-id assignment))
                           (when-let [message (message-by-id message-id)]
                             (summarize message assignment)))))
                 (sort-by :received-at)
                 reverse
                 vec)}))

(defn unassigned
  "Mail no rule has caught. The senders are grouped, because the useful next
  action is almost always one more rule for a domain that keeps appearing."
  [organization-id]
  (let [current (assignments organization-id)
        loose (remove #(contains? current (:id %)) (messages))]
    {:schema schema :ok? true
     :count (count loose)
     :senders (->> loose
                   (group-by #(or (domain-of (:from-email %)) "(不明)"))
                   (map (fn [[domain messages]]
                          {:from-domain domain :count (count messages)}))
                   (sort-by :count >)
                   (take 20)
                   vec)
     :items (->> loose
                 (sort-by :received-at)
                 reverse
                 (take 50)
                 (mapv #(summarize % nil)))}))
