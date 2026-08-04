# 0016 — A mailbox is the unit, not a provider

**Status**: accepted (2026-08-04)

## The two things that were wrong

### Synced mail was visible to nobody

`mail-sync` pulled Gmail and Microsoft Graph into `[:mail-sync :messages]`
every minute, and it worked. The Inbox was built by `mailbox/box` out of
`workspace/inbox-mailbox`, which reads `.eml` files off disk and knows
nothing about that key. So mail arrived, was parsed, was labelled, was
archived to disk, was written to the store — and reached no surface.

From outside, connecting Google did nothing. That is not a rendering bug or
a missing endpoint; there were two mailboxes in one application and only one
of them had ever been connected to a screen.

### A provider is not a mailbox

Everything about the old sync assumed one mailbox per provider. `sync-all!`
walked `[:google :microsoft]`. Connection ids were `{org}:{did}:{provider}`.
Keychain accounts were `{org}:{user-id}:{provider}:{kind}`. The disk archive
was `<provider>/<message-id>.json`.

Each of those is a slot, and each of them holds one thing:

- Connecting a second Google account **overwrote the first**. The first
  connection record went on reporting itself connected while every sync for
  it returned the second account's mail.
- Two mailboxes' messages **collided by id** in the store and on disk —
  Gmail message ids are unique within a mailbox, not across mailboxes — so
  one silently overwrote the other, and which one survived depended on the
  order the accounts happened to sync in.
- An expired grant recorded an error against *Google*, which with two Google
  accounts connected says the wrong thing: one mailbox is broken, one is
  fine, and the status could not express that.

And a mailbox at a host that has never issued an OAuth client could not be
connected at all. There was no IMAP anywhere in the application. There was
no SMTP either, so nothing could be sent — the Inbox had a reply affordance
over an application with no code that delivered a message to anybody.

## Decision

**The unit is an account.** One mailbox, one credential, one cursor, one
error state. `mail-account/accounts` returns however many there are, of
three kinds, and everything above it walks accounts without branching on
which kind it is holding:

| kind         | reached over                 | credential                       |
|--------------|------------------------------|----------------------------------|
| `:gmail`     | Gmail API v1 (`com-gmail`)   | an OAuth grant, refreshed        |
| `:microsoft` | Microsoft Graph              | an OAuth grant, refreshed        |
| `:imap`      | IMAP4rev1 (`org-ietf-imap`)  | a password, held in the Keychain |

**One box.** `mailbox/merged-box` folds the on-disk archive and every synced
account into a single `mail.mailbox`, so threads, labels, unread counts and
the search that reads message bodies range over all of it. A message that
syncs is a message that shows up.

**Sending exists**, through whatever the account already proved: an OAuth
account sends through its own provider API, an IMAP account over SMTP
(`org-ietf-smtp`). Asking somebody for an app password to send from a
mailbox this app already holds an OAuth grant for would be collecting a
second credential for a job the first one covers.

**Identity is qualified by the external account.** Connection ids and
Keychain names now carry the provider subject — the subject rather than the
address, because an address can be reassigned and an alias can deliver to a
mailbox whose primary address is something else. Grants stored under the old
unqualified name are still read from it (`keychain-token` falls back once,
read-only); a reconnect writes the qualified name and retires the old record.

**Protocols come from libraries that are tested.** `com-gmail`,
`org-ietf-imap` and `org-ietf-smtp` already existed in this workspace, with
injectable transports, and the application was re-deriving Gmail's HTTP calls
by hand instead. Hand-rolled protocol code here could only ever be exercised
against somebody's real mailbox, so for its whole life it never was.

## What this costs

**Re-consent.** Google's scopes move from `gmail.readonly` to
`gmail.modify` + `gmail.send`, and Microsoft's from `Mail.ReadBasic` to
`Mail.ReadWrite` + `Mail.Send`. Filing under a label and sending a reply are
both writes and fail at the provider with a 403 no amount of local
correctness prevents; `Mail.ReadBasic` cannot even read a message body.
Existing connections keep working on their old grant until the person
reconnects. This stops short of `mail.google.com` (full access, including
permanent delete) — the trash here is reversible on purpose and never needs
it.

**A library change.** `org-ietf-imap` could fetch headers but not bodies,
which is the shape triage needs and not the shape displaying a mailbox
needs. `fetch-message!`, `list-recent!`, `search!` and `mark-unseen!` were
added there rather than reimplemented here.

**IMAP read state is not read.** `list-recent!` does not request FLAGS, so
synced IMAP messages arrive unread and the local mark layer owns the
difference. Claiming a read state this sync did not read would be worse than
not having it.

**Multipart bodies are not parsed.** `org-ietf-imap` returns a
`multipart/*` body raw, boundaries and all, because selecting the text part
out of a multipart tree is MIME parsing and that library says it does not do
that. Gmail and Graph both hand over a decoded body, so this affects IMAP
accounts only.
