(ns cloud.itonami.app.authority.posture
  "The cross-domain invariant, and the reason the three authorities were
  integrated rather than shipped as three features.

  > A change in who controls a subject's line -- an eSIM ownership transfer (a
  > SIM swap) or a number port-out -- lowers that same subject's card
  > authorization posture, their ability to place outbound calls, and their
  > ability to move the number onward.

  This is the actual defence the integration buys. Whoever takes over a phone
  number has taken over the second factor for everything else, and the classic
  sequence is: move the line, then spend. Neither the eSIM authority nor the card
  authority nor the numbering authority can see that sequence on its own; only
  something reading ALL of them can.

  The number-plane half arrived with ADR-2608034000, and it closes a hole the
  eSIM-only version had: an attacker who could not swap the profile could still
  port the number, and a port-out is the step after which the subject cannot get
  the line back by asking their own operator.

  That is why ADR-2607300300 D4 requires the three domains' audit records to
  share one plane keyed by the subject. In this app they share one store
  partition (`[:authority :proposals]`), which is the local analogue of the
  single kotobase ref: `kotobase.core/open` takes one `:ref-name` and Datalog
  reach is exactly one chain, so a domain split across refs makes this function
  unwritable. Splitting for write throughput would buy throughput and lose the
  only invariant that justified integrating.

  `for-subject` is PURE -- it takes the proposals and the current instant, so the
  rule is testable without a clock and without a store. `subject-posture` is the
  thin impure wrapper that reads the single partition and supplies `now`."
  (:require [cloud.itonami.app.store :as store])
  (:import [java.time Duration Instant]
           [java.time.format DateTimeParseException]))

(def default-window-seconds
  "How long a transfer keeps the posture restricted. Seven days: long enough to
  cover the days-scale tail of a real account takeover, short enough that a
  legitimate device change does not restrict a subject indefinitely."
  (* 7 24 60 60))

(def restricted-ops-by-authority
  "What a restricted posture refuses, per authority.

  `:card` -- `:authorization/decide` is the spend path, the direct objective of a
  takeover. `:card/issue` is included because issuing a NEW card immediately
  after taking over the line is the same attack one step earlier: the attacker
  does not need the victim's existing card if they can have a fresh one.

  `:voice` -- `:call/originate` is the same objective through a different meter.
  Toll fraud spends the subject's money by placing calls, and a takeover that
  cannot reach a card can still reach a premium-rate number.

  `:number` -- moving the line ONWARD is what makes a takeover permanent. Swap
  the profile, then port the number out is one attack in two steps, and the
  second step is the one after which the subject cannot get the line back by
  asking their own operator. `:number/assign` is here for the same reason
  `:card/issue` is: reassigning the line to a new subject is the takeover
  written down."
  {:card   #{:authorization/decide :card/issue}
   :voice  #{:call/originate}
   :number #{:number/port-out :number/assign}})

(def restricted-ops
  "The card operations a restricted posture refuses.

  Kept as its own name because the card adapter and the API layer both read it
  directly, and because card was the domain this invariant was built for
  (ADR-2607300300 D4). It is now one entry in `restricted-ops-by-authority`."
  (:card restricted-ops-by-authority))

(defn restricts?
  "True when this authority gates this op on the posture at all.

  Used by an adapter to decide whether a posture is a REQUIRED input. That is
  what stops the invariant being bypassable by simply not asking: a caller
  cannot decide a restricted op without having stated the posture."
  [authority-key op]
  (contains? (get restricted-ops-by-authority authority-key #{}) op))

(def control-change-ops
  "The proposals that say a line changed hands, by authority.

  The eSIM entry is where this invariant started: an ownership transfer is a SIM
  swap. The `:number` entries arrived with numbering (ADR-2608034000) and are
  read from `kotoba.phone.lifecycle/takeover-signals` rather than restated --
  `:number/port-out` is `:port-out-request`, `:number/assign` is a line being
  reassigned, and the library is where 'the line changed hands' is defined so
  that every consumer reads one definition.

  A port-IN is deliberately absent. An arriving number is a different worry --
  its provenance is unsettled, not the subject's control lost -- and treating it
  as a takeover would restrict the very subject who just consolidated their
  lines."
  {:esim   #{:ownership/transfer}
   :number #{:number/port-out :number/assign}})

(defn- control-change-proposal?
  "True for a proposal that moves a subject's line or profile to somebody else,
  in any state."
  [p]
  (contains? (get control-change-ops (:authority p) #{}) (:op p)))

(def ^:private not-a-signal
  "The one status that clears a transfer as a takeover signal: the human was
  ASKED and said no. That is the subject themselves stating they did not request
  it, which is the strongest evidence available that it was not them.

  Everything else counts, including `:authority-refused`. A transfer the governor
  refused is still an ATTEMPTED transfer, and the attempt is the signal -- waiting
  for a transfer to succeed before restricting hands the attacker exactly the
  window this function exists to close."
  #{:rejected})

(defn- instant-of
  "Parse a stored timestamp, or nil when it cannot be read."
  [s]
  (when (string? s)
    (try (Instant/parse s)
         (catch DateTimeParseException _ nil)
         (catch Exception _ nil))))

(defn- within-window?
  "True when `at` is inside `window-seconds` before `now`.

  An UNPARSEABLE or missing timestamp counts as INSIDE the window. That is the
  fail-closed direction: if we cannot tell when a transfer happened, treating it
  as old would silently drop the restriction, and a dropped restriction is the
  failure this whole namespace is about."
  [at now window-seconds]
  (let [at' (instant-of at)
        now' (instant-of now)]
    (cond
      (nil? at') true
      (nil? now') true
      :else (<= (.toSeconds (Duration/between at' now')) (long window-seconds)))))

(defn transfer-signals
  "The proposals that currently count as takeover signals for this set of
  proposals. Pure.

  Named for the eSIM transfer it was built for; it now covers every
  control-change op in `control-change-ops`, which is why the filter reads a
  table rather than one authority."
  [proposals now window-seconds]
  (->> proposals
       (filter control-change-proposal?)
       (remove #(contains? not-a-signal (:status %)))
       (filter #(within-window? (or (:created-at %) (:committed-at %))
                                now window-seconds))
       (sort-by :created-at)
       vec))

(defn for-subject
  "The posture implied by `proposals`. PURE.

  Returns
    {:authority/posture :normal}
  or
    {:authority/posture :restricted
     :authority/reason :esim/ownership-transfer | :number/port-out | :control/change
     :authority/signals [...]        ; the proposal ids that caused it
     :authority/reasons [...]        ; every distinct reason, when there are several
     :authority/window-seconds n}

  `:authority/reason` stays SINGULAR and keeps its original value when the only
  signals are eSIM transfers, because a reader (and a stored proposal's material)
  already depends on that shape. When signals come from more than one domain it
  reports `:control/change` and `:authority/reasons` carries the list -- a
  restriction caused by both a SIM swap and a port-out must not be describable as
  only one of them."
  ([proposals now] (for-subject proposals now default-window-seconds))
  ([proposals now window-seconds]
   (let [signals (transfer-signals proposals now window-seconds)
         reasons (distinct (map (fn [p]
                                  (if (= :esim (:authority p))
                                    :esim/ownership-transfer
                                    (:op p)))
                                signals))]
     (if (seq signals)
       (cond-> {:authority/posture :restricted
                :authority/reason (if (= 1 (count reasons)) (first reasons) :control/change)
                :authority/signals (mapv :id signals)
                :authority/window-seconds window-seconds}
         (< 1 (count reasons)) (assoc :authority/reasons (vec reasons)))
       {:authority/posture :normal}))))

(defn restricted?
  [posture]
  (= :restricted (:authority/posture posture)))

(defn refuses?
  "True when this posture refuses this op.

  Two arities. The 2-arity one asks about a CARD op and is what the card adapter
  and the existing callers use. The 3-arity one names the authority, and is what
  every other domain must use -- `:call/originate` and `:number/port-out` are
  restricted ops too, and a 2-arity call would have quietly answered false for
  them by looking them up in the card table."
  ([posture op] (refuses? posture :card op))
  ([posture authority-key op]
   (and (restricted? posture) (restricts? authority-key op))))

;; ---------------------------------------------------------------------------
;; The impure edge
;; ---------------------------------------------------------------------------

(defn subject-proposals
  "Every proposal recorded for this session's subject, ACROSS ALL AUTHORITIES,
  read from the one partition they share.

  This is the cross-domain read D4 exists to make possible. If eSIM proposals and
  card proposals lived in separate stores -- or separate kotobase refs -- this
  function would be N reads and a merge, and the invariant below would have to be
  maintained by whoever remembered to do the merge."
  [session]
  (->> (vals (get-in (store/snapshot) [:authority :proposals]))
       (filter #(= (:user-id session) (:user-id %)))
       vec))

(defn subject-posture
  "The current posture for this session's subject."
  ([session] (subject-posture session {}))
  ([session configuration]
   (let [window (or (get-in configuration [:authorities :card :sim-swap-window-seconds])
                    default-window-seconds)]
     (for-subject (subject-proposals session) (store/now) window))))
