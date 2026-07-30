(ns cloud.itonami.app.authority.api
  "The request layer between HTTP routes and the authority spine.

  It exists so the routes in `cloud.itonami.app.server` stay three lines each and
  so everything below them is testable without an HTTP exchange.

  It enforces two things the adapters cannot enforce for themselves:

  1. AN AUTHORITY MUST BE ENABLED. Every stage refuses when the authority is off,
     including the read. The default is off (see defaults.edn `:authorities`), so
     a fresh install has no outward surface at all.

  2. THE POSTURE IS COMPUTED HERE, NEVER ACCEPTED FROM THE CALLER. The card
     adapter requires a `:posture` for spend and issuance, and if a client could
     supply it, the cross-domain SIM-swap invariant would be advisory -- an
     attacker would simply send `{:authority/posture :normal}`. So this namespace
     computes it from the store and OVERWRITES whatever arrived in the request.
     That overwrite is the invariant's actual enforcement point; the adapter's
     required-input check only stops it being forgotten."
  (:require [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.card :as card]
            [cloud.itonami.app.authority.esim :as esim]
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.authority.transport :as transport]
            [cloud.itonami.app.authority.voice :as voice]))

(def adapters
  "authority key -> the namespace's domain constructor."
  {:esim  esim/domain
   :card  card/domain
   :voice voice/domain})

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

(defn- with-server-computed-posture
  "Replace any caller-supplied `:posture` with the one computed from the store.

  `assoc` rather than a merge that could lose to the request: a caller-supplied
  posture is not merely ignored, it is overwritten, because the whole point is
  that the caller does not get a say in it."
  [configuration session authority-key request]
  (if (and (= :card authority-key)
           (contains? posture/restricted-ops (:op request)))
    (assoc request :posture (posture/subject-posture session configuration))
    request))

;; ---------------------------------------------------------------------------
;; stages
;; ---------------------------------------------------------------------------

(defn review!
  "Pre-check and record a proposal awaiting consent."
  [configuration session authority-key request]
  (require-enabled! configuration authority-key)
  (authority/review! (domain-for authority-key) configuration session
                     (with-server-computed-posture
                       configuration session authority-key request)))

(defn start-approval!
  [configuration session authority-key proposal-id rp-id origin]
  (require-enabled! configuration authority-key)
  (authority/start-approval! (domain-for authority-key) session
                             proposal-id rp-id origin))

(defn finish-approval!
  [configuration session authority-key proposal-id transaction-id credential]
  (require-enabled! configuration authority-key)
  (authority/finish-approval! (domain-for authority-key) session
                              proposal-id transaction-id credential))

(defn reject!
  "Record that the human declined. Still gated on the authority being enabled --
  a disabled authority should have no proposals to decline, and answering as
  though it might is worse than refusing."
  [configuration session authority-key proposal-id]
  (require-enabled! configuration authority-key)
  (authority/reject! session proposal-id))

(defn commit!
  "Hand the consented proposal to its authority and record the outcome.

  A refusal from the actor's governor arrives here as a normal return value with
  `:status :authority-refused`, not as an error: the human consented and the
  licensed operator still said no, which is the two gates working."
  [configuration session authority-key proposal-id]
  (require-enabled! configuration authority-key)
  (authority/commit! (domain-for authority-key) configuration session proposal-id))

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
