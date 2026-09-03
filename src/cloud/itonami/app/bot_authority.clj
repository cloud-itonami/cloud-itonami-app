(ns cloud.itonami.app.bot-authority
  "A Bot's authority, carried as a biscuit and decided by `authority`.

  ## Two wires, one decider

  ADR-2608180200 fixes both halves and they must be read separately.
  Biscuit is the DELEGATION FORMAT: a token whose blocks are signed in a key
  chain, which anyone holding only the root PUBLIC key can verify, and which
  anyone at all can attenuate without contacting the issuer. `authority` is
  the DECIDER: `covers?` and `meet`, one implementation, unchanged.

  Nothing here answers *does this cover that*. `biscuit.authority/->grant`
  folds a verified token into an inert grant and `authority.grant/covers?`
  decides. That ADR records what a second decider cost the last time: a
  `covers?` written once per URI scheme, one copy comparing with
  `starts-with?`, so `kotoba://graph/alice*` covered
  `kotoba://graph/alice-evil`.

  ## Why the Bot's capabilities are worth carrying this way

  A workforce Bot's capabilities come from loop-yakuwari and are enforced
  today by intersecting them with the concrete tool grant inside this
  process. That works exactly as far as this process reaches. A token does
  not: a mailbox on another host, a settlement worker, an edge Worker can all
  verify it holding no secret, which is the property macaroons could not give
  without shipping the root secret everywhere it is checked.

  ## Attenuation by the Bot itself -- decided 2026-09-01

  This section used to say a Bot could not narrow its own token, because
  `biscuit.token/append` needs the private key the previous block named and no
  Bot held one. Owner decision: give Bots that key.

  It turned out to be less of a grant than the sentence implies. `bot-did` is
  the public half of `derive-seed(fleet, bot-id)`, so the private half was
  always derivable from the same seed file; what was missing was a caller able
  to reach it. `bot-identity/bot-signing-seed` is that caller, and its
  docstring carries what a holder of one can and cannot do.

  So `attenuate` below completes the half the fleet could not deliver: one
  issuance, then a Bot narrowing its own authority before handing a slice on,
  with the issuer never consulted. Narrowing is all it can do -- meet never
  widens -- and the reader half of that claim is measured in
  kotoba-lang/grant, `kotobase.biscuit-grant-attenuation-test`.

  The root key here signs authority for the whole workforce. It is a separate
  secret from `bot-identity.seed`: one names Bots, this one speaks for the
  fleet, and a compromise of either should not be a compromise of both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ed25519.core :as ed]
            [biscuit.token :as token]
            [biscuit.authority :as biscuit-authority]
            [authority.grant :as grant]
            [cloud.itonami.app.bot-identity :as bot-identity]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.secure-file :as secure-file])
  (:import [java.security SecureRandom]
           [java.nio.file Files]
))

(def seed-bytes 32)

(defn root-seed-file []
  (io/file (config/data-dir) "workforce-authority.seed"))

(defn- read-seed [file]
  (when (.isFile file)
    (let [bytes (Files/readAllBytes (.toPath file))]
      (when (= seed-bytes (alength bytes)) bytes))))

(defn root-seed
  "The fleet's authority root. Created on first use, 0600, never regenerated:
  rotating it invalidates every token already issued."
  []
  (let [file (root-seed-file)]
    (or (try (read-seed file) (catch Exception _ nil))
        (try
          (let [bytes (byte-array seed-bytes)]
            (.nextBytes (SecureRandom.) bytes)
            (io/make-parents file)
            (with-open [out (io/output-stream file)] (.write out bytes))
            (secure-file/harden! file "rw-------")
            bytes)
          (catch Exception _ nil)))))

(defn root-did
  "The public half, as a did:key. This is the ONLY thing a verifier needs."
  []
  (some-> (root-seed) ed/did-key-from-seed))

;; ── capability -> scope ──────────────────────────────────────────────────
;;
;; A capability is a namespaced keyword in loop-yakuwari (`:patch.create`).
;; `authority` addresses resources as `kotoba://…` URIs. The mapping is
;; mechanical and total: no capability is dropped silently, because a dropped
;; capability is a Bot quietly holding less than its role says.

(defn capability->scope [workforce-key capability]
  (str "kotoba://cap/" (str/replace (str workforce-key) #"/" ":")
       "/" (name capability)))

(defn- capability-facts
  "Only capabilities the fleet decided are the Bot's to exercise become
  scopes. `:blocked` is not a narrower grant -- it is the absence of one --
  and `:approval-required` / `:voice-required` are decisions a human still
  makes, so carrying them as scope would be the token claiming what the
  policy withheld."
  [workforce-key capability-policy]
  (for [{:keys [capability decision]} capability-policy
        :when (= :autonomous (keyword (name (or decision :blocked))))]
    ['scope (capability->scope workforce-key capability)]))

(defn issue
  "A biscuit carrying this Bot's autonomous capabilities, held by its did.

  Returns nil when there is no root key or the Bot has no did -- an
  unsignable token must not be approximated by an unsigned one."
  [{:bot/keys [id workforce-key] :as bot} capability-policy]
  (when-let [seed (root-seed)]
    (when-let [holder (bot-identity/bot-did id)]
      (token/authority
       {:facts (into [['holder holder]] (capability-facts workforce-key capability-policy))
        :rules [] :checks []
        ;; The next key is the Bot's own did. Nothing can append after this
        ;; block without the matching private key, and no Bot holds one --
        ;; so today this names the only party who could ever attenuate it.
        :next-public-key holder
        :root-private-key seed
        :sign-fn (fn [s payload] (ed/sign s (.getBytes ^String payload "UTF-8")))}))))

(defn attenuate
  "Narrow a Bot's own token and name who may narrow it next.

  The Bot signs with the key its own did names, so the issuer is not
  consulted and never learns this happened. `scopes` are the facts the new
  block carries; because `biscuit.authority/->grant` MEETS each block onto the
  last, they can only take authority away. A block naming a scope the token
  never had grants nothing rather than adding it.

  `recipient-did` becomes the next key, which is the whole point of handing a
  slice on: only the holder of that did may append after this. Passing the
  Bot's own did back keeps the narrowing to itself.

  Returns nil rather than an unattenuated token when the Bot has no seed. A
  caller that cannot narrow must not silently pass on the wide one."
  [{:bot/keys [id]} t scopes recipient-did]
  (when (and t (seq scopes) (some-> recipient-did str not-empty))
    (when-let [seed (bot-identity/bot-signing-seed id)]
      (token/append
       t
       {:facts (vec (for [s scopes] ['scope s]))
        :rules [] :checks []
        :next-public-key recipient-did
        :private-key seed
        :sign-fn (fn [k payload] (ed/sign k (.getBytes ^String payload "UTF-8")))}))))

(defn verify
  "`{:ok? true :blocks n}` or a reason. Needs only the root did:key."
  [t]
  (if-let [root (root-did)]
    (token/verify t root
                  (fn [did payload sig]
                    (try (ed/verify-did did (.getBytes ^String payload "UTF-8") sig)
                         (catch Exception _ false))))
    {:ok? false :reason :no-root-key}))

(def fleet-scope
  "The widest authority the fleet ever issues over Bot capabilities.

  `biscuit.authority/->grant` MEETS each block onto this, and meet only ever
  narrows -- so the base has to be the top of the range, not the bottom.
  Passing an empty grant here produced a token that reached nothing, which
  reads as a safe failure and is not one: it is indistinguishable from a Bot
  with no capabilities, and the caller cannot tell a withheld grant from a
  broken fold."
  "kotoba://cap/*")

(defn- declares-scope? [t]
  (boolean (some (fn [b] (some (fn [[p _]] (= 'scope p)) (:block/facts b)))
                 (:biscuit/blocks t))))

(defn ->grant
  "A VERIFIED token as an inert `authority` grant. Refuses an unverified one:
  folding first and checking later is how a forged token becomes a decision.

  A token that declares NO scope at all reaches nothing, and that has to be
  said here. `biscuit.authority/->grant` folds blocks onto the base by MEET,
  so a token with no scope facts folds to the base unchanged -- and the base
  is the widest authority the fleet issues. A Bot whose every capability was
  `:blocked` would therefore have been granted everything, which the probe
  for this namespace caught before it shipped.

  Both directions of that fold are now pinned: an empty base makes every
  token reach nothing (the earlier bug), and an empty token reaching the base
  makes every restriction a promotion. Neither is safe, and they fail in
  opposite directions, so only asserting one of them would have looked fine."
  [t]
  (when (:ok? (verify t))
    (if (declares-scope? t)
      (biscuit-authority/->grant t {:scopes [fleet-scope]})
      (biscuit-authority/->grant t {:scopes []}))))

(defn authorized?
  "Does this token authorise `capability` for `workforce-key`, right now, in
  the hands of `holder`?

  Every argument `authority.grant/authorized?` insists on is passed through
  rather than defaulted. Its docstring names the clock and the holder as
  the two things a hurried caller drops first, and the first version of this
  namespace dropped both -- it called `covers?` with a scope STRING where a
  grant was expected, so the scopes it read were nil,
  nothing escalated, and it answered true for every capability of every
  business including ones the policy had BLOCKED. The library was right and
  the caller was wrong, which is the direction this arrangement is meant to
  make visible."
  [t workforce-key capability {:keys [now holder]}]
  (boolean
   (when-let [g (->grant t)]
     (grant/authorized? g (capability->scope workforce-key capability)
                        {:now now :holder holder}))))

;; ── making the capability policy decide ──────────────────────────────────
;;
;; Until now a Bot's capability policy reached exactly one place: its system
;; prompt, labelled "descriptive; concrete tools remain the execution
;; ceiling", ending with "Blocked capabilities stay blocked". Nothing checked
;; that. It was an invariant told to a model and hoped for -- the shape this
;; workspace keeps finding and the one ADR-2608200200 cost the most.
;;
;; This makes it decide, for the tools where the mapping is not a judgement
;; call. It is deliberately SMALL. A tool whose capability is arguable is
;; left out, because inventing the mapping would be inventing authority, and
;; a wrong entry here either takes reach the fleet granted or grants reach it
;; withheld.

(def tool->capability
  "Tools whose capability is unambiguous. NOT a complete map, and read
  `covered-tools` before assuming it is.

  `workspace_write_file` writes a file into the business repository, which is
  what `:patch.create` names. `git_commit` records that change; the fleet
  vocabulary separates creating a patch from integrating one, and a local
  commit that never pushes is the first, not the second.

  Everything else a workforce Bot holds -- workspace_read, workspace_list,
  workspace_search, git_status, git_log, git_diff -- is reading, and the
  vocabulary has no capability that means `may read the repository it was
  given`. Mapping them to `:metrics.read` would be a guess, and a guess that
  can remove a Bot's ability to look at the repository it was pointed at."
  {"workspace_write_file" :patch.create
   "git_commit" :patch.create
   "disk_space_status" :disk.inspect
   "disk_space_cleanup" :disk.cleanup
   "disk_space_inventory" :disk.candidate.inspect
   "disk_space_reclaim" :disk.reclaimable.cleanup
   "git_hygiene_status" :git.inspect
   "git_hygiene_prune" :git.cleanup
   ;; Hokusai video (ADR-0092). Both name the same capability: reading a job
   ;; you submitted is part of submitting it, and a Bot without `:media.video`
   ;; has no job id to read.
   "video_generate" :media.video
   "video_status" :media.video
   "domain_search" :domain.read
   "domain_check" :domain.read
   "domain_registrations" :domain.read
   "domain_registration_status" :domain.read
   "domain_dns_records" :domain.read
   "domain_proposals" :domain.read
   "domain_registration_review" :domain.proposal.create
   "domain_auto_renew_review" :domain.proposal.create
   "domain_dns_change_review" :domain.proposal.create
   "domain_reject" :domain.proposal.create
   "domain_commit" :domain.approved-proposal.commit})

(defn covered-tools
  "The tools this gate actually decides. Everything else is unchanged by it."
  []
  (set (keys tool->capability)))

(defn admit
  "`runnable` narrowed to what the Bot's own token authorises.

  Only ever narrows, and only for tools in `tool->capability`. Three things
  leave it untouched, and each is a deliberate choice rather than an
  oversight:

    no workforce key   an interactive Bot has no fleet-issued policy to check
                       against; its ceiling is its tool grant, as before
    no token           an unissuable token must not silently become a denial
                       of everything -- that is indistinguishable from a Bot
                       with no capabilities, and it is how a key problem
                       becomes a fleet outage
    unmapped tool      see `tool->capability`

  The middle case is the uncomfortable one and it is stated rather than
  hidden: if the root key is unreadable this gate stops gating. It does not
  stop the EXISTING ceiling, which is the tool grant, so the failure is
  'no second floor' rather than 'no floor'."
  [runnable {:bot/keys [workforce-key] :as bot} capability-policy {:keys [now]}]
  (let [gated (filter (covered-tools) runnable)]
    (if (or (str/blank? (str workforce-key)) (empty? gated))
      runnable
      (if-let [t (issue bot capability-policy)]
        (let [holder (bot-identity/bot-did (:bot/id bot))
              permitted? (fn [tool]
                           (authorized? t workforce-key (get tool->capability tool)
                                        {:now now :holder holder}))]
          (into (set (remove (covered-tools) runnable))
                (filter permitted?)
                gated))
        runnable))))
