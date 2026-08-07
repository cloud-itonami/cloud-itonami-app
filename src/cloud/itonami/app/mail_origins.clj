(ns cloud.itonami.app.mail-origins
  "What is known about the domains that send this deployment mail.

  ## Why a registry rather than more rules

  Filing rules were written by hand, one domain at a time, by reading the
  unassigned pile and guessing what each sender was. That works and does not
  scale, and it produces no artefact anybody else can use: the knowledge that
  `notify.cloudflare.com` is infrastructure lives in one organization's rule
  list and nowhere else.

  A registry separates the two things that were tangled in a rule:

  - **what a domain is** — a fact about the world, the same for everyone, and
    worth publishing;
  - **where this organization files it** — a decision, and theirs.

  The first is `:origin/*` and `:route/*` and is published. The second stays in
  the organization's own rules, which still win.

  ## Three fields that are deliberately not the same thing

  `:origin/kind` says what the sender is (a bank, a registrar, a marketplace).
  `:route/projects` says where mail from it goes. `:trust/level` says what this
  app may do with it. They are separate because they disagree: a bank is
  high-consequence and routine, a newsletter is low-consequence and untrusted,
  and collapsing them loses one of the two answers.

  ## Trust is deny-by-default and is not inferred from volume

  Receiving four hundred messages from a domain says nothing about whether it is
  who it claims to be. `:trust/level` therefore starts at `:unverified` and only
  moves on evidence recorded in `:trust/evidence`.

  One signal IS computable and is used: mail that arrived through an Apple
  private relay address reached a relay **this owner created for that party**.
  That is consent, recorded rather than assumed — `:self-registered`. It is not
  `:trusted`; it says the relationship was initiated deliberately, which is a
  different and weaker claim.

  This mirrors the messenger's rule (ADR-0016) rather than inventing a second
  trust model: unknown senders fail closed, and a quarantined body never becomes
  model context."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.mail-authentication :as authentication]))

(def schema "cloud-itonami.mail-origins.v1")

(def resource-name "cloud-itonami-app.origins.edn")

(def trust-levels
  "Ordered from least to most permitted. Deny-by-default means `:unverified`.

  `:abusive` and `:suspected` are separate because the difference is who
  decided: `:suspected` is an observation this deployment made, `:abusive` is a
  verdict backed by evidence somebody can check."
  [:abusive :suspected :unverified :self-registered :trusted])

(def origin-kinds
  "What a sender is. Not what to do about it — see `:trust/level` for that."
  #{:bank :card :payment :tax :legal :government :utility :infrastructure
    :developer-tool :marketplace :retail :travel :education :media :newsletter
    :social :person :employer :insurance :logistics :unknown})

(defn- load-registry []
  (when-let [resource (io/resource resource-name)]
    (edn/read-string (slurp resource))))

(defonce ^:private registry (delay (or (load-registry) {:origins []})))

(defn all [] (:origins @registry))

(defn- registrable
  "The domain a rule would sensibly name: `notify.cloudflare.com` is
  `cloudflare.com`, and `example.co.jp` keeps three labels."
  [domain]
  (let [parts (str/split (str domain) #"\.")
        n (count parts)]
    (cond
      (< n 3) (str domain)
      ;; A two-label public suffix (co.jp, com.au …) needs one more label.
      (and (>= n 3) (contains? #{"co" "or" "ne" "ac" "go" "com" "net" "org"}
                               (nth parts (- n 2))))
      (str/join "." (take-last 3 parts))
      :else (str/join "." (take-last 2 parts)))))

(defn lookup
  "The registry entry for a domain, matching the most specific first.

  A rule written for `cloudflare.com` should cover `notify.cloudflare.com`, so
  a miss falls back to the registrable domain before giving up."
  [domain]
  (let [domain (str/lower-case (str/trim (str domain)))]
    (or (some #(when (= domain (:origin/domain %)) %) (all))
        (some #(when (= (registrable domain) (:origin/domain %)) %) (all)))))

(defn routes-for
  "The projects this domain is pre-labelled for, or nil.

  Nil rather than an empty vector, so a caller can tell \"the registry has
  nothing to say\" from \"the registry says file this nowhere\"."
  [domain]
  (not-empty (vec (:route/projects (lookup domain)))))

(defn trust-of
  "This domain's trust level. `:unverified` when the registry has never seen it,
  which is the same answer as having seen it and learned nothing."
  [domain]
  (or (:trust/level (lookup domain)) :unverified))

(defn permitted?
  "Whether `action` is allowed for mail from `domain`.

  The gate the trust level exists for. `:body-to-model` is the one that matters
  and is the one ADR-0016 already draws for the messenger: an untrusted sender's
  body must not become model context, because a message is not an instruction
  and an unknown party must not be able to write one."
  [domain action]
  (let [level (trust-of domain)]
    (case action
      :file true
      :render (not (contains? #{:abusive} level))
      :body-to-model (contains? #{:self-registered :trusted} level)
      :follow-links (contains? #{:trusted} level)
      false)))

;; ---------------------------------------------------------------------------
;; building the registry from what a deployment has actually received

(defn- authentication-summary
  "Per-domain counts of the receiver's verdict.

  `:unknown` is counted, not dropped. Most of any corpus predates header
  retention, and a summary that hid those would say a domain was fully checked
  when almost none of it was."
  [group]
  (let [verdicts (frequencies (map (comp :verdict authentication/verdict) group))]
    {:authenticated (get verdicts :authenticated 0)
     :unaligned (get verdicts :unaligned 0)
     :impersonation-suspected (get verdicts :impersonation-suspected 0)
     :unknown (get verdicts :unknown 0)}))

(defn observe
  "Fold messages into registry entries.

  Pure: takes messages and a `domain-of` function, returns entries. The caller
  owns where the messages come from and where the result is written, which is
  what lets the same function serve the generator and its test."
  [messages domain-of]
  (->> messages
       (group-by domain-of)
       (keep (fn [[domain group]]
               (when-not (str/blank? (str domain))
                 (let [received (sort (keep :received-at group))
                       names (->> group
                                  (map #(str/replace (str (:from %)) #"\"" ""))
                                  (remove str/blank?)
                                  distinct
                                  (take 4)
                                  vec)
                       ;; An Apple relay address is one this owner created for
                       ;; that party, so its presence is consent — recorded, not
                       ;; assumed, and weaker than trust.
                       relay? (boolean
                               (some #(re-find #"_at_" (str (:from-email %))) group))]
                   {:origin/domain domain
                    :origin/registrable (registrable domain)
                    :origin/display-names names
                    :origin/kind :unknown
                    :origin/via-relay? relay?
                    :observed/messages (count group)
                    :observed/first (first received)
                    :observed/last (last received)
                    ;; What the RECEIVING server decided about this domain's
                    ;; mail. An origin fact, not a trust decision: DMARC passing
                    ;; says the sender is who it claims, which is a different
                    ;; and much narrower statement than the sender being worth
                    ;; trusting. A verified spammer is verified.
                    ;;
                    ;; It does justify one thing, and it is the reason to record
                    ;; it here: a rule naming a domain that consistently
                    ;; authenticates cannot be fooled by somebody impersonating
                    ;; it.
                    :observed/authentication (authentication-summary group)
                    :route/projects []
                    :trust/level (if relay? :self-registered :unverified)
                    :trust/evidence (if relay?
                                      [{:evidence/kind :apple-private-relay
                                        :evidence/note
                                        "この所有者がこの相手のために作成したリレー宛先で受信"}]
                                      [])}))))
       (sort-by (juxt (comp - :observed/messages) :origin/domain))
       vec))

(defn merge-known
  "Overlay curated facts onto observed entries, by domain.

  Observation is regenerated from the corpus every time and would otherwise
  erase whatever a person had classified. Counts always come from the
  observation; `:origin/kind`, `:route/projects` and `:trust/level` always come
  from the curated side when it has them."
  [observed curated]
  (let [by-domain (into {} (map (juxt :origin/domain identity)) curated)]
    (mapv (fn [entry]
            (if-let [known (get by-domain (:origin/domain entry))]
              (merge entry
                     (select-keys known [:origin/kind :route/projects
                                         :trust/level :trust/evidence
                                         :origin/operator :origin/note]))
              entry))
          observed)))
