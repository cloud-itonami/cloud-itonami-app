(ns cloud.itonami.app.bot-authority
  "A Bot's authority, carried as a biscuit and decided by `authority`.

  ## Two wires, one decider

  ADR-2608180200 fixes both halves and they must be read separately.
  Biscuit is the DELEGATION FORMAT: a token whose blocks are signed in a key
  chain, which anyone holding only the root PUBLIC key can verify, and which
  anyone at all can attenuate without contacting the issuer. `authority` is
  the DECIDER: `covers?` and `meet`, one implementation, unchanged.

  Nothing here answers *does this cover that*. `biscuit.authority/->grant`
  folds a verified token into an inert grant and `authority.grant/covers?`
  decides. That ADR records what a second decider cost the last time: a
  `covers?` written once per URI scheme, one copy comparing with
  `starts-with?`, so `kotoba://graph/alice*` covered
  `kotoba://graph/alice-evil`.

  ## Why the Bot's capabilities are worth carrying this way

  A workforce Bot's capabilities come from loop-yakuwari and are enforced
  today by intersecting them with the concrete tool grant inside this
  process. That works exactly as far as this process reaches. A token does
  not: a mailbox on another host, a settlement worker, an edge Worker can all
  verify it holding no secret, which is the property macaroons could not give
  without shipping the root secret everywhere it is checked.

  ## What is NOT here

  Attenuation by the Bot itself. `biscuit.token/append` needs the private key
  the previous block named, and no Bot holds one -- `bot-identity` gives a
  Bot a name, deliberately not a signing key. So the fleet can issue a Bot's
  authority and anyone can verify it, and a Bot cannot yet hand a narrower
  slice to something else. That is the next decision, not an oversight.

  The root key here signs authority for the whole workforce. It is a separate
  secret from `bot-identity.seed`: one names Bots, this one speaks for the
  fleet, and a compromise of either should not be a compromise of both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ed25519.core :as ed]
            [biscuit.token :as token]
            [biscuit.authority :as biscuit-authority]
            [authority.grant :as grant]
            [cloud.itonami.app.bot-identity :as bot-identity]
            [cloud.itonami.app.config :as config])
  (:import [java.security SecureRandom]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(def seed-bytes 32)

(defn root-seed-file []
  (io/file (config/data-dir) "workforce-authority.seed"))

(defn- read-seed [file]
  (when (.isFile file)
    (let [bytes (Files/readAllBytes (.toPath file))]
      (when (= seed-bytes (alength bytes)) bytes))))

(defn root-seed
  "The fleet's authority root. Created on first use, 0600, never regenerated:
  rotating it invalidates every token already issued."
  []
  (let [file (root-seed-file)]
    (or (try (read-seed file) (catch Exception _ nil))
        (try
          (let [bytes (byte-array seed-bytes)]
            (.nextBytes (SecureRandom.) bytes)
            (io/make-parents file)
            (with-open [out (io/output-stream file)] (.write out bytes))
            (try (Files/setPosixFilePermissions
                  (.toPath file) (PosixFilePermissions/fromString "rw-------"))
                 (catch Exception _ nil))
            bytes)
          (catch Exception _ nil)))))

(defn root-did
  "The public half, as a did:key. This is the ONLY thing a verifier needs."
  []
  (some-> (root-seed) ed/did-key-from-seed))

;; ── capability -> scope ──────────────────────────────────────────────────
;;
;; A capability is a namespaced keyword in loop-yakuwari (`:patch.create`).
;; `authority` addresses resources as `kotoba://…` URIs. The mapping is
;; mechanical and total: no capability is dropped silently, because a dropped
;; capability is a Bot quietly holding less than its role says.

(defn capability->scope [workforce-key capability]
  (str "kotoba://cap/" (str/replace (str workforce-key) #"/" ":")
       "/" (name capability)))

(defn- capability-facts
  "Only capabilities the fleet decided are the Bot's to exercise become
  scopes. `:blocked` is not a narrower grant -- it is the absence of one --
  and `:approval-required` / `:voice-required` are decisions a human still
  makes, so carrying them as scope would be the token claiming what the
  policy withheld."
  [workforce-key capability-policy]
  (for [{:keys [capability decision]} capability-policy
        :when (= :autonomous (keyword (name (or decision :blocked))))]
    ['scope (capability->scope workforce-key capability)]))

(defn issue
  "A biscuit carrying this Bot's autonomous capabilities, held by its did.

  Returns nil when there is no root key or the Bot has no did -- an
  unsignable token must not be approximated by an unsigned one."
  [{:bot/keys [id workforce-key] :as bot} capability-policy]
  (when-let [seed (root-seed)]
    (when-let [holder (bot-identity/bot-did id)]
      (token/authority
       {:facts (into [['holder holder]] (capability-facts workforce-key capability-policy))
        :rules [] :checks []
        ;; The next key is the Bot's own did. Nothing can append after this
        ;; block without the matching private key, and no Bot holds one --
        ;; so today this names the only party who could ever attenuate it.
        :next-public-key holder
        :root-private-key seed
        :sign-fn (fn [s payload] (ed/sign s (.getBytes ^String payload "UTF-8")))}))))

(defn verify
  "`{:ok? true :blocks n}` or a reason. Needs only the root did:key."
  [t]
  (if-let [root (root-did)]
    (token/verify t root
                  (fn [did payload sig]
                    (try (ed/verify-did did (.getBytes ^String payload "UTF-8") sig)
                         (catch Exception _ false))))
    {:ok? false :reason :no-root-key}))

(def fleet-scope
  "The widest authority the fleet ever issues over Bot capabilities.

  `biscuit.authority/->grant` MEETS each block onto this, and meet only ever
  narrows -- so the base has to be the top of the range, not the bottom.
  Passing an empty grant here produced a token that reached nothing, which
  reads as a safe failure and is not one: it is indistinguishable from a Bot
  with no capabilities, and the caller cannot tell a withheld grant from a
  broken fold."
  "kotoba://cap/*")

(defn ->grant
  "A VERIFIED token as an inert `authority` grant. Refuses an unverified one:
  folding first and checking later is how a forged token becomes a decision."
  [t]
  (when (:ok? (verify t))
    (biscuit-authority/->grant t {:scopes [fleet-scope]})))

(defn authorized?
  "Does this token authorise `capability` for `workforce-key`, right now, in
  the hands of `holder`?

  Every argument `authority.grant/authorized?` insists on is passed through
  rather than defaulted. Its docstring names the clock and the holder as
  the two things a hurried caller drops first, and the first version of this
  namespace dropped both -- it called `covers?` with a scope STRING where a
  grant was expected, so the scopes it read were nil,
  nothing escalated, and it answered true for every capability of every
  business including ones the policy had BLOCKED. The library was right and
  the caller was wrong, which is the direction this arrangement is meant to
  make visible."
  [t workforce-key capability {:keys [now holder]}]
  (boolean
   (when-let [g (->grant t)]
     (grant/authorized? g (capability->scope workforce-key capability)
                        {:now now :holder holder}))))
