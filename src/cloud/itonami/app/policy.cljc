(ns cloud.itonami.app.policy
  "Routing policy. The judgements are in `policy.kotoba` and RUN from there.

  This namespace keeps the halves that are not decisions: reading a provider
  out of a config map, walking `:providers`, comparing ids. What it no longer
  keeps is a second copy of the rules.

  Until 2026-08-11 it did. `policy-kotoba-parity-test` ran both
  implementations over the same inputs and required the same answers, which
  was the right first step and caught the thing it was built for. But the
  measure of this migration is whether the AUTHORITY moved, not how many host
  lines went away (ADR-2608110100), and while the `.cljc` was what ran, it had
  not."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

(def ^:private provider-record
  "The record `policy.kotoba` declares, spelled here in DECLARED field order.

  Written out rather than read back from the source: if the schema changes
  shape, this stops matching and the call fails loudly, instead of silently
  following the change."
  [:record :policy/provider
   [[:enabled :bool] [:reviewed :bool] [:no-egress :bool]
    [:egress-permitted :bool] [:confidential :bool]
    [:authenticated :bool]]])

(defn loopback-host? [host]
  (oracle/call :policy 'loopback-host? [(str host)]))

(defn- base-url-host
  "The host of a provider's `:base-url`, or nil.

  Parsed rather than trusted from a `:local?` flag. A provider that declares
  itself local and points at a remote address would otherwise be admitted as
  if the bytes stayed here, and the declaration is exactly the thing an
  attacker or a typo gets to write."
  [provider]
  (some-> (:base-url provider) str
          (->> (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:]+)"))
          second))

(defn- https? [provider]
  (boolean (some-> (:base-url provider) str
                   (->> (re-find #"^https://")))))

(defn- credentialed?
  "Whether a request to this provider will actually carry a credential.

  The ENV VAR is read, not just the `:api-key-env` declaration. A provider that
  names a variable nobody exported sends an unauthenticated request, and the
  name alone would let a config assert an account relationship it does not
  have."
  [provider]
  (boolean (some-> (:api-key-env provider)
                   #?(:clj (System/getenv) :cljs (aget (or (.-env js/process) #js {})))
                   str
                   not-empty)))

(defn provider-allowed?
  "May this provider be used?

  Security first: review is universal and locality is evidence rather than
  permission. A loopback provider still has to have been reviewed — being on
  this machine makes the bytes stay, not the process trustworthy. Egress adds
  the deployment switch, TLS, and a credential.

  Every fact is DERIVED here and none is taken on a provider's word:
  `no-egress` comes from parsing the host out of `:base-url`, `confidential`
  from its scheme, `authenticated` from the environment variable actually being
  set. `:local?` in the config is now documentation, not an input.

  The rule itself is `policy.kotoba/provider-allowed?` — if you find yourself
  wanting to change it here, it is in the other file."
  [config provider]
  (oracle/call :policy 'provider-allowed?
               [(oracle/record provider-record
                               [(boolean (:enabled? provider))
                                (boolean (:reviewed? provider))
                                (boolean (some-> (base-url-host provider) loopback-host?))
                                (boolean (get-in config [:routing :cloud-enabled?]))
                                (https? provider)
                                (credentialed? provider)])]))

(defn select-provider [config requested-id]
  (let [provider-id (or requested-id
                        (get-in config [:routing :default-provider]))]
    (some #(when (and (= provider-id (:id %))
                      (provider-allowed? config %))
             %)
          (:providers config))))
