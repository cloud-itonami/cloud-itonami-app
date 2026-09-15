(ns cloud.itonami.app.capability
  "A share, as a signed capability rather than a row in this server's state.

  ## What this changes, and what it does not

  Before this, a grant was `{principal-id role}` written into
  `:drive/permissions`. Nothing outside this process could check it, nothing
  expired, and the only evidence that alice had let bob read a document was
  that this server said so.

  A grant is now also a CACAO (CAIP-74): a SIWE statement naming an
  audience, a resource and a role, signed with Ed25519 and carrying an
  expiry. Three things follow that did not before — it can be verified
  without asking this server, it stops being true on its own, and
  `cacao.core/verify-chain` is already there for the day a grantee may
  re-grant.

  **It does not remove this server from the trust path, and saying otherwise
  would be the whole lie of this layer.** The issuer is the Drive's own key,
  not the granting user's, so the Drive can mint any capability it likes. It
  is the Drive attesting \"I let bob read this, until then\", verifiable by
  anyone afterwards — not alice proving she chose to.

  The reason it is not alice's key is concrete rather than an omission. Her
  Passkey is a WebAuthn P-256 credential (`cloud.itonami.app.did`); WebAuthn
  signs its own `authenticatorData || clientDataHash` with ES256.
  `cacao.core/mint` signs a SIWE string with EdDSA from a raw seed. There is
  no way to make the one produce the other. The User DID may itself be
  Ed25519 (minted at creation, not from the Passkey), but this layer is still
  the Drive attesting — not alice proving she chose to. Teaching cacao a
  WebAuthn signature type, or minting user-issued CACAOs from the person's
  seed, is a change to that library / a later slice, not something to fake
  here.

  ## The key

  Generated once and kept beside the state file. For a server that binds
  loopback-only that is the same protection the documents themselves have,
  and no more: whoever can read `data/` can mint capabilities. Named here
  rather than left for somebody to work out."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cacao.core :as cacao]
            [cloud.itonami.app.config :as config]
            [ed25519.core :as ed])
  (:import [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64 UUID]))

(def schema "cloud.itonami.app.capability.v1")

(def ^:private roles
  "The Drive's roles as capability verbs. `:owner` is absent because it is
  not something one principal hands another — see `documents/grantable-roles`."
  {:editor "write" :commenter "comment" :viewer "read"})

(defn- key-file [] (io/file (config/data-dir) "drive-issuer.key"))

(defn- read-seed []
  (let [f (key-file)]
    (when (.isFile f)
      (.decode (Base64/getDecoder) ^String (str/trim (slurp f))))))

(defn- write-seed! [^bytes seed]
  (let [f (key-file)]
    (.mkdirs (.getParentFile f))
    (spit f (.encodeToString (Base64/getEncoder) seed))
    ;; Best effort: the JVM cannot set POSIX permissions portably through
    ;; `spit`, and a file nobody else can read is the point of saying so.
    (doto f (.setReadable false false) (.setReadable true true)
             (.setWritable false false) (.setWritable true true))
    seed))

(defn issuer-seed
  "The Drive's Ed25519 seed, generated on first use.

  Deliberately not regenerated when the file is unreadable: a new key would
  silently invalidate every capability already issued, and a Drive that
  quietly stopped honouring its own past attestations is worse than one that
  fails loudly."
  []
  (or (read-seed)
      (write-seed! (let [seed (byte-array 32)]
                     (.nextBytes (SecureRandom.) seed)
                     seed))))

(defn issuer-did
  "The `did:key` this Drive issues capabilities as."
  []
  (ed/did-key-from-seed (issuer-seed)))

(defn resource
  "The capability URI a grant is about.

  `drive:<document-id>#<verb>` — the document names itself and the verb says
  what is permitted, so `cacao.core/covers?` can compare two capabilities
  without knowing what a Drive is."
  [document-id role]
  (str "drive:" document-id "#" (get roles role (name role))))

(defn mint-grant
  "A CACAO attesting that `audience` may act on `document-id` as `role`.

  `audience` is the grantee's DID when they have one and their principal id
  when they do not — a legacy User without a DID is still shareable. Refusing
  to share until a Passkey exists would be this layer deciding a product
  question (and the person DID is no longer derived from that Passkey)."
  [{:keys [document-id role audience expires-in-days now]
    :or {expires-in-days 365}}]
  (let [now (or now (Instant/now))
        iat (str now)
        exp (str (.plusSeconds now (* 86400 (long expires-in-days))))]
    (assoc (cacao/mint {:seed (issuer-seed)
                        :aud audience
                        :iat iat
                        :exp exp
                        ;; Required by `mint`: without one, nothing
                        ;; downstream can tell a replay from a reissue.
                        :nonce (str (UUID/randomUUID))
                        :domain "cloud.itonami"
                        :statement (str "Cloud Itonami Drive grants " (name role)
                                        " on " document-id)
                        :resources [(resource document-id role)]})
           :aud audience :iat iat :exp exp)))

(defn verify-grant
  "Whether `capability` is a capability this Drive issued for this document
  and role, and is currently in force.

  Returns `{:valid? bool :reason kw}` rather than throwing: an expired share
  is an ordinary state of the world, and the caller's job is to stop
  honouring it, not to fail."
  [{:keys [cacao-b64 aud] :as capability} document-id role now]
  (cond
    (nil? capability) {:valid? false :reason :absent}
    (str/blank? (str cacao-b64)) {:valid? false :reason :absent}
    :else
    (let [result (try (cacao/verify cacao-b64 {:now (str now)})
                      (catch Exception _ {:valid? false}))
          payload (:payload result)]
      (cond
        (not (:valid? result)) {:valid? false :reason :unverifiable}
        (not= (issuer-did) (:iss payload)) {:valid? false :reason :other-issuer}
        (not= aud (:aud payload)) {:valid? false :reason :audience-mismatch}
        (not (contains? (set (:resources payload)) (resource document-id role)))
        {:valid? false :reason :resource-mismatch}
        :else {:valid? true}))))

(defn expired?
  "Whether `capability` has passed its `exp`, cheaply and without verifying.

  Separate from `verify-grant` because the ACL consults it on every read and
  a signature check per item per request is a cost with no answer in it: a
  capability whose signature is bad was never honoured, and one whose expiry
  has passed stops being honoured now."
  [{:keys [exp]} now]
  (boolean (and exp (neg? (compare (str exp) (str now))))))
