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
            [cloud.itonami.app.mail-origins :as origins]
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

(defn- migrate-assignment
  "One message's filings, from either shape.

  The first version of this stored `message-id -> assignment`: one project per
  message. That was wrong about the subject matter, not merely limiting — an
  invoice from a law firm belongs in `billing` AND `legal`, and filing it once
  means whoever opens the other project does not have it. The shape is now
  `message-id -> {project-id assignment}`.

  Read through rather than migrated in a batch: 988 messages had been filed
  before the shape changed, and a one-shot rewrite of somebody's filing is a
  worse risk than a branch that will be dead once they are all rewritten by
  ordinary use."
  [value]
  (cond
    (nil? value) {}
    ;; New shape: a map of project-id -> assignment.
    (and (map? value) (every? map? (vals value))) value
    ;; Old shape: a single assignment map.
    (and (map? value) (:project-id value)) {(:project-id value) value}
    :else {}))

(defn filings
  "message id -> {project-id assignment}, for this organization.

  A message may be filed against several projects, so the value is a map rather
  than one assignment."
  [organization-id]
  (into {}
        (map (fn [[message-id value]] [message-id (migrate-assignment value)]))
        (get-in (store/snapshot) (assignments-path organization-id) {})))

(defn projects-of
  "Every project this message is filed against."
  [organization-id message-id]
  (vec (sort (keys (get (filings organization-id) message-id)))))

(defn assignments
  "message id -> ONE assignment, for callers that want a single answer.

  Kept because the reports read this way and because most mail is filed once.
  When a message has several filings it answers with the manual one if there is
  one, and otherwise the first by project id — a stable choice rather than a
  meaningful one, which is why anything that cares reads `filings`."
  [organization-id]
  (into {}
        (keep (fn [[message-id by-project]]
                (when-let [chosen (or (first (filter #(= :manual (:by %))
                                                     (vals by-project)))
                                      (first (vals (into (sorted-map) by-project))))]
                  [message-id chosen])))
        (filings organization-id)))

;; ---------------------------------------------------------------------------
;; matching

(def ^:private relay-hosts
  #{"icloud.com" "privaterelay.appleid.com"})

(def ^:private top-level-domains
  "Enough of the TLD space to find where a decoded domain ends.

  Not a registry: the only question asked of it is \"does this underscore
  segment end the hostname\", and the alternative — cutting at a fixed number of
  segments — gets `mk.ooedoonsen.jp` and `notify.cloudflare.com` wrong in
  opposite directions."
  #{"com" "net" "org" "jp" "io" "co" "dev" "ai" "app" "cloud" "me" "info" "biz"
    "us" "uk" "eu" "de" "fr" "cn" "kr" "tw" "hk" "sg" "au" "ca" "tech" "shop"
    "store" "site" "online" "xyz" "email" "news" "team" "live" "tv" "fm" "gg"})

(defn relay-origin
  "The real sender behind an Apple private relay address, or nil.

  Apple rewrites `noreply@notify.cloudflare.com` to
  `noreply_at_notify_cloudflare_com_<random>@icloud.com`, so the original
  hostname is right there in the local part with its dots turned into
  underscores and a random tail appended. Reading it back matters more than it
  sounds: measured on this inbox, **37% of messages arrive through the relay**,
  and to a rule matching on domain they all look like one sender called
  `icloud.com`.

  The tail is cut at the last hostname-looking segment rather than a fixed
  depth, because `mk_ooedoonsen_jp_wpg…` and `notify_cloudflare_com_2kwm…` end
  at different positions and a fixed depth is wrong for one of them. A `co_jp`
  pair is why the scan continues past the first match instead of stopping at
  it."
  [address]
  (let [address (str/lower-case (str/trim (str address)))
        [local host] (str/split address #"@" 2)]
    (when (and host (contains? relay-hosts host) local)
      (when-let [encoded (second (str/split local #"_at_" 2))]
        (let [segments (vec (str/split encoded #"_"))
              tld? #(contains? top-level-domains %)
              ;; The hostname ends at the FIRST segment that is a TLD and is not
              ;; followed by another one. First, not last, because the random
              ;; tail can itself contain a token that looks like a TLD; and "not
              ;; followed by another" is what keeps `co_jp` together.
              end (some (fn [i]
                          (when (and (tld? (nth segments i))
                                     (or (= i (dec (count segments)))
                                         (not (tld? (nth segments (inc i))))))
                            (inc i)))
                        (range (count segments)))]
          (when (and end (> end 1))
            (str/join "." (take end segments))))))))

(defn- domain-of
  "The sending hostname, seen through Apple's relay when it is one."
  [address]
  (or (relay-origin address)
      (some-> address str str/lower-case (str/split #"@") second str/trim
              not-empty)))

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

(defn matching-rules
  "Every rule that matches, one per project.

  All of them, because a message can belong to more than one project and
  stopping at the first match made the rule ORDER encode a priority nobody had
  decided on — a law firm's invoice went to whichever of `billing` and `legal`
  happened to be written first.

  Deduplicated by project so two rules aimed at the same project file once; the
  earlier rule wins, which keeps `:rule-id` pointing at the one an operator
  would find by reading the list top to bottom."
  [rules message]
  (->> rules
       (filter #(matches? % message))
       (reduce (fn [acc rule]
                 (if (contains? (set (map :rule/project acc)) (:rule/project rule))
                   acc
                   (conj acc rule)))
               [])))

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
        (store/transact! update-in
                         (conj (assignments-path organization-id) message-id)
                         (fn [current]
                           (assoc (migrate-assignment current)
                                  project-id assignment))))
      {:schema schema :ok? true :message message-id :assignment assignment
       :projects (projects-of organization-id message-id)
       :artifacts (materialize! organization-id actor
                                [[message-id assignment]])})))

(defn unassign!
  "Take a message out of one project, or out of all of them.

  A message may be filed several times, so removing it needs to say from where.
  Without `project-id` it is removed from all — which is what the old
  single-filing behaviour meant, and what somebody typing `unassign` with no
  project almost certainly wants."
  ([organization-id message-id] (unassign! organization-id message-id nil))
  ([organization-id message-id project-id]
   (let [project-id (not-empty (str/trim (str project-id)))]
     (locking write-lock
       (store/transact!
        update-in (assignments-path organization-id)
        (fn [current]
          (let [current (or current {})
                remaining (when project-id
                            (dissoc (migrate-assignment (get current message-id))
                                    project-id))]
            (if (seq remaining)
              (assoc current message-id remaining)
              (dissoc current message-id))))))
     {:schema schema :ok? true :message message-id
      :removed-from (or project-id :all)
      :projects (projects-of organization-id message-id)})))

(defn thread-messages
  "Every message in one conversation.

  Gmail gives a thread id and this app has carried it since the first sync
  without ever grouping by it. A conversation is the unit a person files —
  nobody decides that the third reply belongs to `legal` and the fourth does
  not."
  [thread-id]
  (let [thread-id (str thread-id)]
    (->> (messages)
         (filter #(= thread-id (str (:thread-id %))))
         (sort-by :received-at)
         vec)))

(defn assign-thread!
  "File a whole conversation, not one message of it.

  Returns per message, because a thread whose later replies arrived after the
  rules last ran is a normal state and the count is how somebody notices."
  [organization-id thread-id project-id actor]
  (let [in-thread (thread-messages thread-id)]
    (when (empty? in-thread)
      (throw (ex-info (str "そのスレッドはありません: " thread-id)
                      {:type :mail/not-found :thread thread-id})))
    {:schema schema :ok? true :thread thread-id :project-id project-id
     :messages (count in-thread)
     :results (mapv #(assign! organization-id (:id %) project-id actor)
                    in-thread)}))

(defn apply-rules!
  "Run the rules over every message this organization has not filed by hand.

  Returns what changed and what did not, because 'applied' with no numbers is
  indistinguishable from a rule set that matches nothing."
  ([organization-id] (apply-rules! organization-id nil))
  ([organization-id actor]
   (let [rules (rules organization-id)
         current (filings organization-id)
         manual? (fn [message-id project-id]
                   (= :manual (:by (get-in current [message-id project-id]))))
         ;; Every message is a candidate. Exclusion is per PROJECT, in the
         ;; `manual?` check below — excluding the whole message is what made
         ;; filing something by hand into one project stop the rules from ever
         ;; filing it into another.
         candidates (messages)
         ;; EVERY matching rule files, not only the first. A law firm's invoice
         ;; belongs in billing and in legal, and stopping at the first match is
         ;; what made that impossible to express — the rule order then had to
         ;; encode a priority nobody had decided on.
         ;; The registry's pre-labels, then this organization's rules. Both
         ;; file; neither excludes the other. The registry says what a domain
         ;; IS, which is the same for everyone and worth publishing; the rules
         ;; say where THIS organization files it, and a rule naming a project
         ;; the registry did not is not a conflict — a message simply belongs to
         ;; both.
         from-registry (fn [message]
                         (map (fn [project-id]
                                {:project-id project-id :by :origin
                                 :origin (domain-of (:from-email message))
                                 :at (store/now)})
                              (or (origins/routes-for (domain-of (:from-email message)))
                                  [])))
         from-rules (fn [message]
                      (map (fn [rule]
                             {:project-id (:rule/project rule) :by :rule
                              :rule-id (:rule/id rule) :at (store/now)})
                           (matching-rules rules message)))
         decided (mapcat (fn [message]
                           (->> (concat (from-rules message) (from-registry message))
                                ;; A rule and the registry naming one project is
                                ;; one filing, and the rule wins the attribution
                                ;; because somebody in this organization wrote it.
                                (reduce (fn [acc a]
                                          (if (contains? acc (:project-id a))
                                            acc
                                            (assoc acc (:project-id a) a)))
                                        {})
                                vals
                                (keep (fn [assignment]
                                        (when-not (manual? (:id message)
                                                           (:project-id assignment))
                                          [(:id message) (:project-id assignment)
                                           assignment])))))
                         candidates)
         changed (remove (fn [[message-id project-id _]]
                           (get-in current [message-id project-id]))
                         decided)]
     (locking write-lock
       (when (seq decided)
         (store/transact!
          update-in (assignments-path organization-id)
          (fn [existing]
            (reduce (fn [acc [message-id project-id assignment]]
                      (update acc message-id
                              #(assoc (migrate-assignment %) project-id assignment)))
                    (or existing {})
                    decided)))))
     {:schema schema :ok? true
      ;; Only what CHANGED is written into a repository. Re-running the rules
      ;; over mail that is already filed should produce no commit at all, or
      ;; every sync would add an empty-but-noisy revision to every project.
      :artifacts (materialize! organization-id actor
                               (map (fn [[message-id _ assignment]]
                                      [message-id assignment])
                                    changed))
      :rules (count rules)
      :considered (count candidates)
      ;; Filings, not messages: a message filed into two projects is two, and
      ;; reporting it as one would make the numbers disagree with the projects.
      :filings (count decided)
      ;; `:assigned` has always meant "messages filed" and consumers read it;
      ;; `:filings` is the new number, and one message in two projects is two
      ;; filings and one assignment.
      :assigned (count (distinct (map first decided)))
      :messages-filed (count (distinct (map first decided)))
      :changed (count changed)
      :unmatched (- (count candidates) (count (distinct (map first decided))))
      :manual (count (filter #(= :manual (:by %))
                             (mapcat vals (vals current))))})))

;; ---------------------------------------------------------------------------
;; reading

(defn organizations-with-rules
  "Every organization that has said how its mail should be filed.

  The rule set is what says an organization wants filing, so it is also what
  says whose rules to run after a sync. An organization with no rules is not a
  gap to fill — it is a deployment that has not asked for this, and running over
  it would be work with no possible outcome."
  []
  (vec (keys (get-in (store/snapshot) [:mail :project-rules] {}))))

(defn apply-all!
  "Run every organization's rules. Called after a sync.

  Returns one entry per organization rather than a total: two organizations
  whose rules behave differently is exactly the case a single number hides."
  ([] (apply-all! nil))
  ([actor]
   (let [organizations (organizations-with-rules)]
     {:schema schema :ok? true
      :organizations (count organizations)
      :results (mapv (fn [organization-id]
                       (assoc (apply-rules! organization-id actor)
                              :organization-id organization-id))
                     organizations)})))

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
  (let [current (filings organization-id)
        filed (into {} (remove (comp empty? val)) current)
        all (messages)]
    {:schema schema :ok? true
     :rules (rules organization-id)
     :messages (count all)
     :assigned (count filed)
     ;; Filings exceed assignments once a message is in two projects, and the
     ;; per-project counts below sum to this rather than to `:assigned`.
     :filings (reduce + 0 (map count (vals filed)))
     :unassigned (- (count all) (count filed))
     :projects (->> (mapcat vals (vals filed))
                    (group-by :project-id)
                    (map (fn [[project-id entries]]
                           {:project-id project-id
                            :count (count entries)
                            :manual (count (filter #(= :manual (:by %)) entries))}))
                    (sort-by :project-id)
                    vec)}))

(defn project-mail
  "Every message filed against one project, newest first.

  Reads `filings` rather than the single-answer view: a message filed into two
  projects appeared in only one of them while this asked `assignments`, which is
  the bug that made multi-project filing look like it had not worked."
  [organization-id project-id]
  (let [current (filings organization-id)]
    {:schema schema :ok? true
     :project-id project-id
     :items (->> current
                 (keep (fn [[message-id by-project]]
                         (when-let [assignment (get by-project project-id)]
                           (when-let [message (message-by-id message-id)]
                             (summarize message assignment)))))
                 (sort-by :received-at)
                 reverse
                 vec)}))

(defn unassigned
  "Mail no rule has caught. The senders are grouped, because the useful next
  action is almost always one more rule for a domain that keeps appearing."
  [organization-id]
  (let [current (filings organization-id)
        loose (remove #(seq (get current (:id %))) (messages))]
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
