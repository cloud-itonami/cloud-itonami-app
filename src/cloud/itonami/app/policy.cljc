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
   [[:enabled :bool] [:local :bool]
    [:cloud-enabled :bool] [:cloud-reviewed :bool]]])

(defn loopback-host? [host]
  (oracle/call :policy 'loopback-host? [(str host)]))

(defn provider-allowed?
  "Local providers are admitted by being enabled. Cloud providers require BOTH
  the global cloud gate and the privacy gate; there is no implicit fallback
  from local to cloud.

  The rule itself is `policy.kotoba/provider-allowed?`. This projects the two
  maps into the four booleans it asks about and does nothing else — if you
  find yourself wanting to change the rule here, it is in the other file."
  [config provider]
  (oracle/call :policy 'provider-allowed?
               [(oracle/record provider-record
                               [(boolean (:enabled? provider))
                                (boolean (:local? provider))
                                (boolean (get-in config [:routing :cloud-enabled?]))
                                (boolean (get-in config [:privacy :allow-cloud-without-review?]))])]))

(defn select-provider [config requested-id]
  (let [provider-id (or requested-id
                        (get-in config [:routing :default-provider]))]
    (some #(when (and (= provider-id (:id %))
                      (provider-allowed? config %))
             %)
          (:providers config))))
