(ns cloud.itonami.app.vf-authority
  "Valueflows economic authority for bots (ADR-2609111230 slice 2).

  A bot's economic authority is a biscuit scope over the shared VF event
  journal, of the form `kotoba://vf/<org>/<plane>/<action>`, decided by the
  ONE decider this fleet has: `authority.scope/covers?` via
  `authority.grant`. This namespace adds no comparison logic and no second
  decider — ADR-2608200400's cost record names what a per-scheme `covers?`
  bought: `kotoba://graph/alice*` covering `kotoba://graph/alice-evil`.

  What it adds is the MECHANICAL, TOTAL mapping from loop-yakuwari
  capability keywords to VF scopes, in the same spirit `tool->capability`
  is mechanical: a dropped capability is a bot quietly holding less than
  its role says, so `vf-capabilities` names every keyword that maps and
  `capability->vf-scope` refuses (returns nil) rather than guessing on one
  outside it. A guessed scope is invented authority.

  Planes and actions (ADR-2609111230):
    event       read append          — the shared journal (slice 1)
    commitment  commit               — requires an vf:Agreement upstream
    agreement   read                 — the commitments' terms
    resource    read                 — the folded inventory projection

  Grant order (the ADR's, enforced by the wording of the scope
  constructors): read first for every workforce bot, append only for bots
  with a measured production surface, commitment/commit for
  codinator-class coordinators only, and a coordinator hands a worker a
  slice by `attenuate` — never by re-issuing."
  (:require [authority.scope :as scope]))

(def ^:private planes
  {:event #{:read :append}
   :commitment #{:read :commit}
   :agreement #{:read}
   :resource #{:read}})

(defn vf-scope
  "The wire form of one VF authority: `kotoba://vf/<org>/<plane>/<action>`.

  Refuses a plane/action pair the ADR does not define rather than minting a
  scope nothing downstream knows how to read."
  [org plane action]
  (let [p (keyword plane), a (keyword action)]
    (when-let [acts (planes p)]
      (when (contains? acts a)
        (str "kotoba://vf/" (name org) "/" (name p) "/" (name a))))))

(defn vf-capabilities
  "The loop-yakuwari capability keywords that map to VF scopes, with the
  scope each one names for ORG. Mechanical and total over THIS set — and
  deliberately closed, because the mapping that silently drops a capability
  is the one the ADR forbids, and the mapping that invents one is worse.

  :vf.read   → observe the economy: journal, commitments, agreements,
               resources. What every workforce bot gets first (grant order
               step 1); observation needs no grant beyond presence, but the
               scope is still issued, so a verifier can see it.
  :vf.append → write EconomicEvents to the org's journal. Measured
               production surface only (step 2).
  :vf.commit → write a commitment on another's behalf. Codinator-class
               coordinators only (step 3); the journal itself refuses a
               commitment with no upstream vf:Agreement."
  [org]
  {:vf.read   (vf-scope org :event :read)
   :vf.append (vf-scope org :event :append)
   :vf.commit (vf-scope org :commitment :commit)})

(defn capability->vf-scope
  "A capability keyword → its VF scope string for ORG, or nil when the
  capability is not one of the VF ones. nil is the honest answer: the
  capability stays a tool scope under `bot_authority/capability->scope`,
  and this plane has nothing to say about it."
  [capability org]
  (get (vf-capabilities org) (keyword capability)))

(defn read-scope
  "Step 1 of the grant order: the event-read scope every workforce bot gets."
  [org] (vf-scope org :event :read))

(defn append-scope
  "Step 2: the event-append scope, for bots with a measured production
  surface. A sibling of read under the event plane, not implied by it —
  `covers?` orders them as separate leaves."
  [org] (vf-scope org :event :append))

(defn commit-scope
  "Step 3: the commitment-commit scope, codinator-class only."
  [org] (vf-scope org :commitment :commit))

(defn attenuate-slices
  "Step 4, the narrowing arithmetic a coordinator uses before handing a
  slice to a worker: which of COORDINATOR's VF scopes the named
  org/plane/action actually fits inside. `authority.scope/meet` returns nil
  for incomparable scopes, which is how 「the coordinator held append but
  asks for commit」 fails — the meet of two different leaves does not
  exist, and nothing is synthesised."
  [coordinator-scopes org plane action]
  (let [requested (scope/parse (vf-scope org plane action))]
    (when requested
      (->> coordinator-scopes
           (map scope/parse)
           (keep #(scope/meet % requested))
           (into #{})))))

(defn authorized?
  "Does this GRANT (already folded by `biscuit.authority/->grant`) authorise
  CAPABILITY over ORG?

  One decider underneath: the grant's scope antichain covers the scope the
  capability expands to, via `authority.scope/covered?` — the same
  `covers?` order. A VF capability expands to exactly one scope. The holder
  and clock conjuncts stay with `grant/authorized?` and are not
  re-implemented here."
  [g capability org]
  (boolean
   (when-some [s (capability->vf-scope capability org)]
     (scope/covered? (:grant/scopes g) (scope/parse s)))))
