(ns cloud.itonami.app.authority.api
  "The request layer between HTTP routes and the authority spine.

  It exists so the routes in `cloud.itonami.app.server` stay three lines each and
  so everything below them is testable without an HTTP exchange.

  It enforces two things the adapters cannot enforce for themselves:

  1. AN AUTHORITY MUST BE ENABLED. Every stage refuses when the authority is off,
     including the read. The default is off (see defaults.edn `:authorities`), so
     a fresh install has no outward surface at all.

  2. SECURITY-BEARING FACTS ARE COMPUTED HERE, NEVER ACCEPTED FROM THE CALLER.
     The card adapter requires a `:posture` for spend and issuance, and if a
     client could supply it, the cross-domain SIM-swap invariant would be
     advisory -- an attacker would simply send `{:authority/posture :normal}`. The
     payment adapter is the same shape and higher stakes: its balance, its
     balance freshness and its settlement history all decide whether a human is
     asked at all, so a client that could send `{:balance {:amount-minor 10^9}}`
     would buy itself past the funds gate.

     So this namespace computes every one of them from the store and OVERWRITES
     whatever arrived in the request. That overwrite is the invariants' actual
     enforcement point; the adapters' required-input checks only stop them being
     forgotten."
  (:require [clojure.string :as str]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.card :as card]
            [cloud.itonami.app.authority.esim :as esim]
            [cloud.itonami.app.authority.number :as number]
            [cloud.itonami.app.authority.payment :as payment]
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.authority.transport :as transport]
            [cloud.itonami.app.authority.voice :as voice]
            [cloud.itonami.app.funding :as funding]
            [cloud.itonami.app.numbers :as numbers]
            [cloud.itonami.app.store :as store]
            [kotoba.phone.origination :as origination]))

(def adapters
  "authority key -> the namespace's domain constructor."
  {:esim    esim/domain
   :card    card/domain
   :number  number/domain
   :payment payment/domain
   :voice   voice/domain})

(defn- domain-for
  "The spine domain for this authority, with its transport bound. Refuses an
  unknown key rather than defaulting -- a typo must not silently reach a
  different authority."
  [authority-key]
  (let [ctor (get adapters authority-key)]
    (when-not ctor
      (throw (ex-info (str "未知の authority です: " authority-key)
                      {:type :authority/unknown-authority})))
    (ctor (transport/commit-fn authority-key))))

(defn- require-enabled! [configuration authority-key]
  (when-not (transport/enabled? configuration authority-key)
    (throw (ex-info (str (name authority-key)
                         " authority は無効です（defaults.edn :authorities）")
                    {:type :authority/disabled :authority authority-key}))))

(defn- payment-facts
  "The five facts the payment pre-check stands on, read from the store.

  `:funding-account-id` is taken from the request because naming the account
  money comes out of is the caller's decision -- but the ACCOUNT is looked up, so
  an id belonging to another organization resolves to nil and refuses. Defaulting
  to 'the organization's only active account' was rejected: an implicit funding
  source is the wrong thing to be convenient about."
  [configuration session request]
  (let [account (some->> (:funding-account-id request)
                         (funding/account session))
        balance (when account
                  (funding/balance session (:id account)))
        proposals (vals (get-in (store/snapshot) [:authority :proposals]))
        settled (payment/settled-references proposals (:organization-id session))]
    (assoc request
           :posture (posture/subject-posture session configuration)
           :funding-account account
           :balance balance
           :balance-freshness (funding/freshness
                               balance (store/now)
                               (funding/max-age-seconds configuration))
           ;; Always a boolean, never nil: the adapter refuses a non-boolean, and
           ;; that refusal is meant to catch a caller who forgot to state it --
           ;; not this function, which has read the history and knows.
           :already-settled? (contains? settled
                                        (some-> (:reference request) str
                                                str/trim)))))

(defn- number-facts
  "The facts the numbering pre-check stands on, read from the store and from
  configuration.

  `:records` is THE one that must not come from the caller. It is what decides
  whether a port-out names the number's actual holder, and a client that could
  send its own records could send `{:phone/subject me}` and pre-check its way
  through the port-out scam this adapter exists to refuse.

  `:blocks` comes from configuration rather than the store for a different
  reason: which ranges an operator was assigned predates every proposal, and a
  block that arrived in a request would let a caller allocate themselves a
  number out of a range nobody gave this operator."
  [configuration session request]
  (assoc request
         :posture (posture/subject-posture session configuration)
         :records (numbers/records session)
         :blocks (numbers/blocks configuration)
         :now-ms (numbers/now-ms)
         :quarantine-days (numbers/quarantine-days configuration)
         :freeze-days (numbers/freeze-days configuration)
         ;; The subject of an allocation or an assignment is whoever this session
         ;; is. Taking it from the request would let one subject allocate a
         ;; number to another -- which is `:number/assign`, an op with its own
         ;; consent context, reached by writing a different field.
         :subject (:user-id session)))

(defn- today-prefix
  "The date part of a stored ISO-8601 instant."
  [s]
  (when (string? s) (subs (str s) 0 (min 10 (count (str s))))))

(defn- spent-today-minor
  "What this subject has already committed to spending on outbound calls today.

  Summed from THIS APP's own proposals, which is the only spend it can honestly
  account for: the actor's meter is the actor's. Every proposal that is not
  terminally refused or rejected counts, including one still awaiting the
  authority -- money the operator has not yet decided on is money that may still
  leave, and leaving it out of the total is how two calls each pass a limit that
  neither would pass second."
  [session]
  (let [today (today-prefix (store/now))]
    (->> (authority/proposals session :voice)
         (filter #(= :call/originate (:op %)))
         (remove #(contains? #{:rejected :authority-refused} (:status %)))
         (filter #(= today (today-prefix (:created-at %))))
         (keep #(get-in % [:value :estimate-minor]))
         (reduce + 0))))

(defn- rate-minor-per-minute
  "The configured rate for this destination's class, or nil.

  A rate card keyed by classification, not by number: this app is a consent
  surface and has no carrier relationship to price a single destination from.
  nil is NOT read as free -- `kotoba.phone.origination/cost-issues` refuses an
  unknown rate, which is the only safe reading when the thing being estimated is
  a bill."
  [configuration destination]
  (let [settings (get-in configuration [:authorities :voice])
        class (origination/classify destination (:home-country-code settings))]
    (get-in settings [:rate-card class])))

(defn- origination-facts
  "The facts the outbound pre-check stands on. Same discipline as
  `payment-facts`: the caller states what it WANTS (destination, calling number,
  expected minutes) and every fact that decides whether it is allowed is
  computed here."
  [configuration session request]
  (assoc request
         :posture (posture/subject-posture session configuration)
         :records (numbers/records session)
         :subject (:user-id session)
         :rate-minor-per-minute (rate-minor-per-minute configuration (:destination request))
         :daily-limit-minor (get-in configuration [:authorities :voice :daily-limit-minor])
         :spent-today-minor (spent-today-minor session)))

(defn- with-server-computed-facts
  "Replace any caller-supplied security-bearing facts with ones computed from the
  store.

  `assoc` rather than a merge that could lose to the request: a caller-supplied
  posture or balance is not merely ignored, it is overwritten, because the whole
  point is that the caller does not get a say in it."
  [configuration session authority-key request]
  (case authority-key
    :card (if (contains? posture/restricted-ops (:op request))
            (assoc request :posture (posture/subject-posture session configuration))
            request)
    ;; Every payment op is a spend op, so there is no restricted-op subset to
    ;; test against -- the facts are computed for all of them.
    :payment (payment-facts configuration session request)
    ;; Same for numbering: every op reads the subject's records, and the records
    ;; are the thing that must not be caller-supplied.
    :number (number-facts configuration session request)
    :voice (if (= :call/originate (:op request))
             (origination-facts configuration session request)
             request)
    request))

;; ---------------------------------------------------------------------------
;; stages
;; ---------------------------------------------------------------------------

(defn review!
  "Pre-check and record a proposal awaiting consent."
  [configuration session authority-key request]
  (require-enabled! configuration authority-key)
  (authority/review! (domain-for authority-key) configuration session
                     (with-server-computed-facts
                       configuration session authority-key request)))

(defn start-approval!
  [configuration session authority-key proposal-id rp-id origin]
  (require-enabled! configuration authority-key)
  (authority/start-approval! (domain-for authority-key) session
                             proposal-id rp-id origin))

(defn finish-approval!
  [configuration session authority-key proposal-id transaction-id credential]
  (require-enabled! configuration authority-key)
  (authority/finish-approval! (domain-for authority-key) configuration session
                              proposal-id transaction-id credential))

(defn reject!
  "Record that the human declined. Still gated on the authority being enabled --
  a disabled authority should have no proposals to decline, and answering as
  though it might is worse than refusing."
  [configuration session authority-key proposal-id]
  (require-enabled! configuration authority-key)
  (authority/reject! session proposal-id))

(defn refresh!
  "Ask the authority what became of a pending proposal and advance it if decided.

  Read-only against the authority: this hits its consent surface, which cannot
  decide. Learning an outcome and causing one are different authorities -- for the
  eSIM actor they are literally different listeners, so this app has no route to
  the decision even if it wanted one."
  [configuration session authority-key proposal-id]
  (require-enabled! configuration authority-key)
  (authority/refresh! (domain-for authority-key) configuration session proposal-id))

(defn resolve-pending!
  "Refresh every proposal this session is waiting on an authority for, and report what
  moved.

  This exists because `refresh!` is per-proposal and, until now, nothing called it. It
  was reachable only as an HTTP route needing a session and a CSRF token, and no part of
  the UI and no timer ever hit it -- so an `:authority-pending` proposal never resolved
  at all. The gap was recorded as 'the app learns the outcome late'. It did not learn it
  late; absent someone hand-crafting a POST, it never learned it.

  Only enabled authorities are asked. A disabled one has nothing to ask about, and
  `refresh!` would refuse anyway -- collecting refusals for authorities the deployment
  switched off would bury the answers that matter.

  Never throws: one authority being unreachable must not stop the others resolving, so a
  failure is recorded against its own proposal and the sweep continues."
  [configuration session]
  (let [pending (filter authority/pending-with-authority? (authority/proposals session))
        enabled (filter #(transport/enabled? configuration %) (keys adapters))
        askable (filter #(contains? (set enabled) (:authority %)) pending)]
    {:schema "cloud.itonami.app.authority.api.resolve-pending.v1"
     :asked (count askable)
     ;; Named so a caller can tell "nothing was pending" from "everything pending
     ;; belongs to an authority this deployment has switched off".
     :skipped-disabled (- (count pending) (count askable))
     :results
     (vec (for [p askable]
            (let [before (:status p)
                  after (try (:status (refresh! configuration session
                                                (:authority p) (:id p)))
                             (catch Exception e
                               {:error (or (.getMessage e) (str e))}))]
              (cond-> {:id (:id p) :authority (:authority p) :was before}
                (map? after) (assoc :error (:error after))
                (keyword? after) (assoc :now after
                                        :moved? (not= before after))))))}))

(defn record-number-outcome!
  "Update this app's number read model from a COMMITTED numbering proposal.

  Only `:committed`. A proposal that is pending, refused or rejected changed
  nothing, and writing it here would make the read model claim a number the
  authority never granted -- which is the same number the origination check then
  treats as presentable.

  What this records is a governed CLAIM, not a network state: see the warning at
  the top of `cloud.itonami.app.numbers`. `apply-transition!` re-runs the state
  machine against the records as they are NOW, so a transition that has become
  unreachable since review is refused rather than forced -- the read model would
  rather disagree with a stale proposal than with the actor."
  [session proposal]
  (when (and (= :number (:authority proposal)) (= :committed (:status proposal)))
    (let [{:keys [msisdn subject operation iccid]} (:value proposal)]
      (case (:op proposal)
        (:number/allocate :number/port-in)
        (numbers/admit! session msisdn :iccid iccid)

        :number/assign
        (numbers/apply-transition! session msisdn :assign :subject subject)

        :number/lifecycle
        (numbers/apply-transition! session msisdn operation
                                   :iccid iccid
                                   ;; The pre-check already established this
                                   ;; against the same window; re-deriving it
                                   ;; here would let a slow approval land a
                                   ;; recycle whose window has since been
                                   ;; extended by a fresh release.
                                   :quarantine-elapsed? (= :recycle operation))

        ;; A port-out proposal REQUESTS the move; it does not complete it. The
        ;; number stays on this operator's plane, in :porting-out, until the
        ;; actor says otherwise -- and until there is an actor, it stays there.
        :number/port-out
        (numbers/apply-transition! session msisdn :port-out-request)

        nil))))

(defn commit!
  "Hand the consented proposal to its authority and record the outcome.

  A refusal from the actor's governor arrives here as a normal return value with
  `:status :authority-refused`, not as an error: the human consented and the
  licensed operator still said no, which is the two gates working."
  [configuration session authority-key proposal-id]
  (require-enabled! configuration authority-key)
  (let [outcome (authority/commit! (domain-for authority-key) configuration session proposal-id)]
    (record-number-outcome! session outcome)
    outcome))

;; ---------------------------------------------------------------------------
;; read model
;; ---------------------------------------------------------------------------

(defn proposals
  "This session's proposals for one authority, plus the posture that currently
  applies to the subject.

  The posture travels with the read so a UI does not have to derive it -- and so
  it cannot derive it differently."
  [configuration session authority-key]
  (require-enabled! configuration authority-key)
  {:schema "cloud.itonami.app.authority.api.v1"
   :authority authority-key
   :enabled? true
   :posture (posture/subject-posture session configuration)
   :proposals (authority/proposals session authority-key)})

(defn overview
  "Every authority, whether it is enabled, and the proposals for the enabled
  ones. Never refuses -- this is the read a settings screen needs in order to show
  that an authority is OFF, and refusing it would leave nothing to render."
  [configuration session]
  {:schema "cloud.itonami.app.authority.api.overview.v1"
   :posture (posture/subject-posture session configuration)
   :authorities
   (into {}
         (for [k (sort (keys adapters))
               :let [on? (transport/enabled? configuration k)
                     {:keys [endpoint]} (transport/settings configuration k)]]
           [k (cond-> {:enabled? on?
                       ;; Reported because an enabled authority with no endpoint
                       ;; still cannot commit, and a settings screen that shows
                       ;; only "enabled" would be misleading.
                       :endpoint-configured? (boolean (seq (str (or endpoint ""))))}
                on? (assoc :proposals (authority/proposals session k)))]))})
