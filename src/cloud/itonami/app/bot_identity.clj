(ns cloud.itonami.app.bot-identity
  "A workforce Bot's own `did:key`.

  ## Why a Bot needs a name of its own

  A Bot is addressed today by `:bot/id`, which is this application's row
  identifier and means nothing outside it. Everything a Bot is heading toward
  -- delegated authority, a mailbox it corresponds from, a wallet it draws a
  bounded allowance against -- has to name a subject that survives leaving
  this process. `did:key` is that name: self-certifying, verifiable by anyone
  holding nothing, and independent of where the Bot is running.

  ## Derived, not stored

  There is ONE secret (`bot-identity.seed`, 32 bytes, 0600) and every Bot's
  did comes out of it deterministically. The alternative -- generating a
  keypair per Bot and storing 90 of them -- multiplies custody by ninety to
  buy a property nothing yet needs, since the resident already runs all of
  them and could sign as any.

  The derivation is keyed on `:bot/id`, which is ALREADY a stable name:
  `stable-workforce-id` is `UUID/nameUUIDFromBytes` over
  `organization:user:workforce-key`, so re-provisioning a role reproduces the
  same id and therefore the same did. A Bot's identity survives the fleet
  being re-provisioned, which is the whole point of it being an identity.

  ## What this deliberately does not do

  It does not put a signing key in the Bot's hands, and nothing here signs on
  a Bot's behalf. Identity first; the ability to delegate onward is a separate
  decision with a separate blast radius, and ADR-2608180200 already says what
  shape it takes when it comes (biscuit, attenuated, verified against a root
  public key).

  Rotating the seed renames every Bot. That is the cost of one secret instead
  of ninety, and it is why the file is written once and never regenerated
  silently: a missing seed is created, an unreadable one degrades to no
  identity, and neither path quietly mints a different name for a Bot that
  already had one."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ed25519.core :as ed]
            [identity.model :as identity-model]
            [cloud.itonami.app.config :as config])
  (:import [java.security MessageDigest SecureRandom]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermission PosixFilePermissions]))

(def seed-bytes 32)

(defn seed-file []
  (io/file (config/data-dir) "bot-identity.seed"))

(defn- owner-only! [^java.io.File file]
  (try
    (Files/setPosixFilePermissions
     (.toPath file)
     (PosixFilePermissions/fromString "rw-------"))
    (catch Exception _ nil)))

(defn- read-seed []
  (let [file (seed-file)]
    (when (.isFile file)
      (let [bytes (Files/readAllBytes (.toPath file))]
        (when (= seed-bytes (alength bytes)) bytes)))))

(defn fleet-seed
  "The one secret every Bot's did derives from. Created on first use; never
  regenerated once present, because regenerating renames every Bot at once.

  Returns nil rather than throwing when it cannot be read or written: a Bot
  without an identity is a Bot missing a field, not a broken Bot, and a
  resident that refuses to serve because of it would be trading a whole
  workforce for a name nothing has used yet."
  []
  (or (try (read-seed) (catch Exception _ nil))
      (try
        (let [file (seed-file)
              bytes (byte-array seed-bytes)]
          (.nextBytes (SecureRandom.) bytes)
          (io/make-parents file)
          (with-open [out (io/output-stream file)] (.write out bytes))
          (owner-only! file)
          bytes)
        (catch Exception _ nil))))

(defn- derive-seed
  "SHA-256(fleet-seed || 0x00 || bot-id) -- 32 bytes, which is what Ed25519
  takes. The separator is there so two different splits of the same
  concatenation cannot collide."
  [^bytes fleet ^String bot-id]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest fleet)
    (.update digest (byte-array 1))
    (.update digest (.getBytes bot-id "UTF-8"))
    (.digest digest)))

(defn bot-signing-seed
  "The 32-byte Ed25519 seed whose public half IS `bot-did` for this Bot.

  ## This issues nothing. The key already existed.

  `bot-did` is `did-key-from-seed(derive-seed(fleet, bot-id))`, so the private
  half has been derivable from the seed file since Bots had dids at all. What
  did not exist was a caller able to reach it, which is why
  `bot-authority` could mint a token naming the Bot as the only party who may
  append after the first block, and no Bot could append.

  ## What a holder of this can do, and what it cannot

  It can sign one more block onto that Bot's own token, which under Biscuit
  can only NARROW -- `biscuit.authority/->grant` meets each block onto the
  last, and meet never widens. So the worst a leaked bot seed does to
  authority is let somebody hand out a SMALLER slice of what that one Bot
  already had.

  It cannot reach the fleet seed or any sibling: `derive-seed` is
  SHA-256(fleet || 0x00 || bot-id), one-way and domain-separated, so this is
  the leaf of a tree and not a step toward its root.

  It is still a signing key for that Bot's identity, and this is the sentence
  to read twice before calling it: whoever holds it can sign AS that Bot
  anywhere that Bot's did is trusted, which is more than attenuation. Pass it
  to the append and let it go; do not store it, log it, or hand it across a
  process boundary."
  [bot-id]
  (when-let [id (some-> bot-id str not-empty)]
    (when-let [fleet (fleet-seed)]
      (try (derive-seed fleet id) (catch Exception _ nil)))))

(defn bot-did
  "`did:key:z6Mk…` for this Bot, or nil when there is no identity seed.

  Deterministic: the same `bot-id` always yields the same did, on this host,
  for as long as the seed file lives."
  [bot-id]
  (when-let [id (some-> bot-id str not-empty)]
    (when-let [fleet (fleet-seed)]
      (try (ed/did-key-from-seed (derive-seed fleet id))
           (catch Exception _ nil)))))

(defn subject
  "The Bot as an `identity.model/subject`, so it lands in the same shape every
  other identity in this workspace does rather than a second one."
  [{:bot/keys [id name workforce-key]}]
  (when-let [did (bot-did id)]
    (identity-model/subject
     id :agent
     {:did did
      :source :cloud-itonami/workforce
      :labels (into #{} (remove str/blank?) [name workforce-key])})))
