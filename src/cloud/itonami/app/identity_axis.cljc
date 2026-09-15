(ns cloud.itonami.app.identity-axis
  "DID-axis host half. Judgements run from `identity_core.kotoba`.

  Facts are derived here (blank?, did: subject?). The core answers. Callers
  that branch on their own `str/blank?` for the same rule are a second
  implementation and must not land."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]
            [clojure.string :as str]))

(defn- blank-did? [did]
  (str/blank? (str did)))

(defn- did-subject? [value]
  (and (string? value) (str/starts-with? value "did:")))

(defn may-adopt-user-did?
  "May `candidate` become the User DID while `held` is what the store has?"
  [held candidate]
  (oracle/call :identity 'may-adopt-user-did?
               [(blank-did? held) (did-subject? candidate)]))

(defn may-fill-user-did-on-passkey?
  "May Passkey enrolment write a User DID for the current held value?"
  [held]
  (oracle/call :identity 'may-fill-user-did-on-passkey?
               [(blank-did? held)]))

(defn may-backfill-legacy-user-did?
  "May a pre-ADR-0064 store receive a DID derived from a Passkey COSE key?"
  [passkey-enrolled? held]
  (oracle/call :identity 'may-backfill-legacy-user-did?
               [(boolean passkey-enrolled?) (blank-did? held)]))
