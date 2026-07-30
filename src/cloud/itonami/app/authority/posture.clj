(ns cloud.itonami.app.authority.posture
  "The cross-domain invariant, and the reason the three authorities were
  integrated rather than shipped as three features.

  > An eSIM ownership transfer -- a SIM swap -- lowers the same subject's card
  > authorization posture.

  This is the actual defence the integration buys. Whoever takes over a phone
  number has taken over the second factor for everything else, and the classic
  sequence is: move the line, then spend. Neither the eSIM authority nor the card
  authority can see that sequence on its own; only something reading BOTH can.

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

(def restricted-ops
  "The card operations a restricted posture refuses.

  `:authorization/decide` is the spend path -- the direct objective of a takeover.
  `:card/issue` is included because issuing a NEW card immediately after taking
  over the line is the same attack one step earlier: the attacker does not need
  the victim's existing card if they can have a fresh one."
  #{:authorization/decide :card/issue})

(defn- transfer-proposal?
  "True for an eSIM ownership-transfer proposal, in any state."
  [p]
  (and (= :esim (:authority p))
       (= :ownership/transfer (:op p))))

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
  "The transfer proposals that currently count as takeover signals for this set
  of proposals. Pure."
  [proposals now window-seconds]
  (->> proposals
       (filter transfer-proposal?)
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
     :authority/reason :esim/ownership-transfer
     :authority/signals [...]        ; the proposal ids that caused it
     :authority/window-seconds n}"
  ([proposals now] (for-subject proposals now default-window-seconds))
  ([proposals now window-seconds]
   (let [signals (transfer-signals proposals now window-seconds)]
     (if (seq signals)
       {:authority/posture :restricted
        :authority/reason :esim/ownership-transfer
        :authority/signals (mapv :id signals)
        :authority/window-seconds window-seconds}
       {:authority/posture :normal}))))

(defn restricted?
  [posture]
  (= :restricted (:authority/posture posture)))

(defn refuses?
  "True when this posture refuses this card op."
  [posture op]
  (and (restricted? posture) (contains? restricted-ops op)))

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
