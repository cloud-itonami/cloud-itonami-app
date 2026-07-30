(ns cloud.itonami.app.credential-sd-jwt
  "Membership as an SD-JWT VC, so the holder can withhold their own identifier.

  `cloud.itonami.app.credential` issues the same claim as a W3C VC with a Data
  Integrity proof, and that format discloses everything in the credential to
  whoever sees it. Presenting it twice to two verifiers hands both the same
  `did:key`, which links those presentations to each other whether or not either
  verifier needed to know who the holder was.

  This format lets a holder prove **\"someone at this organization is an
  auditor\"** while withholding *which* someone. That is the privacy property the
  W3C-VC path structurally cannot offer, and the reason both formats exist rather
  than one replacing the other.

  ## What is disclosable, and what cannot be

  `sub` — the subject's `did:key` — is the selectively disclosable claim, because
  it is the linkable one. `iss`, `vct`, `exp`, `cnf`, `nbf`, `status` and
  `vct#integrity` are protected by the specification and the library refuses to
  conceal them; `role` and `organization` are deliberately NOT concealed, since a
  membership credential that could withhold the role would be asserting nothing.

  ## No key binding, and why that is honest here

  `cnf` is omitted, so the issued credential carries no holder-binding key and
  `verify` does not require a KB-JWT. That is not an omission to fix later: this
  app cannot hold a holder key on a user's behalf without a decision nobody has
  taken, and a Passkey cannot produce one — WebAuthn signs its own
  `authenticatorData || clientDataHash`.

  The consequence is real and stated rather than hidden: **an SD-JWT VC issued here
  is bearer-presentable.** Whoever holds the token can present it. It proves the
  organization asserted the claim; it does not prove the presenter is the subject.
  A verifier needing the latter must require key binding, which means a wallet with
  its own key — and `sd-jwt-vc.core/verify` supports that path today, this app
  simply cannot be the wallet."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.capability :as capability]
            [cloud.itonami.app.credential :as credential]
            [ed25519.core :as ed]
            [sd-jwt.core :as sd]
            [sd-jwt-vc.core :as vc])
  (:import [java.security SecureRandom]
           [java.util Base64]))

(def schema "cloud.itonami.app.credential-sd-jwt.v1")

;; EdDSA, because the issuer key is the same Ed25519 seed the Data Integrity path
;; signs with. One key, two formats.
(def alg "EdDSA")

(defn vct
  "The credential type. A collision-resistant name, per the draft — so it is a URL
  under a domain this deployment controls rather than a bare word."
  [organization-domain]
  (if (str/blank? (str organization-domain))
    "urn:cloud-itonami:credentials:membership:v1"
    (str "https://" organization-domain "/credentials/membership/v1")))

(defn- fresh-salt []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- codec [] {:json-encode json/write-str :json-decode #(json/read-str %)})

(defn- issuer-options []
  (merge (codec)
         {:alg alg
          :salt-fn fresh-salt
          :sign (fn [signing-input]
                  (ed/sign (capability/issuer-seed) signing-input))}))

(defn claims
  "The SD-JWT VC payload, before concealment. Separated so a test can see exactly
  what will be signed."
  [{:keys [organization-did organization-domain subject-did role
           organization-name issued-at expires-at]}]
  (when (str/blank? (str subject-did))
    (throw (ex-info "credential subject の DID が必要です。"
                    {:type :credential/no-subject})))
  (cond-> {"iss" (or (not-empty (str organization-did)) (credential/issuer-did-key))
           "vct" (vct organization-domain)
           "iat" issued-at
           ;; `sub` is the linkable claim, and the one this format exists to make
           ;; withholdable.
           "sub" subject-did
           "role" (name (keyword role))}
    expires-at (assoc "exp" expires-at)
    (not (str/blank? (str organization-name)))
    (assoc "organization_name" organization-name)
    (not (str/blank? (str organization-did)))
    (assoc "organization" organization-did)))

(defn issue
  "Issue a membership SD-JWT VC. Returns the presentation the holder keeps, plus
  the issuer JWT and the single Disclosure separately so a caller can see the
  split."
  [{:keys [organization-domain] :as opts}]
  (let [payload (claims opts)
        issued (vc/issue payload [["sub"]] (issuer-options))]
    (assoc issued
           :vct (vct organization-domain)
           :disclosable ["sub"]
           :schema schema)))

(defn- verify-issuer [signing-input sig]
  (ed/verify (ed/pubkey-from-seed (capability/issuer-seed)) signing-input sig))

(defn verify
  "Verify a membership SD-JWT VC this app issued.

  Returns `{:valid? bool :claims … :subject-disclosed? bool}`. `:claims` contains
  `sub` only when the holder chose to disclose it — which is the point, so the
  result says which happened rather than leaving a caller to notice a missing key.

  Malformed input is an answer, not an exception: this is fed tokens from outside."
  [presentation]
  (if-not (string? presentation)
    {:valid? false :reason :credential/not-a-document :schema schema}
    (try
      (let [result (vc/verify presentation
                              (merge (codec)
                                     {:expected-alg alg
                                      :verify verify-issuer}))]
        (if-not (:valid? result)
          {:valid? false :reason (:reason result) :stage (:stage result) :schema schema}
          (let [claims (:claims result)]
            {:valid? true
             :claims claims
             :subject-disclosed? (contains? claims "sub")
             :subject (get claims "sub")
             :role (get claims "role")
             :issuer (get claims "iss")
             :vct (get claims "vct")
             ;; Said out loud in the result: no cnf means no holder proof, so this
             ;; establishes what the organization asserted and NOT who presented it.
             :bearer-presentable? true
             :schema schema})))
      (catch clojure.lang.ExceptionInfo error
        (let [data (ex-data error)]
          {:valid? false
           :reason (or (:sd-jwt-vc/error data) (:sd-jwt/error data)
                       (:jws/error data) (:type data)
                       :credential/unverifiable)
           :schema schema})))))

(defn present
  "Re-serialize a presentation with a chosen subset of Disclosures.

  The holder's operation, offered here because this app is also where a holder's
  credential currently lives. Passing `[]` withholds the subject."
  [issuer-jwt disclosures]
  (sd/present issuer-jwt disclosures))
