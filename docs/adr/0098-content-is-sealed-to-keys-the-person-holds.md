# ADR-0098: Content is sealed to keys the person holds; the operator holds ciphertext

**Status:** proposed — 2026-10-10. Owner question: whether moving to
itonami.cloud can remove ransomware and data-leak risk, and whether a person can
avoid entrusting their information to the company at all.

## Context

The answer to the owner's question is no for ransomware in general. Most
incidents begin with stolen credentials, an edge-device vulnerability or a
compromised endpoint, and a compromised endpoint reads whatever its person can.
One part of the risk is structural, though, and it can be removed: **a single
operator holds plaintext, or the keys to it, for everyone.** One breach of that
operator is then a breach of every customer. This ADR removes that shape. It
does not claim to remove the rest.

Measured in this repository, 2026-10-10. There are three content stores, and
only one of them already has the right shape.

| Store | Sealed with | Where the private key is | Who can read it |
|---|---|---|---|
| Filed mail bodies (ADR-0021/0022) | `age`, by `project-repository/age-encrypt!` (`project_repository.cljk:797-817`) | **One deployment-wide identity** in kagi item `itonami-mail-age`, or Keychain `cloud-itonami-app.mail-age` (`mail_age_key.cljk:24-27`) | Whoever operates the deployment, for every person's mail |
| Drive objects (ADR-0057) | `envelope.seal-jvm`, through `drive-crypto/seal-for` | **A plaintext 0600 file** at `<data-dir>/drive-keys/<sha256(principal)>.edn`, generated on the host (`drive_crypto.cljk:33-75`) | The host process, and anyone holding the data directory |
| Repository storage (ADR-0013) | Kagi AES-GCM under a per-user VMK compartment key (`repository_storage.cljk:341-351`) | The person's Kagi VMK, wrapped by Passkey or OS-Keychain unlock | Whoever performs the unwrap |

Mail and Drive protect against the object store (B2, Storj, kotobase.net). They
do not protect against the host, because the key sits beside the ciphertext's
owner on the same machine. For mail it is worse: one key opens every person's
mail.

While the app was a loopback resident on the owner's Mac, "the host" and "the
person" were the same party, and that was tolerable. **ADR-0092 changed this.**
itonami.cloud now runs as a hosted agent on murakumo. The host is now the
operator, and every key that unwraps there is a key the company holds.

Kagi already has the model this ADR needs (`kotoba-lang/kagi`, README §鍵階層):

- unlock by passphrase (Argon2id), **WebAuthn PRF**, or Shamir recovery;
- a VMK, then HKDF compartment keys, then per-item DEKs;
- hybrid X25519 + ML-KEM-768 wraps;
- per-item grants to an agent's KEM key, where revoking re-keys rather than
  asking nicely.

A browser PRF adapter exists (`CHANGELOG.md:127-136`), and only the public PRF
salt is persisted. Nothing in this app calls it yet. `prf` does not appear in
`src/`.

## Decision

### 1. One key hierarchy: the person's Kagi VMK

Mail-body recipients and Drive recipient keys become items under the owning
person's Kagi VMK. They stop being plaintext files or a deployment identity.
Repository storage already works this way. After this ADR, all three stores
answer "whose key?" the same way.

An organization's content is sealed to a per-organization compartment. Each
member device receives the compartment key as a hybrid-KEM grant. This is Kagi's
existing organization/device grant, not a new mechanism.

### 2. The custody line: a key unwraps only where the person is in control

A VMK, compartment key or DEK may be unwrapped only in:

- **the browser**, via WebAuthn PRF through HKDF to the VMK unwrap KEK, using
  WebCrypto and the existing `kagi.crypto.noble` provider;
- **the desktop app** on the person's own machine, with signed and staged
  updates (ADR-0030, ADR-0033);
- **a loopback resident** whose data directory is on the person's own disk.

The hosted plane stores only ciphertext, wrapped keys, public keys and PRF salts.
The hosted plane means murakumo nodes, Cloudflare Workers, kotobase.net and B2.
A hosted node with an `age` identity, a `drive-keys/` directory or an unwrapped
VMK is a configuration error. Startup refuses it; it is not a warning.

### 3. Mail is sealed on arrival, to the owner's key

Mail bodies are sealed to the owning tenant's public keys. That is the person's
key for a personal tenant (ADR-0023), or the organization compartment for an
organization. They are no longer sealed to the deployment.

An organization may add an **escrow recipient**, for example for legal hold. It
does so only as an explicit organization setting that an owner enables with a
Passkey step-up. Each envelope already records `:mail/body-recipients`
(`project_repository.cljk:838-839`). Escrow is therefore visible per message,
not just per policy.

Inbound mail arrives at a hosted endpoint in plaintext, because SMTP gives no
choice. The ingress seals the body to public keys before anything persists it,
and it keeps no plaintext copy. **The operator sees mail at the moment it
arrives; this ADR limits retention, not ingress.** The UI says so.

### 4. A Bot reads sealed content only under a grant

Bots run on the hosted plane, so by default they cannot read sealed content.
When a Bot needs a document, it asks for it (ADR-0044). A human approval issues a
Kagi grant scoped to the named items. The approval is bound to the instruction
it was asked under (ADR-0046). The grant wraps those items' DEKs to the run's
ephemeral KEM key and expires with the run.

Ending a grant uses Kagi's `ungrant`, which re-keys. A standing delegation
(ADR-0070) may pre-authorize grants for a named project. It never covers "all of
my content".

### 5. Recovery comes before sealing

The switch to person-held keys does not happen until the person has **at least
two unlock wraps**. Either:

- two Passkeys on different devices; or
- one Passkey plus a Shamir recovery kit that the person has confirmed by
  restoring from it once.

There is no operator recovery path. Losing every wrap loses the content. The
enrolment screen states that before the switch, not after.

### 6. Migration, in this order

1. **Drive keys wrap under the VMK.** This is local only, with no change in
   behaviour. Existing `drive-keys/*.edn` files are re-written as Kagi items,
   then deleted.
2. **PRF unlock in the browser and desktop app.** The second wrap is required by
   §5.
3. **Mail recipients switch.** Each existing deployment-sealed body is
   re-sealed:
   - the person's client unwraps the deployment identity once, through a
     one-time, logged grant;
   - it re-encrypts the body to the owner's key;
   - it pushes the new annex object.

   When no body remains under the deployment recipient, that identity is
   destroyed. Destroying it is the step that removes the honeypot; until it
   happens, step 3 has not finished.

## Consequences

- **A breach of the hosted plane yields ciphertext.** An attacker, or the
  operator, can still delete or encrypt it, so ransomware's availability attack
  is unchanged. That is handled by a separate measure, not by this ADR:
  Kotobase's append-only head chain plus object-lock retention on B2.
  Confidentiality and recoverability are different properties and are not
  claimed together.
- **The web client is only as trustworthy as whoever serves its JavaScript.**
  A hostile operator could ship a page that exfiltrates the PRF output. The
  desktop app, with signed updates and native publisher trust (ADR-0033), is the
  client that actually removes the operator from the trust path. Documentation
  must say this rather than call the web client zero-knowledge.
- **Metadata stays visible.** Mail envelopes carry subjects and remain ordinary
  Git objects (ADR-0022). Object counts and sizes are visible. Sealing envelopes
  is a separate decision.
- **Server-side features that read plaintext move or stop.** That includes
  full-text search, body-reading filing rules (ADR-0019) and Bot summarization.
  They either run where the key unwraps, or run under a §4 grant. None of them
  may quietly keep a server-side key.
- **The local DID seed** at `<data-dir>/identity/<user-id>.ed25519` (ADR-0064) is
  unused and out of scope here. Holder keys are ADR-0099.

## Verification (for the implementation, not this draft)

- **No hosted keys.** On a hosted-profile start, the presence of
  `drive-keys/`, an `itonami-mail-age` identity, or `KAGI_HOME` with an unwrapped
  VMK fails startup. A test asserts the refusal.
- **Recipients.** Every envelope written after step 3 has
  `:mail/body-recipients` equal to the owning tenant's keys, plus escrow only
  when the organization setting is on.
- **Bot grants.** A Bot run without a grant gets `:kagi/not-granted` on a sealed
  item. After `ungrant`, the previous DEK no longer opens the re-keyed item.
- **Recovery gate.** Enrolment refuses to seal while fewer than two wraps
  exist.
