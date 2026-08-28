(ns cloud.itonami.app.humanity-trust
  "Itonami's one evidence-only Human Passport step-up boundary.

  Verification is a live EAS read. Acceptance is bound to the authenticated
  Principal's Passkey Smart Account recipient, persisted atomically, and never
  promoted to a capability or to general trust."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as app-identity]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.wallet :as wallet]
            [identity.adapters.evm :as evm]
            [identity.adapters.human-passport :as passport]
            [identity.adapters.ledger :as ledger]
            [identity.trust-policy :as trust-policy]
            [identity.trust-profile :as trust-profile])
  (:import [java.time Instant]))

(def action "identity.sybil-step-up")

(def ^:private coordinate
  {:namespace (:namespace trust-profile/human-passport-coordinate)
   :chain-id (:chainId trust-profile/human-passport-coordinate)
   :eas-address (:easAddress trust-profile/human-passport-coordinate)
   :schema-registry-address
   (:schemaRegistryAddress trust-profile/human-passport-coordinate)})

(defn- refusal! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- app-ledger [principal-id decision]
  (reify ledger/ILedger
    (transact! [_ datoms opts]
      (let [uid (:case-ref opts)
            record {:schema "cloud.itonami.app.external-trust.v1"
                    :source :human-passport
                    :subject-principal principal-id
                    :attestation-uid uid
                    :observed-at (:at opts)
                    :decision decision
                    :datoms (vec datoms)}]
        (store/transact!
         (fn [state]
           (-> state
               (assoc-in [:trust :external :human-passport principal-id uid]
                         record)
               (assoc-in [:trust :external :human-passport principal-id :latest]
                         uid))))
        {:tx/id (str "human-passport:" uid)
         :tx/datoms (count datoms)
         :tx/case-ref uid
         :tx/at (:at opts)}))))

(defn verify-with!
  "Verify, bind and persist one attestation with injected IO for qualification."
  [{:keys [reader decoder now persist!]} {:keys [principal-id recipient]} uid]
  (let [policy (trust-policy/human-passport-policy
                "https://itonami.cloud" action)]
    (when-not policy
      (refusal! :trust/policy-unavailable
                "Human Passport policy is unavailable." {}))
    (when-not (and (string? principal-id) (not (str/blank? principal-id)))
      (refusal! :trust/principal-required
                "A stable authenticated Principal is required." {}))
    (when-not (and (string? recipient)
                   (re-matches #"(?i)^0x[0-9a-f]{40}$" recipient))
      (refusal! :trust/principal-account-required
                "A verified Principal EVM account is required." {}))
    (let [now (long now)
          issued-at (.toString (Instant/ofEpochSecond now))
          bundle
          (passport/verify!
           reader decoder coordinate uid
           {:eas {:allowed-schema-uids #{trust-profile/human-passport-schema-uid}
                  :allowed-attesters #{trust-profile/human-passport-attester}
                  :now now}
            :scorer-id (:scorer-id policy)
            :minimum-score (:minimum-score policy)
            :policy-cid (:id policy)
            :issued-at issued-at
            :subject-id principal-id})
          decision (trust-policy/authorize-human-passport
                    policy recipient bundle)]
      (when-not (:allowed? decision)
        (refusal! :trust/subject-binding-failed
                  "Human Passport recipient does not match the Principal account."
                  {:reason (:reason decision)}))
      ((or persist!
           (fn [verified receipt]
             (ledger/persist-trust-bundle!
              (app-ledger principal-id receipt) verified
              {:case-ref uid :at issued-at})))
       bundle decision)
      {:schema "cloud.itonami.app.human-passport-step-up.v1"
       :verified true
       :action (:action decision)
       :effect "evidence-only"
       :grants-capability false
       :policy-id (:policy-id decision)
       :subject-principal principal-id
       :subject-recipient (:subject-recipient decision)
       :claim-id (:claim-id decision)
       :valid-until (:valid-until decision)})))

(defn verify!
  "Production host: derive the Principal account and read Optimism through the
  explicitly configured HTTPS RPC endpoint."
  [configuration session uid]
  (let [rpc-url (or (get-in configuration [:trust :human-passport :rpc-url])
                    (some-> (System/getenv "HUMAN_PASSPORT_RPC_URL") str/trim not-empty))
        principal-id (app-identity/session-principal-id session)
        account (wallet/ensure-principal-account! configuration session)]
    (when-not rpc-url
      (refusal! :trust/rpc-unconfigured
                "Human Passport RPC is not configured." {}))
    (verify-with! {:reader (evm/eas-reader {:rpc-url rpc-url})
                   :decoder (evm/human-passport-decoder)
                   :now (.getEpochSecond (Instant/now))}
                  {:principal-id principal-id :recipient (:address account)}
                  uid)))
