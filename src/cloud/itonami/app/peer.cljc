(ns cloud.itonami.app.peer
  "Named durable Bots as persistent peers: they message, they do not inherit.

  `handoff` is one Bot giving work to another as a bounded chain.
  This namespace is two Bots talking. What crosses is a note. What does not
  cross is any part of the sender's grant: `->pair` has no field for it.
  Memory stays with the Bot that wrote it. The computer is the owner's.

  Decisions live in `peer_core.kotoba`. This namespace hands it booleans and
  reads back its answers."
  (:require [clojure.string :as str]
            [cloud.itonami.app.kotoba-oracle :as oracle]))

(def schema "cloud.itonami.app.peer.v1")

(def ^:private pair-record
  "The record `peer_core.kotoba` declares, in DECLARED field order.

  No tool, no grant, no account, no task text, no depth. The absence is the
  mechanism: adding a field here would remove it."
  [:record :peer/pair
   [[:same-owner :bool] [:source-enabled :bool] [:target-enabled :bool]
    [:distinct-bots :bool]]])

(def ^:private decision-record
  [:record :peer/decision
   [[:human :bool] [:identified :bool] [:authorized :bool]]])

(def ^:private reach-record
  [:record :peer/reach
   [[:same-owner :bool] [:target-enabled :bool] [:device-known :bool]
    [:device-is-local :bool] [:remote-enabled :bool]]])

(defn ->pair
  "The four facts the core decides from.

  `source` and `target` are Bots; owners are what the host resolved.
  Whether they are the same person is answered HERE."
  [source target {:keys [source-owner target-owner]}]
  (oracle/record pair-record
                 [(boolean (and source-owner target-owner
                                (= source-owner target-owner)))
                  (boolean (:bot/enabled? source))
                  (boolean (:bot/enabled? target))
                  (not= (:bot/id source) (:bot/id target))]))

(defn may-message?
  "May `source` send an asynchronous note to `target` right now?"
  [source target context]
  (oracle/call :peer 'may-message? [(->pair source target context)]))

(defn computer-shared?
  "Do these two Bots work on the same computer?"
  [source target context]
  (oracle/call :peer 'computer-shared? [(->pair source target context)]))

(defn foreign-memory?
  "Would letting `source` read `target`'s memory be reading someone else's?"
  [source target context]
  (oracle/call :peer 'foreign-memory? [(->pair source target context)]))

(defn may-approve?
  "May this actor record an approval that arrived by peer message?

  Never, for an agent — restated here so a Bot cannot approve by DMing a
  held run to a second Bot and having that one say yes."
  [{:keys [actor-kind human? identified? authorized?]}]
  (oracle/call
   :peer 'may-approve?
   [(oracle/record decision-record
                   [(boolean human?) (boolean identified?) (boolean authorized?)])
    (name (or actor-kind :unknown))]))

;; ── addresses ───────────────────────────────────────────────────────────
;;
;; `bot:<id>` is a Bot on this machine. `bot:<id>@<device>` is one on another
;; of the owner's machines (ADR-0062).
;;
;; What crosses a device boundary is a note. What does not cross is anything
;; that could make something happen over there: `->pair` has no field for a
;; grant, `may-approve?` refuses an agent outright, and a remote Bot's writes
;; stop on ITS OWN approval cards under ITS OWN grants. That is why this is a
;; handle and not a transport for authority -- and why ADR-0036's refusal of a
;; cloud VM is untouched by it. A machine a person owns and has enrolled is not
;; somewhere the fail-closed policy fails to reach; it is somewhere the policy
;; is the one running.

(def ^:private address-pattern #"^bot:([A-Za-z0-9][A-Za-z0-9_-]*)(?:@([A-Za-z0-9][A-Za-z0-9_.-]*))?$")

(defn address
  "The mailbox address of a Bot, on this machine or on a named device.

  A nil or blank device gives the local form, so a caller that does not know
  about devices writes what it always wrote."
  ([bot-id] (address bot-id nil))
  ([bot-id device]
   (let [device (some-> device str str/trim not-empty)]
     (str "bot:" bot-id (when device (str "@" device))))))

(defn parse-address
  "`{:bot-id … :device …}`, or nil when this is not a Bot address.

  nil rather than a throw, and nil rather than a partial parse: an address that
  does not match is not a Bot's, and guessing which half was meant is how a
  message reaches the wrong principal."
  [value]
  (when-let [[_ bot-id device] (re-matches address-pattern (str value))]
    {:bot-id bot-id :device device}))

(defn- ->reach
  [target {:keys [source-owner target-owner device local-device remote-enabled?
                  known-devices]}]
  (let [device (some-> device str not-empty)]
    (oracle/record reach-record
                   [(boolean (and source-owner target-owner
                                  (= source-owner target-owner)))
                    (boolean (:bot/enabled? target))
                    ;; A device nobody registered is not addressable. The local
                    ;; device counts as known without being enrolled twice.
                    (boolean (or (nil? device)
                                 (= device local-device)
                                 (contains? (set known-devices) device)))
                    (boolean (or (nil? device) (= device local-device)))
                    (boolean remote-enabled?)])))

(defn may-address?
  "May this owner's Bot be reached at this handle right now?"
  [target context]
  (oracle/call :peer 'may-address? [(->reach target context)]))

(defn reaches-another-machine?
  "Does this handle name a device other than the one running?"
  [target context]
  (oracle/call :peer 'reaches-another-machine? [(->reach target context)]))
