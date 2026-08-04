# 0017 — Message format is a library, not a side job

**Status**: accepted (2026-08-04)
**Follows**: ADR-0016 (a mailbox is the unit, not a provider)

## What went wrong

ADR-0016 wired IMAP into this app by adding whole-message reads to
`kotoba-lang/org-ietf-imap`, and those reads came with a `split-message`, a
transfer-encoding decoder and a quoted-printable decoder written by hand
inside that library.

`kotoba-lang/org-ietf-mime` already existed and did all of it — RFC 5322,
RFC 2045–2047, RFC 8601 — and had done since ADR-2607263000 D8. It was
never looked for. The workspace rule is to search before concluding
something is absent (`nbb scripts/repo-search.cljs`), and the rule exists
because 4,000 repositories are not all checked out; this is the failure it
was written for.

The copy was not merely redundant. It got `multipart/*` wrong — returning
the raw body, boundaries and per-part headers and base64 of every
attachment — and documented that as a limitation. `mime.parse` gets it
right, including the part that is easy to invert: `multipart/alternative`
orders its parts worst-to-best (RFC 2046 §5.1.4), so the **last** match is
the message somebody sent and the first is a fallback.

## Decision

**Protocol libraries carry bytes. `org-ietf-mime` parses them.**

`imap.client/fetch-message!` and `pop3.client/retrieve!` return `:raw`, and
this app parses with `mime/parse` + `mime/message-parts` — which returns
exactly the map `mail.inbound/from-parts` takes, so a synced message and an
archived one are the same kind of thing by the time they reach the mailbox.

**Reads are binary strings.** `org-ietf-mime` states its input contract:
latin-1, one byte per character, so character *n* is byte *n*. Both
transports decoded reads as UTF-8, which destroyed the bytes before the
parser saw them — the symptom was a Japanese body arriving as
`e1n2WkdDf�W~Y`, mojibake that looked like a bug in the MIME parser
two libraries from its cause. A message is bytes and its parts routinely
disagree about what those bytes mean; decoding the whole thing as UTF-8
loses every part that was not UTF-8, quietly, as U+FFFD rather than as an
error.

## Spec coverage raised

| library | added |
|---|---|
| `org-ietf-mime` | RFC 2231 parameter continuations and charset-tagged values (a Japanese attachment filename, a boundary split across continuations); raw 8-bit headers read as UTF-8 when they structurally are |
| `org-ietf-imap` | UIDVALIDITY, untagged responses, AUTH PLAIN/XOAUTH2, STARTTLS, STORE with any flag, LIST/STATUS/EXAMINE/APPEND/COPY/MOVE/EXPUNGE, IDLE, multiple literals per response |
| `org-ietf-smtp` | one transaction per message (all RCPT TO), ESMTP extension parsing, AUTH PLAIN/XOAUTH2, STARTTLS, RSET/NOOP, enhanced status codes, `:raw` |
| `org-ietf-pop3` | **new** — RFC 1939 + CAPA (2449) + STLS (2595) + APOP + SASL (5034) |

Two of those fix defects rather than adding surface:

**One SMTP transaction per message.** `send-mail!` took a single `:to`, so
this app sent once per recipient. That is not one message delivered three
times: each copy carries only its own address in the header, so nobody can
see who else received it and a reply-all reaches one person. RFC 5321 §3.3
has one MAIL FROM and one or more RCPT TO.

**UIDVALIDITY.** A UID is meaningful only with the UIDVALIDITY it was
issued under (RFC 3501 §2.3.1.1). A client that caches UIDs and never reads
it is correct until a server reissues one, after which every stored UID
names a different message — silently, looking like corruption rather than
like a protocol event. `sync!`'s cursor now carries it.

## What this costs, and what is still missing

**POP3 is the lesser protocol and is offered anyway**, because ISP and
legacy hosting mailboxes still speak nothing else. It has no folders, no
server-side flags, and message numbers that renumber on deletion — so read
state for a POP3 account is local only, and the UIDL is what
`:provider-message-id` carries.

**Nothing deletes on the server.** POP3's historical default removed the
only copy as a side effect of reading it; this app never sends DELE.

Not implemented, and listed in each library's README rather than left to be
discovered: IMAP CONDSTORE/QRESYNC, NAMESPACE, ACL, SORT/THREAD,
BODYSTRUCTURE/ENVELOPE parsing; SMTP PIPELINING, CHUNKING/BDAT, DSN,
SMTPUTF8; SASL beyond PLAIN/XOAUTH2 in all three.

**Charsets beyond UTF-8/ASCII/latin-1 need a host decoder**, which this app
injects (`mail-imap/charset-decoder`). `org-ietf-mime` declines to invent
ISO-2022-JP inside a pure library and says so; the JVM's is correct and
already present.
