(ns cloud.itonami.app.peer
  "Named durable Bots as persistent peers: they message, they do not inherit.

  `handoff` is one Bot giving work to another as a bounded chain.
  This namespace is two Bots talking. What crosses is a note. What does not
  cross is any part of the sender's grant: `->pair` has no field for it.
  Memory stays with the Bot that wrote it. The computer is the owner's.

  Decisions live in `peer_core.kotoba`. This namespace hands it booleans and
  reads back its answers."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

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
