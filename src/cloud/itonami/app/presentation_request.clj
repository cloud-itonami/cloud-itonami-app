(ns cloud.itonami.app.presentation-request
  "Asking a wallet for a Verifiable Presentation (OID4VP 1.0, Verifier side).

  This app cannot BE a wallet — a Passkey signs its own
  `authenticatorData || clientDataHash` and so cannot produce a key-binding proof
  over a nonce a Verifier chose. It can perfectly well ASK one, and that is the
  half implemented here.

  ## What the request state is for

  §5.3: when the presentations coming back carry no key binding, the `nonce` is
  NOT echoed in the response — there is nothing to bind it into — so `state` is
  the only thing tying a response to the session that asked. It becomes REQUIRED
  at ≥128 bits, and the Verifier must store it and check it. That store is this
  namespace: without it, `state` would be a value we sent and then forgot, which
  is the same as not having sent it.

  A request is single-use. `consume!` removes it, so a response that arrives twice
  is accepted once — the second time there is no session left to match and it is
  refused as unknown rather than replayed.

  ## What this does NOT establish

  `oid4vp.core/validate-response` checks the ENVELOPE. It does not look inside
  `vp_token`, does not verify any presentation, and does not check the wallet
  answered the query. A `:valid? true` from here means \"this response belongs to
  a request we made\", nothing more. Verifying the credentials inside is
  `credential-trust`'s job and the caller must do it."
  (:require [clojure.string :as str]
            [cloud.itonami.app.store :as store]
            [oid4vp.core :as oid4vp])
  (:import [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64]))

(def schema "cloud.itonami.app.presentation-request.v1")

;; 32 bytes of base64url = 43 characters, comfortably over the 128-bit floor §5.3
;; sets for `state`, and the same for the nonce.
(def ^:private entropy-bytes 32)
(def default-lifetime-seconds 600)

(defn- fresh-token []
  (let [b (byte-array entropy-bytes)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- requests [snapshot] (or (:presentation-requests snapshot) {}))

(defn pending
  "Requests that have not been answered, newest first. The nonce and state are
   NOT included: they are the secrets that make the exchange non-replayable, and a
   listing surface has no use for them."
  [snapshot]
  (->> (vals (requests snapshot))
       (map #(dissoc % :nonce :state))
       (sort-by :created-at #(compare %2 %1))
       vec))

(defn create!
  "Mint an OID4VP Authorization Request and remember what answers it.

   `:response-uri` is where the wallet posts back — a URL this deployment serves.
   `:actor` is recorded so an audit can say who asked."
  [{:keys [response-uri actor claims lifetime-seconds now]
    :or {claims [["credentialSubject" "role"]]
         lifetime-seconds default-lifetime-seconds}}]
  (let [id (fresh-token)
        nonce (fresh-token)
        state (fresh-token)
        now (or now (Instant/now))
        request (oid4vp/authorization-request
                 {:client-id (str "redirect_uri:" response-uri)
                  :response-mode "direct_post"
                  :response-uri response-uri
                  :nonce nonce
                  :state state
                  ;; holder-binding? is left at its default of false, which is the
                  ;; honest assumption: we do not know what the wallet can do, and
                  ;; the false branch is the one that demands `state`.
                  :dcql-query (oid4vp/dcql-query
                               [(oid4vp/credential-query
                                 {:id "membership" :format "ldp_vc"
                                  :claims claims})])})]
    (store/transact!
     (fn [current]
       (-> current
           (assoc-in [:presentation-requests id]
                     {:id id :nonce nonce :state state
                      :response-uri response-uri
                      :actor actor
                      :created-at (str now)
                      :expires-at (str (.plusSeconds now (long lifetime-seconds)))})
           (update :events conj
                   {:type :presentation-request/created :at (str now)
                    :id id :actor actor :response-uri response-uri}))))
    {:id id :request request :expires-at (str (.plusSeconds now (long lifetime-seconds)))}))

(defn- expired? [record now]
  (not (pos? (compare (Instant/parse (:expires-at record)) now))))

(defn consume!
  "Find and REMOVE the request `state` belongs to.

   Removed rather than marked: a request is single-use, so a response that arrives
   twice is accepted once. Returns nil when there is no such request, which covers
   both a state we never issued and one already answered — deliberately the same
   answer, because telling the two apart tells a prober which states existed."
  ([state] (consume! state (Instant/now)))
  ([state now]
   (when-not (str/blank? (str state))
     (let [found (some (fn [[_ r]] (when (= state (:state r)) r))
                       (requests (store/snapshot)))]
       (when found
         (store/transact!
          (fn [current] (update current :presentation-requests dissoc (:id found))))
         (when-not (expired? found now) found))))))

(defn validate-response
  "Check an Authorization Response against the request that asked for it.

   Returns `{:valid? bool …}`. `:envelope-only? true` is carried through from the
   library so a caller cannot mistake this for having verified a presentation."
  ([response] (validate-response response (Instant/now)))
  ([response now]
   (let [state (get response "state")
         record (consume! state now)]
     (if-not record
       ;; One answer for "never issued", "already answered" and "expired": the
       ;; distinctions are only useful to somebody probing for valid states.
       {:valid? false :reason :presentation-request/unknown-state :schema schema}
       (let [result (oid4vp/validate-response
                     response
                     {:state (:state record) :nonce (:nonce record)
                      :holder-binding? false})]
         (assoc result :schema schema :request-id (:id record)))))))
