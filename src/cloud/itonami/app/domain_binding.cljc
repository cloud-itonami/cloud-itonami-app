(ns cloud.itonami.app.domain-binding
  "Whether a tenant may be CALLED by a domain (ADR-0043).

  The judgement is in `domain_binding_core.kotoba` and RUNS from there. This
  namespace is the host half: build the record, call the shipped artifact, turn
  an `:i64` back into a state keyword, decide nothing.

  DNS, the outbound probe, the store writes and every `throw` are
  `domain_verification.clj`'s. So is establishing the eight facts — this file
  does not read them out of anything, it is handed them, which is what keeps it
  callable from a test with a table instead of a deployment."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

(def ^:private facts-record
  "The record `domain_binding_core.kotoba` declares, spelled here in DECLARED
  field order.

  Written out rather than read back from the source: if the schema changes
  shape, this stops matching and the call fails loudly, instead of silently
  following the change."
  [:record :domain-binding/facts
   [[:owner-authorized :bool]
    [:txt-observed :bool]
    [:claim-exclusive :bool]
    [:probe-answered :bool]
    [:probe-confidential :bool]
    [:probe-fresh :bool]
    [:name-is-service-owned :bool]
    [:previously-live :bool]]])

(def fact-keys
  "The eight facts, in the order the record declares them.

  Public because the callers that establish them should be able to prove they
  established all eight. A map missing one would otherwise cross as `false`,
  which for `claim-exclusive` reads as \"somebody else holds this name\" and for
  `previously-live` silently turns a lapse into a claim."
  [:owner-authorized :txt-observed :claim-exclusive :probe-answered
   :probe-confidential :probe-fresh :name-is-service-owned :previously-live])

(defn- facts [m]
  (let [missing (remove #(contains? m %) fact-keys)]
    (when (seq missing)
      (throw (ex-info (str "domain binding facts are incomplete: "
                           (pr-str (vec missing)))
                      {:type :domain-binding/incomplete-facts
                       :missing (vec missing)})))
    (oracle/record facts-record (mapv #(boolean (get m %)) fact-keys))))

(def states
  "Guest `:i64` -> the state keyword the store holds.

  Built from the core's own exports rather than written as a literal, so a
  renumbering in the core cannot leave this map pointing at the wrong names."
  (delay
    (into {} (map (fn [[state export]]
                    [(oracle/i64-value (oracle/call :domain-binding export []))
                     state]))
          {:pending 'state-pending
           :claimed 'state-claimed
           :live 'state-live
           :lapsed 'state-lapsed})))

(defn nonce-route?
  "Is this request the public activation-nonce document?

  Stringified here so a caller does not have to know the guest ABI, exactly as
  `did_web.cljc` does for its route."
  [method path]
  (oracle/call :domain-binding 'nonce-route? [(str method) (str path)]))

(defn may-start?
  "May a challenge be issued for this name?"
  [m]
  (oracle/call :domain-binding 'may-start? [(facts m)]))

(defn claim-holds?
  "Is the naming right established — and only that?"
  [m]
  (oracle/call :domain-binding 'claim-holds? [(facts m)]))

(defn name-holds?
  "Is the name both proven and answering here?"
  [m]
  (oracle/call :domain-binding 'name-holds? [(facts m)]))

(defn binding-state
  "`:pending` / `:claimed` / `:live` / `:lapsed` for these facts.

  Throws on an `:i64` the core did not name, rather than returning nil. A nil
  state written into the store would read as \"no binding\" everywhere that asks."
  [m]
  (let [code (oracle/i64-value (oracle/call :domain-binding 'binding-state [(facts m)]))]
    (or (get @states code)
        (throw (ex-info "the decision core returned a state this host cannot name"
                        {:type :domain-binding/unknown-state :code code})))))
