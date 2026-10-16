(ns cloud.itonami.app.awai-dnssec-policy
  "Portable freshness and shape checks for DNSSEC-verified Awai domain proofs."
  (:require [kotoba.protocol.cid :as cid]
            [kotoba.protocol.install :as install]
            [kotoba.protocol.vocab :as vocab]))

(def maximum-proof-ttl-seconds 300)

(defn- valid-binding-cid? [value]
  (boolean (and (vocab/cid? value)
                (not (:error (cid/parse-cid value))))))

(defn fresh-proof?
  "Recheck the DNSSEC adapter result at the lifecycle boundary. The boolean
  flags are descriptive; domain, owner name, validation class, unique TXT
  answer and bounded lifetime must all match the expected proof shape."
  [domain proof now]
  (let [checked-at (:checked-at proof)
        expires-at (:expires-at proof)
        signature-expires-at (:signature-expires-at proof)
        ttl (:ttl-seconds proof)]
    (and (map? proof)
         (true? (:verified? proof))
         (true? (:fresh? proof))
         (= :secure (:dnssec-status proof))
         (= domain (:domain proof))
         (= (str "_itonami-app." domain ".") (:qname proof))
         (valid-binding-cid? (:binding-cid proof))
         (= [] (:alias-chain proof))
         (= 1 (:txt-record-count proof))
         (nat-int? checked-at)
         (nat-int? now)
         (nat-int? signature-expires-at)
         (integer? ttl)
         (<= 1 ttl maximum-proof-ttl-seconds)
         (<= checked-at now)
         (= expires-at (+ checked-at ttl))
         (<= expires-at signature-expires-at)
         (< now signature-expires-at)
         (< now expires-at))))
