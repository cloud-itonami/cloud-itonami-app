# Architecture

## Goal

Own the durable layer around interchangeable models: agent identity, local
memory, tool authority, and the user surface. Local inference is the default
and cloud inference is an explicit policy decision, never a fallback caused by
an error.

The design takes Osaurus's public harness decomposition as a reference, then
maps each responsibility to an existing Kotoba authority instead of reproducing
its Swift implementation.

| Harness responsibility | cloud-itonami-app authority |
|---|---|
| Desktop calendar and Keychain | `kotoba-lang/shell` native host |
| UI semantics | web surface on jp-go-dds |
| Provider selection | safe `.kotoba` policy, executed from the shipped KIR |
| Local/cloud model transport | localhost service adapters |
| Session memory | `kotoba.kgraph` EAV datoms + durable EDN |
| Compatible client access | OpenAI-compatible HTTP; MCP over stdio and authenticated Streamable HTTP |
| Secret access | named environment variables at provider boundary |

## Workspace integrations

`GET /api/workspace` composes read models from authorities that already exist
in this checkout. The UI does not synthesize missing mail bodies, files,
projects, or events.

| Surface | Authority | Current contract |
|---|---|---|
| Inbox | `m365-archive`, every connected mail account, `net-kotobase/mail-worker` | One `mail.mailbox` over the on-disk archive and every synced account; sealed reception remains recipient-key controlled |
| Mail accounts | `kotoba-lang/com-gmail`, Microsoft Graph, `kotoba-lang/org-ietf-imap` / `org-ietf-smtp` | One row per *mailbox*, not per provider: two Gmail accounts are two cursors, two credentials, two error states. Credentials live in the Keychain and never in `state.edn` |
| Projects | `kotoba-lang/com-github` | Reads GitHub Projects v2; shows `permission-required` without `read:project` |
| Drive (archive) | `m365-archive` OneDrive snapshot | Lists file state without silently materializing git-annex objects |
| Drive (documents) | `kotoba-lang/drive` workspace + an object store | Creates and edits Sheets / Docs / Forms / Slides as office envelopes; per-user ACL, quota, versions and a reversible trash; a save the surface's own validator rejects is refused |
| Scheduler | `kotoba-lang/shell` EventKit + `kotoba-lang/calendar` | Reads seven days under the explicit `calendar/read` capability |

`GET /api/workspace/worker` is served next to these but is not one of them: it
reports live queue state rather than reading an external authority, so it
bypasses the read cache.

The combined read is cached for 60 seconds — except the created documents,
which are read live, because a document missing from the list a moment after
it was created reads as a failed create. It is intentionally separate from
model context: viewing a calendar or mailbox does not send its data to an AI
provider.

Kaisya Messenger is a separate organization-scoped mailbox ledger. Direct,
group, and channel conversations fan out to per-principal deliveries for human,
Agent session, and OrganismWorker addresses. Admission is an exact, deny-by-
default sender allowlist; quarantine exposes no body and cannot become model
context. Browser `itonami-signal-v1` keeps private device/ratchet keys in
IndexedDB, uses X3DH + bounded Double Ratchet for device sessions and pairwise-
wrapped sender keys for groups. The server holds public prekeys and ciphertext
only. External OrganismWorkers authenticate with a rotated 0600-file bearer,
poll their own mailbox and reply through `/api/ao/messenger/*`; ordinary
messages still confer no intent, tool, approval, or effect authority.

Creating and editing a document is the one mutation here, and it does not
write to any of the external authorities above: it writes to a
`drive.workspace` held in the app's own state and to an object store the app
owns. Mutation adapters that write back to OneDrive or EventKit still require a
later capability and approval design. GitHub Projects is the narrow exception
described below: its status projection has lease, receipt, and basis gates.

Kanban execution has a pure integration contract in
`cloud.itonami.app.work-governance`: WorkItems are joined to DoDAF performers,
organization assignments, content-bound human approval, `yakuwari` policy and
desired AgentRun capacity. `work-runtime` persists its leases and receipts,
dispatches bounded runs through Agent Control, and is woken by the supervised
`work-reconciler`. The optional `github-projects-writeback` adapter re-reads and
compares the lease-time Projects v2 basis before mutation. Dispatch and GitHub
write-back are independently disabled by default.

The runtime control plane is exposed under `/api/work-governance` with
organization-scoped reads and owner/admin mutations. Approval and independent
review use operation-bound Passkey ceremonies. A file-elected leader performs
ticks, while `state.edn.lock` serializes commits from standby/API processes.
GitHub sources use cursor checkpoints, signed webhook wake-up, rate-limit
backoff, post-mutation verification, projection receipts and dead-letter replay.
Governed work is not stored in `state.edn`: `work-governance/manifest.edn` pins
one atomic generation composed from a global EDN file and one owner-only EDN
file per organization. The previous complete generation is retained for
recovery; older/orphan generations are collected after a successful commit.
The Projects view includes structured editors for performers, assignments,
reporting lines and approval policies; JSON inputs are normalized back into the
canonical namespaced-keyword/set EDN model at the HTTP boundary.
The dedicated `#organization` Organization Studio is the primary editor. It
projects nested Organization Units, Position/Role definitions, typed
Person/System/Organization actors, effective-dated assignments, reporting
lines, and approval-route previews from the active organization's same physical
EDN partition. User, Agent session and OrganismWorker candidates are resolved
server-side and exposed only for the active organization.

A save is validated by the surface that owns the schema — `sheets.validate`,
`docs.validate`, `forms.validate` — after the payload has been rehydrated out
of its plain-JSON projection. The order is not an implementation detail: those
validators read namespaced keys, find none on a projected payload, and report
no problems, so validating before rehydrating would accept anything at all.
Warnings do not block; a `docs` document with no title is a draft, not a
rejected save. They are returned on the save response rather than dropped — a
warning that is computed and then discarded is the same as not having run the
validator.

Every save is a new version under a new object reference, and every version is
counted against the quota: `drive.workspace/add-version` adds and nothing
subtracts. `drive.workspace/trash` only sets a flag, so trashing frees
nothing. `documents/purge!` is the one call that does, it refuses anything not
already in the trash, and the Drive shows the trash and the quota together
because otherwise a Drive that fills up cannot say why.

### Going back to an earlier version

Every save has been a new version since the beginning; nothing could put one
back. The pane could open version 3 and leave you to save it yourself, which
is a restore only if you know that is what you are doing.

A restore is **a new version whose contents equal an old one**, not a
rewrite. The history is append-only, so restoring version 2 of four leaves
five — and the author recorded is whoever restored it, not whoever wrote it
the first time. They made this version; the earlier one is still there
saying who made that.

It goes through `write-resource!` like any other save, so the validator sees
it. A surface's rules can have tightened since, and silently reinstating
something the model would now refuse is how a document becomes unopenable by
the thing that owns it.

It carries an etag for the same reason a save does. Putting an old version
back on top of a change you have not seen is the lost update wearing a
different hat.

`documents/history` is the addressable form: newest first, each entry
knowing its own index and its size delta, which is what makes a column of
identical timestamps readable.

**A version index is a position, not an identity.** It is where the version
sits in `:drive/versions`, so pruning renumbers what is left — what was
version 3 becomes version 1. Everything that takes an index re-reads first,
so this is a property to know rather than a hazard to work around.

### Forgetting part of a history

`add-version` adds to the quota and nothing subtracts. Trashing frees
nothing and `documents/purge!` frees everything, so until
`drive.object/prune-versions` the only way to reclaim what a heavily-edited
document's past cost was to delete the document.

`documents/prune!` keeps the newest N and returns the rest to the quota.
Owner only and irreversible, like purge, and for the same reason: an editor
may change a document and still not destroy the record of how it got that
way. `keep-count` below 1 is refused by `drive` itself — the newest version
is the document.

**Nothing prunes on its own.** It would be easy to trim on every save and
never mention it, and that would mean the Drive quietly deleting history
somebody may be relying on, at a moment they did not choose, to solve a
problem they had not noticed. A Drive that fills up and says so is the
better of the two.

### One page at a time

The listing returned everything. `documents/page` returns `default-page-size`
(50) and a `:next-cursor`, nil at the end so a caller stops by being told to
rather than by asking again.

**A keyset cursor, not an offset**, and the reason is specific to this list:
it is ordered by last write, so saving anything moves that document to the
front and shifts every offset after it. Offset paging would then show one
document twice and skip another, silently. A cursor says where to continue
from, which stays meaningful however the list moves — a document that jumps
to the front is seen again at the top rather than lost from the middle. There
is a test that saves the oldest document between two pages and checks nothing
is seen twice.

**`:limit` bounds the response, not the work.** Every workspace is still
scanned and sorted, because a grant is recorded on the item rather than
anywhere central and there is no index to consult. When the scan itself needs
bounding the fix is the same index that would fix search.

**Searching twice does not read the bytes twice.** Extracted text is cached
by object reference, and content addressing is what makes that provably
safe: for an uploaded file the reference *is* the content, and for a document
`write-item` mints a fresh reference per version and refuses to reuse one for
different content. Either way the same reference cannot ever name different
bytes, so there is no invalidation to get wrong — an edit is a new reference
and therefore a new entry, and the old one is simply never asked for again.

It is not the index. It does not bound the first search, or a Drive larger
than the cache, and the scan still visits every readable document. What it
removes is re-reading bytes that have not changed, which is what a second
search does with all of them. Measured with a store that counts its reads:
the first search reads them, the second reads none.

The cache is bounded at 2000 entries and drops half of itself on overflow.
Which half is arbitrary — a hash map has no insertion order and this does not
carry one — so it is not an LRU and does not claim to be; the cost of being
wrong about which half is one read.

Only the created half is paged. The archive is eighty files that
`workspace/drive-snapshot` has already capped, so a second cursor for a list
that does not grow would be ceremony.

### Searching inside documents

The Drive could filter a list of names. What a cell says, what a paragraph
says, what is written on a slide — none of it was reachable except by
opening the document.

What counts as text is the model's business and lives with each surface as
`:text` in `documents/kinds`, next to `:vocabulary` and `:problems`.
Searching is the app's, because only the app knows which documents this
principal may read — and a search reaches exactly as far as a listing does,
so it cannot be used to learn that a document exists.

A title match wins over a content match and a document appears once. The
snippet is cut to a window around the hit and quotes the *document's* casing,
not the query's: echoing the query back would be quoting something the
document does not say.

**It reads every readable document's bytes on every search.** No index, so
nothing can be stale and nothing has to be rebuilt — linear in the size of
the Drive, which is the right trade for one household's documents and the
wrong one for an organisation's. When it stops being right, the fix is an
index keyed on the object reference, which already changes on every save.
The UI debounces at 300 ms for the same reason: a request per keystroke
would be a scan per keystroke.

### Import and export

Three formats, and only one of them is new code here.

- **CSV**, from `sheets.csv`, which gained it for this. One tab in or out,
  because that is what the format can carry.
- **PPTX**, from `slides.pptx` and `slides.office`, which have had it all
  along without the Drive ever offering it.
- **EDN**, which is free: the stored bytes already are the EDN envelope, so
  exporting one is handing over what is on disk. That makes every surface
  exportable, including the two with no office format at all.

`documents/export-formats` is keyed by surface, so asking a document for a
format it has no writer for is refused by name (415) with the list of what it
does have, rather than producing something empty. The Drive offers exactly
those buttons, from the same table.

An import **creates** rather than replaces. Importing into an existing
document would be a save, and a save has an etag; an import has a file and no
idea what it is landing on top of. It lands through `create!` and then
`write-resource!` — the same path a save takes — so quota, ACL, versioning
and the surface's own validator all apply to it. An imported deck that is not
a deck is refused with the same code as a typed one.

xlsx joined them, from `sheets.xlsx` on `ooxml` — the same division
`slides.pptx` uses, and no `spreadsheetml` repository, because `ooxml`
already knew what an xlsx was and what was left fitted in one namespace.
Every cell is written as an inline string: refusing to guess on the way in
and then guessing on the way out would be the same mistake arriving late.

xlsx reads as well as writes now, on `kotoba-lang/xml`. Reading meets more
shapes than writing chose — shared strings, bare numbers with no `t`,
formulas carrying the value Excel last calculated — and the formula wins
over that value, because the formula is what the document says.

**Bytes that are not the file they claim to be are refused before anything
is created.** Neither office reader can be asked: `slides.office` builds a
deck with one empty slide from an empty graph, and `sheets.xlsx` reads a zip
with no worksheets as a workbook with no tabs. Measured — three bytes of `x`
imported as pptx produced a one-slide deck and as xlsx an empty workbook,
both reported as successes. The package is what can be asked, so
`require-office-package!` checks for a `ppt/` or `xl/` part first.

**A dated cell arrives as a date.** Excel has no date type: a date is a
number counting days from an epoch, and what makes it a date is the format
its style points at. Reading one means reading `xl/styles.xml`, so an
invoice imported here shows `2023-03-15` where it used to show `45000`.

This is the one place the import converts rather than passing text through,
and it converts on what the document says rather than on what the value
looks like. The amount next to that date — `120000`, a perfectly plausible
serial — stays a number, because nothing formatted it as a day. Three other
things decline for the same reason: a shared string under a date format is
still a string, a word under one is still that word rather than
1899-12-30, and a number under `0.00"円"` is currency, whose only letters
are inside a quoted literal.

Two smaller things came out of the same work. The 1900 leap bug is honoured
— Excel believes in a 29th of February 1900 that did not happen, so serials
above 60 sit one day further along than the arithmetic says. And 1904
workbooks are asked rather than assumed: the two systems are 1462 days
apart, which reads as a plausible date rather than an error.

What is still not read is anything about appearance — fonts, fills, widths,
merges — because nothing in the sheets model can hold them. There is still
no docx.

### A document can leave

`docs` could only be exported as EDN, which is the same as not being able to
leave: the format is exact and nothing else reads it. `docs.markdown` writes
one as Markdown and reads one back, so a memo written here can go into a
mail, a repository, or anything that opens text.

**What it cannot carry is named before it is dropped, not after.**
`docs.markdown/unexpressed` answers what *this* document will lose — block
ids, comments, suggestions, a style Markdown has no syntax for, a table cell
that is not text, a Drive reference — shaped like `docs.validate/problems`,
so the pane renders it with the code it already has. `content` carries it as
`:export-warnings`, keyed by format, so the warning can sit next to the
button that causes it.

That warning is shown in two pieces for a reason. The static line — Markdown
does not keep ids, comments, or every style — is true of every document, so
it costs nothing and is there before anything is opened. The document's own
list needs its bytes, and the detail pane is rebuilt on every keystroke in
the search box, so fetching it per render would be a request per keystroke.
It appears once the document is loaded.

Two asymmetries in the conversion are deliberate. `write` spells a bold run
as `**`; `read` does not turn `**` back into a run, because the asterisks in
a pasted document may be asterisks and a wrong range puts the bold in the
wrong place — a round trip loses styling rather than inventing it. And a
table cell holding `120` comes back holding `"120"`, because reading it as a
number would be the guess `sheets.csv` refuses.

Unlike pptx and xlsx there is nothing to refuse on the way in: every byte
sequence is valid Markdown. What `require-office-package!` does for those,
the parser does here by never throwing — junk becomes the nearest blocks and
the validator is what reports the document, because a parser that threw
would turn a bad paste into a 500.

### Word

`docs.docx` writes a document Word opens, and reads one back. It is one
namespace on top of `ooxml`, the same as `slides.pptx` and `sheets.xlsx` —
`ooxml/package-kind` already returned `:docx` for a `word/` prefix.

**Structure rather than appearance.** A heading is a paragraph carrying
`w:pStyle Heading1`, not bold 18pt text; a list is `w:numPr`, not a line
beginning with a hyphen; a table is `w:tbl`, not aligned spaces. Word renders
both the same way and only one of them can be read back as a heading,
collapsed into an outline, or restyled by whoever receives it. That is why
`styles.xml` and `numbering.xml` are written: a style id referring to nothing
is a paragraph with no style, and a `numId` with no entry is a list Word shows
with no marker at all.

Junk bytes are refused by `require-office-package!` looking for a `word/`
part, for the same reason as pptx and xlsx: the reader answers an empty
document for anything it cannot parse, which is right for a reader and
indistinguishable from a working import of an empty file.

**What docx does not carry is the same list Markdown does not, plus one
more.** Block ids, comments and suggestions have nowhere to go. And the
writer ignores `:docs/text-runs` entirely — Markdown at least spells bold and
italic, and this does not spell any of them, so a styled run goes out plain.

### What every format will drop, before it drops it

`export-warnings` asks a table of `[surface format] → fn`, so the formats
that *can* answer are one line each and the ones that cannot are visible as
absences rather than as an unstated assumption. All five answer now — `docs.markdown`, `docs.docx`, `sheets.xlsx`,
`sheets.csv` and `slides.pptx`. EDN is the one absence and is not a gap: it
is the stored bytes, so there is nothing for it to drop.

**CSV's first entry is the one that surprises people.** A CSV is one table,
so every other tab is left behind — unlike the rest of any of these lists,
that one is most of the document. It also reports that a formula goes out as
`=SUM(B2:B9)` rather than as what it comes to: Excel re-evaluates on open,
which is why it is written that way, and a reader that is not a spreadsheet
gets the formula. Both are defensible and only one can be written, so the
choice is reported rather than argued.

**PPTX's list has one entry, and that is the finding.** Checked against
every constructor the slides model has: text boxes, rectangles and images
travel with position, fill, weight and size; notes become a notesSlide part;
the deck's title becomes `docProps/core.xml`; the theme becomes a theme
part. The exception is a *slide's* title, which is written nowhere.

That one is a label rather than content — `slide` defaults it to the slide's
id and `slides.office` generates `Slide 1 · source` when reading a file that
has none, so rendering it would put `s1` on every auto-named slide. So it is
reported rather than rendered, and the editor's field is now called
スライド名 rather than 見出し: calling it a heading promised text that never
appears, on the slide or in the export.

Keying by format rather than reporting one set of losses per document is not
tidiness: the lists differ. A bold run is spelled by Markdown and dropped by
docx, so the same paragraph produces a warning under one button and not the
other.

Each surface writes its answer in its own namespaced shape — `:docs/severity`
here, `:sheets/severity` there — because it belongs beside the writer that
does the dropping. The app flattens them to one shape so the pane renders all
of them with the code it already has.

Block ids are dropped by both text formats and reported by neither. Every
export drops them on every document, so an entry would appear on everything
and mean nothing; the docstrings say it instead, and a test pins the silence
so it stays a decision rather than an omission.

`xlsx` named three losses and now names one and a half. Named ranges are
written. **Cell styles are written and read back** — weight, slant,
underline, alignment and a number format — which was the largest of the
three: a spreadsheet imported with a bold header row came back plain, and
exporting it again lost the formatting for good.

A style is two levels of indirection, cell → `cellXfs` entry → font and
`numFmt`, which is what made it the largest. A writer that emits a
`<font b="1"/>` and nothing else produces a file Excel opens with no bold in
it, because nothing pointed at the font.

What is left of a style — a colour, a border, a font family — is reported
**by key** rather than as "the style is dropped", so the report says what is
actually lost. Charts reach Excel now too, so what `xlsx` still reports is one thing and a
half: the parts of a style it has no element for, and a named range or chart
pointing at a tab the workbook does not have.

### A chart you can see

`add-chart` was in the model from the start and nothing could draw one, so
`unexpressed` reporting charts as dropped was the whole of their existence.
`sheets.chart` draws SVG, and the Drive carries it beside the resource as
`:charts` — the same reason `:computed` sits there rather than in the
payload: the payload is what a save sends back, and a picture in it would
return as part of the document.

It plots what a formula comes to. A chart over a column of `=SUM(…)` should
plot the totals, and reading `:sheets/value` would plot nothing, because a
formula cell has none.

A chart whose range holds no numbers is **listed and not drawn**, and the
pane says why. Empty axes read as *there is no data here*, which is the
wrong answer when the range is simply wrong.

And it reaches the `.xlsx`: a chart there is four parts and a chain of
relationships — sheet → drawing → chart → cells — and a link missing anywhere
gives a file that opens with no chart and no complaint. The series names its
cells *and* caches what they hold, because the reference is what Excel
recalculates from and the cache is what every other reader draws.

Only a chart naming a tab the workbook does not have is still dropped: there
is no sheet for its drawing to sit on.

**And there is a way to set one.** The style bar acts on the cell the cursor
is in, which is what a spreadsheet does; there is no multi-cell selection
here, so it names the cell rather than leaving that to be guessed. It offers
exactly the five things that reach Excel and says so.

Two things it exposed. The grid replaced a cell's whole map on every
keystroke, so **a bold header stopped being bold the moment somebody
corrected a typo in it** — the style is the cell's, not the value's.
And emptying a styled cell now leaves the formatting, because formatting is
applied to the box and not to what was in it; a style-only cell is
storable, writable and readable, which it was not before the last change. What it deliberately does
*not* name is a formula written without a cached value — that is Excel
recalculating on open, which is the format working.

### Folders, and why the trash is a question rather than a flag

`drive.workspace` had `create-folder`, `:drive/parent-id`, and an
`effective-role` that walked up the parents — so sharing a folder always
shared what was in it. What was missing was everything that reads the tree
back, and an application that ever called any of it. Every document lived at
the root.

**Trashing is derived, not cascaded.** Writing the flag onto every descendant
means restoring has to know which ones it wrote: a file already in the trash
before its folder went there would come back out, restored by a fact about
its parent. `trashed?` asks instead — is this item, or anything above it, in
the trash — so trashing a folder hides its contents because the answer
changes for all of them at once, and restoring reveals exactly what was
visible before because nothing else was ever touched.

The trash *listing* deliberately still reads the item's own flag. What was
put in the trash is one thing and restoring it is one act; listing every file
under a trashed folder separately would offer to restore each of them out of
a folder that is still in the trash.

**`purge!` used to remove the id from the root's children.** Everything lived
at the root, so it was right by accident. In a folder it would have left a
listing pointing at an item that is gone.

**An editor of a shared folder may create in it, and what they create
belongs to that Drive.** `ws/create-file` makes the creator the owner, which
is right in your own Drive and wrong in somebody else's: trash, purge and
re-sharing are owner-only, so a document bob owned inside alice's folder
would be one alice cannot remove from her own Drive. The Drive's owner owns
it; the creator is recorded as an editor, which is what they need to go on
working on what they just made.

The cost is stated rather than discovered: someone you gave write access to
can consume your quota. That was already true of *saving* a shared document —
every version is charged to the owner — so this widens who can start one
rather than introducing the hazard.

**Creating may cross Drives; moving may not.** `ws/move` rewrites one tree,
so a destination in another workspace would leave a parent id pointing at an
item that tree does not contain — a breadcrumb walking up out of the
workspace and a listing that never shows it again. `locate-folder!` resolves
across Drives for `create!`; `folder-parent!` stays inside one for `move!`,
and the two exist separately for that reason.

`move` refuses a folder into itself or its own descendant — a drag lands
where it lands, so an interface will ask, and the result would be a subtree
detached from the root, invisible walking down and unreachable walking up.
The library refuses in its own vocabulary, an ex-info with no `:type`, which
the server's status table cannot see and would answer 502 for; `move!`
translates it to `:drive/invalid-move` and 409, because an ordinary mistake
should not look like a broken server.

Moving a file into a shared folder shares it. That falls out of inheritance
rather than being implemented, and it is tested because it is a permission
change nobody performed.

**Purging a folder purges what is inside it, deepest first.** The order is
not tidiness: `trashed?` walks upwards, so a folder dropped before its
contents would leave them pointing at a parent that is not there, the walk
would end at a missing item, and the answer would be *not in the trash*.
They would come back into the listing — resurrected by the deletion of their
folder, and impossible to get rid of.

**The trash lists folders as well as files, or their contents can never be
reclaimed.** A file inside a trashed folder has its own flag clear, so
nothing lists it on its own; before this, a trashed folder appeared nowhere
and everything under it stayed charged against the quota for ever.
`empty-trash!` reports what was removed rather than how many things were
listed — purging a folder purges its subtree, so the two stopped being the
same number.

**Searching looks everywhere; browsing does not.** A search scoped to the
folder you happen to be standing in cannot find what you are looking for,
which is the only reason to search. So the folder strip says so while a
query is present, and the breadcrumb — which would be describing a place the
results are not from — is replaced by that sentence.

A document shared from somebody else's Drive has no folder in this one, and
`:parent-id` is nil rather than theirs: naming a folder this principal
cannot open would put an id in a breadcrumb that goes nowhere. Those
documents stay at the top rather than disappearing into a folder nobody can
reach.

The move picker offers every folder by path — `My Drive / 営業 / Q1` — because
two folders called Q1 are an ordinary thing to have and a picker showing both
as `Q1` asks an unanswerable question.

**The listing sorts by timestamp *and* id.** `cursor-of` builds a cursor from
both, so paging compares on a total order; a sort that stopped at the
timestamps left documents written in the same millisecond in whatever order
they came out of a hash map, and the two orders disagreeing means
`after-cursor` skips one document and repeats another. This surfaced as a
flaky test rather than a bug report — five documents created in a loop shared
a timestamp and one run in some number interleaved them. Anything that can
page in a different order between two requests can lose a row between two
pages.

### Responses are a table, and the table is not the document

A form collects a map per response, keyed by field id. Nobody reads them
that way. `forms.responses` turns them into the grid they are always read
as, and the Drive offers it two ways: CSV, and a workbook created beside the
form.

**Columns come from the form, not from the answers.** Deriving them from the
keys present in the responses loses a question nobody answered — the table
stops matching the form — and gives two responses with different keys rows of
different widths, so values slide sideways under headings belonging to other
questions. A blank is information; a shifted column is a lie. Answers to a
question since deleted are kept as trailing columns rather than dropped.

**This is the first export that is not the document, and the permission is
different.** Every other format writes what the document says, so
`readable!` is the right question. A form's CSV is what people told you, and
a viewer of a form is not entitled to it — nor is an editor, because editing
the questions is not owning the answers. `export-formats` carries
`:owner-only?` and `export` asks `owned!` when it is set; the kinds table
carries `:owner-only-exports` so the pane does not render a button that
refuses. Without that, a responses download would have inherited the
document's permission, which is the quietest possible way to hand over
everyone's answers.

`responses-sheet!` is what Google Forms means by sending responses to a
spreadsheet, with one difference said in the name, in the note on the screen,
and in the docstring: **it is a snapshot.** Keeping it current would mean
every submission writing a second document — a new version, charged to the
owner's quota, on a document somebody may be editing. Asking twice makes two
documents, because two days' answers are two things somebody may want and
replacing the earlier one would destroy a document the owner never asked to
lose.

The CSV goes through a `sheets` workbook rather than joining strings,
so the quoting is `sheets.csv`'s one implementation of it — an answer
containing a comma or a newline is ordinary, and a second escaping routine is
a second place to get it wrong.

### A page the browser can print

There is no PDF export here, and the reason is fonts: a PDF of a Japanese
document has to carry a CJK font inside it, and embedding one means shipping
a font file and a subsetter. **The browser already has the font.** So the
answer is a page it can print, and the reader's own *print to PDF* is the
export — with their fonts, their paper size and their margins, all of which
a writer here would have to guess.

`printable` renders every surface, because every surface is worth printing:
a document as its blocks, a workbook as **what its formulas come to** (a
printed sheet showing `=SUM(B2:B9)` is a printout of a spreadsheet rather
than of its numbers), a deck as one drawn slide per page, and a form as its
questions with a line to write on.

A file is not printable. It is bytes, and the browser can already open the
ones it knows.

The print CSS carries no web fonts and no colour: a printed page uses the
reader's own fonts, and a coloured background is ink somebody pays for. One
slide per page, and a heading is never left alone at the bottom of one.

### Files that are not documents

A Drive that could only hold the four surfaces could not hold a PDF, a
photograph, or a zip — which is most of what people put in one. `upload!`
takes arbitrary bytes and makes an item with a media type and no resource
kind. `content` refuses it by name rather than handing a PDF to the EDN
reader, which is what used to happen and produced *"unexpected character:
%"* as a 500.

**The reference is the content's PieceCID.**
`cloud.itonami.app.filecoin/piece-ref` computes it, and `drive` never has to
know what a PieceCID is because it lets the caller name the reference — its
own docstring said "a content hash, a uuid, a path" from the start. Two
people uploading the same file store one object.

That is the whole feature and it is also the whole hazard, because two of
`drive.object`'s safety rules assumed the uuid case:

- **`write-item` refused a reference already in use.** Right when the caller
  picks the name, impossible when the bytes pick it. It now allows reuse
  when the bytes are the same bytes — compared against what is stored, not
  taken on the caller's word, because a caller trusted to say "this is
  content-addressed" is trusted with exactly what the guard exists to not
  trust.
- **`forget-item` deleted every reference the item held.** With sharing, that
  destroys the other holder's file. It now takes `:keep-ref?`, and `purge!`
  passes one built by scanning **every** workspace — the other holder may be
  somebody else entirely, so a check scoped to one Drive would be correct
  exactly until two people uploaded the same file, which is the case content
  addressing exists for.

Both are tested through the Drive: purging one holder leaves the other
readable, purging the last one takes the bytes with it, and the same holds
across two Drives.

The quota is charged per item, not per object. Two items over one object
cost twice even though the disk holds one copy — which is per-item
accounting rather than disk accounting, and the number a person can act on.

**An uploaded file is served as `application/octet-stream`, whatever it
claims to be**, with `Content-Disposition: attachment` and
`X-Content-Type-Options: nosniff`. Bytes uploaded by one person and served
from this origin to another are stored XSS if the browser is allowed to
decide they are HTML: a `text/html` upload opened from the Drive would run
with this app's session.

**Except for a closed list of raster image types, which are served inline.**
There is no execution context in a PNG — a raster format cannot carry
script — and `nosniff` forbids the browser from looking for a second opinion
about what the bytes are, so a `.png` full of HTML stays a broken image
rather than becoming a page. The declared type is trusted only to *select*
from that set, never to label an arbitrary response.

**SVG is deliberately not on it.** It is an image everywhere except in the
way that matters: it is XML, it may contain `<script>`, and a browser runs
that when the SVG is a document rather than an `<img>` source. Admitting it
because it is "an image" is the mistake the list exists to not make. The
tests name it, along with `text/html`, `application/xhtml+xml` and a
mis-cased `IMAGE/PNG `.

The preview is a separate route from the download rather than a query
parameter on it, so the inline path cannot be reached for a `.html` by
adding `?disposition=inline`; it refuses anything `file-bytes` did not mark
inline, and carries `Content-Security-Policy: default-src 'none'; sandbox`
as well, so even a type that slipped through the list could load and run
nothing.

I originally wrote that inline preview needed a second origin. That is true
of arbitrary types and not of this closed set, and the earlier note was too
broad.

An empty upload is refused: its PieceCID is the CID of nothing, shared by
every empty upload anyone ever makes.

**Search does not look inside a file**, and skips it by asking rather than by
throwing. The extractors are per-surface and a file has no surface, so there
is nothing to read; `search` already caught the exception `content` raises,
but that catch was written for a document whose bytes are gone — which is
exceptional — and a Drive full of PDFs is not. Control flow through an
exception stops being noticed the moment it becomes routine. A file is still
found by its name.

### Make a copy

The operation a reader of a shared document actually needs. Until now the
only way to get an editable version of something shared read-only was to
export it and import it back — two steps, through bytes, losing the kind if
the surface had no office format. `copy!` takes `readable!` and not
`writable!`: a viewer may copy, and that is the point rather than an
oversight.

**Four things are deliberately left behind, and each would be a bug if it
came along.** The *grants*, because copying a document shared with five
people must not share the copy with them — it falls out of `create!` giving
the creator `:owner` and nobody anything, and is asserted anyway, since
getting it wrong is a silent access leak rather than a visible fault. The
*comments and responses*, because they are about the document somebody said
them about. The *history*, because a copy is not a fork of the past. And the
*quota*, which is the copier's: unlike editing a shared document, where the
bytes stay in the owner's Drive, these bytes are new and in the copier's.

**A copy has one version, and so does an import.** Both used to create a
seeded document and write over it, so every copied or imported document had
a first version that was an empty one nobody ever had — offered by the
history pane and restorable. `create!` now takes a `resource-fn` and
produces the document with its contents.

That change moved validation with it. `write-resource!` refuses a document
the surface rejects; `create!` did not, because it only ever produced a seed
and validating one would have been checking this file against itself. With
contents arriving at creation, leaving the check out let a broken `.edn`
import succeed — silent in the direction that looks like success, which is
how it was found. `create!` now runs the same check, and a refused import
leaves nothing behind at all rather than a seeded document.

The kinds table gained `:id-key`. `import!` read
`(if (= kind :slides) :slides/id :docs/id)`, so an EDN-imported form gained a
stray `:docs/id` and kept the original's `:forms/id` — a document that
internally still said it was the one it came from.

### The clock everything is ordered by

Versions, comments and the document listing are all ordered by
`store/now`'s strings, and the keyset cursor that pages the listing is built
from one. Two properties had to hold and neither did.

**No two may be equal.** Measured: `(str (Instant/now))` gave 18,848
distinct values for 20,000 calls — one call in seventeen collided with
another. A tie is an order nothing decides, so the same request could return
two documents in either order, and a cursor built from one of them could
skip a row or repeat it. `now` now steps a microsecond on a tie, so every
ordering is total by construction within the process.

**Comparing the strings must give the same answer as comparing the
instants.** It did not. `Instant/toString` drops trailing zeros in groups of
three, so about one timestamp in eleven hundred prints `…:00.123Z` instead
of `…:00.123456Z` — and `Z` sorts after `4`, so that one sorts *after* every
longer timestamp in its own second. The listing order is then the exact
opposite of the truth, rarely and silently. Measured over 500,000 samples:
446 printed with three fractional digits and one with none. `now` now
formats with a fixed six digits, so width never varies and string order is
instant order.

This is the likeliest explanation for an intermittent single-test failure
seen twice during this work and never captured — a few hundred timestamped
things per run, one in eleven hundred printing short, is the right order of
magnitude. It is stated as the likeliest rather than the proven cause,
because the failing run was never caught and the fix is justified without
it.

Timestamps written before this keep their own widths and compare with each
other exactly as badly as they always did; what stops is new ones joining
them. `documents` also sorts by id as well as timestamp, because two
processes do not share the atom that makes `now` monotonic — the clock is
the answer within one process and the tiebreaker is the answer across them.

### Tables and lists without the JSON editor

A document with a table could not be edited without dropping to the JSON
pane. That pane is a working escape hatch and a wall for anybody who has not
been told about it, and a table is not an exotic thing to put in a memo.

The model allows ragged rows, and the editor does not force them even: the
grid is drawn to the widest row, a shorter row has empty boxes at the end,
and typing in one fills that row out. A row nobody touches stays short —
what is stored is what was entered. The **writers** pad on the way out,
because a ragged `w:tr` draws with a torn edge in Word and a short row ends
a Markdown table early.

What the tests cover is the path the editor actually uses: the projected
payload, string-keyed, through `update!`. A table is a vector of vectors and
a list a vector of strings, which is exactly where a projection loses shape
if it is going to. An empty list and an empty table — what the editor
produces the moment somebody adds one — save and come out of both writers
without a broken file.

### A slide you can see

Slides sent `rect`, `image` and components to the JSON pane on the grounds
that position and fill are a canvas's job. They are — and a canvas is a
picture, which `slides.svg` now makes. Numbers for `x`, `y`, `w` and `h` are
worth typing once you can see what they move, so the pane draws the slide
and offers those fields beside it.

Inches, the unit the model measures in, so a number in the field and a
number in the picture are the same number. A blank or unparseable box is
left alone rather than written as `NaN`, which the renderer would fall back
on and the exporter would write as a shape of no size.

The preview is drawn **with outlines**, which a slide does not have: the
editor is moving boxes and their edges are what it is moving. `slides.svg`
draws them only when asked, so a preview elsewhere does not get them.

Components and any shape kind the renderer does not know still go to the
JSON pane. Their position could be edited, and moving a shape nobody can see
is worse than handing it over.

### Proposing a change you may not make

`docs.model` had `:docs/suggestions` from the start — `add-suggestion`, a
validation rule, and an entry in both `unexpressed` lists — and nothing in
this application ever created or applied one. The same shape folders were
in.

**A commenter may say what should change and may not change it.** That is
the whole of suggestion mode, and it is the division comments already draw:
`drive.workspace` says a `:commenter` may not write, so a proposal lives
beside the document rather than in it — writing one into `:docs/suggestions`
would be a write, which is exactly what the proposer does not have. A
*viewer* may do neither; that is `comment-roles`' existing line, not a new
one. I first wrote this feature as "a viewer may propose", which
contradicted a line this app had already drawn and which the tests caught.

**A proposal records what the paragraph said when it was made, and
accepting checks that it still says it.** Alice rewriting the paragraph
after bob proposed a change to it means bob's proposal is about a sentence
that no longer exists; applying it would discard her rewrite without anybody
seeing. That is the lost update this app refuses for saves, arriving through
a different door, and it is refused the same way — `:drive/suggestion-stale`
and a 409, with what it *was* and what it is *now* in the error. The listing
computes `:stale?` rather than storing it, because the paragraph may have
changed a second ago and a flag written at proposal time would be answering
a question about the past.

Accepting is an ordinary save: validated, versioned, and authored by
whoever accepted. They made that version; the suggestion still records who
proposed it — the same rule `restore-version!` follows.

Declining somebody else's proposal and withdrawing your own are one
operation. Refusing the second would leave a mistake on the page with no way
to take it back.

Only `:docs`, and only a block's text. Proposing a new block, a deletion or
a reordering is a larger surface, and offering half of it would leave a
reviewer wondering which half.

### A workbook can have more than one tab

Every other surface could add to itself — a form a question, a document a
paragraph, a deck a slide — and a workbook was stuck with the tab it was
created with unless somebody hand-edited the JSON. The same shape folders,
suggestions and named ranges were in: the model allowed it and nothing
offered it.

Removing the last tab is not offered. It would leave a workbook the editor
cannot show and the writer has to invent a sheet for, which is a state
nobody chose. A new tab takes the first free `sheetN`, not the count: in a
workbook whose first two tabs were deleted the count is 1, and reusing an id
silently replaces the tab that already has it.

The server half is what the tests cover, because it is what can be
checked here: a two-tab workbook survives the validator, each tab computes
against its own cells, both worksheets are written, and the pair comes back
whole through an xlsx round trip. CSV still takes one tab by name and says
so when asked for one that is not there — a CSV is one table.

`A1` means *this* tab's A1 and `原価表!A1` means that tab's — the first
half is what an unqualified reference must keep meaning now that the second
half exists, and both are tested through the Drive. A sheet that is not
there is `#REF!`.

The chain of cells being computed is keyed by sheet *and* cell. With one tab
those are the same thing; with two, `売上表!A1` and `原価表!A1` are both
`[1 1]`, so a cell-only key would call an ordinary cross-tab reference a
cycle and would miss a real one that goes out to another sheet and back.

### A spreadsheet that computes

A workbook could hold `=SUM(B2:B9)` and never compute one. `sheets.xlsx`
writes `<f>` with no cached value on purpose — Excel recalculates on open —
but inside this Drive the cell showed the formula's text for ever, in both
the editable grid and the read-only preview. A spreadsheet that shows you
`=SUM(B2:B9)` instead of the total is a picture of a spreadsheet.

`sheets.formula` evaluates, and the result travels beside the resource as
`:computed` rather than inside it. The payload is what a save sends back: a
computed value in there would return as something somebody typed, and the
formula that produced it would be gone.

Nothing is written back for the same reason — a stored result is a second
copy of something derived, stale the moment an input changes and afterwards
indistinguishable from a typed value.

**Cells hold text and that is not undone.** Evaluation parses text to a
number where a number is required and says so when it cannot. That is
Excel's rule and the reason for it: `=A1+1` over text is `#VALUE!` while
`=SUM(A1:A9)` ignores the text in the range, because arithmetic asks for a
number and an aggregate asks for the numbers there are — so a column of
amounts with a heading on top sums to the amounts.

Errors are values and propagate. A cell that refers to itself, directly or
round a loop, is `#CIRCULAR!` rather than a stack overflow.

**`IF` chooses before it computes**, which the first version did not.
Evaluating both branches makes `IF(A1=0,"未入力",100/A1)` come to `#DIV/0!` —
the error the guard exists to avoid — and a guarded division is the single
most common thing IF is used for. `AND`/`OR` are deliberately not lazy,
because Excel's are not either.

Beyond arithmetic: `COUNTIF` and `SUMIF` (with the optional third range, so
one column can be tested and another totalled), the text functions, and
`AND`/`OR`/`NOT`. Anything else is `#NAME?` rather than a crash.

**Named ranges resolve**, so `=SUM(売上)` works — and there is a panel to
define one, which there was not when the resolution shipped. The only way in
was the JSON editor: a working escape hatch, and not something a person
finds. A name is attached to the current tab's *title*, because that is what
a `definedName` references and what the evaluator matches on; its id would
resolve nowhere.

The panel does not validate. The surface does, and a range with no tab is
refused with `:named-range/invalid` — which is what a hand-edited payload
looks like, and better than storing a name that resolves nowhere. What the
test covers is the round trip the panel actually uses: the projected
payload, through JSON and back, where a nested map is most likely to be
quietly lost.

`sheets.xlsx` writes them, which is one of the three losses `unexpressed` used to report
turned into a non-loss. Only a name pointing at a tab the workbook does not
have is still dropped, because writing that reference produces a file that
opens with a broken name in it.

The evaluation goes through `workbook-values` rather than `values` per tab:
a name belongs to the workbook and a tab does not know which workbook it is
in, so tab-by-tab evaluation makes every `=SUM(売上)` a `#NAME?`. The tab a
name points at is matched by its **title**, which is what a `definedName`
references and what somebody defining a range writes — matching the map key
resolved names only in a workbook whose tabs happen to be keyed by their
titles, which is not the workbook this Drive creates. That was found by a
test here rather than in the library, whose own fixture used a tab where the
id and the title were the same string.

The grid shows the value and shows the formula while the cursor is in the
cell, which is what every spreadsheet does and the only way to see a formula
you are about to change. The preview shows the value with the formula in the
cell's title.

### Two editors, one document

A save carries the `:etag` of the version it was made from — the object
reference of that version, which `drive.object/write-item` guarantees is
unique per version. A save whose etag is not the current one is refused with
`:drive/stale-version` and a 409, naming who moved it.

This is a defect fix rather than a feature. Measured before the check
existed: alice and bob open version 1, both add a paragraph, both save — and
alice's paragraph is gone with the UI saying "saved". The bytes were still in
the history, which is not the same as anybody knowing to look.

A missing etag is refused too. A nil that meant "whatever is there now" would
be the old behaviour under a new name.

`rename!` carries no etag because it cannot be stale: it reads the current
resource itself, inside the lock, so what it writes is by construction based
on what is there.

**This is optimistic concurrency, not co-editing.** The second editor is told
to re-read and re-apply; nothing merges their work for them. Real
simultaneous editing means operations rather than whole-document saves, and
that is a different design, not more of this one.

### EDN at rest, JSON on the wire

The object store holds EDN. `documents/stored-envelope` is the same
four-key shape the office envelope has — family, version, resource kind,
payload — written with `pr-str`, so the bytes are still self-describing and a
reader still does not have to know in advance which surface it is holding.

The reason is what plain JSON cannot carry. `:sheets/type` left as
`"workbook"` and a cell address `[1 1]` left as the string `"[1 1]"`, so
every reader had to put them back — which is why there are four
`rehydrate-*` functions and why each had to learn not to throw on input it
could not convert. None of that is needed at rest: EDN is what the models
already are, what every validator reads, and what `store.clj` already writes
for the rest of this app's state.

**Rehydration did not go away; it moved to the one place it belongs.** A
payload arriving over HTTP is JSON because HTTP is, and `update!` converts it
on the way in. Nothing converts on the way out.

The client contract did not move with the storage. `content` returns
`:payload` as the same plain-JSON projection the editor has always been
given — `transit.core/write-json` of the EDN — alongside `:resource`, the
EDN itself, for callers inside this process.

Documents written before this are JSON. `decode-stored` tells the two apart
by their first character and rehydrates a JSON one on read, so an old
document reads as it always did and the next save rewrites it in EDN.
**Migration is what the Drive does as it is used, not something anyone runs.**
An item's `:drive/media-type` is corrected by that same save, so it says
`application/edn` once it is.

### Editing

Two views of one value. The Drive detail pane offers fields for the surface a
document is — questions for a form, blocks for a document, a cell grid for a
workbook — and the JSON underneath for everything the fields do not reach.
Both mutate the same projected payload, which is the object the versions
endpoint accepts, so a save does not care which produced it and neither is a
parallel format that can drift.

The vocabularies those fields offer (`forms.model/field-types`,
`docs.model/block-kinds`, `slides.validate/shape-kinds`) travel from the
libraries through `documents/kinds` to the page, so the editor offers
exactly what the validator accepts.

Slides is the one surface whose validator does not take the resource.
`slides.validate/problems` takes a *workspace* — it looks for items whose
kind is `:slides/deck` — and it also runs `route-problems`, which reports an
error for each of four Pages hosts it cannot find. That is a question about
the slides website, not about this document, and asking it here would refuse
every save. So the deck is wrapped in a workspace of its own and only
`deck-problems` is asked. Two things it cannot reach — a `docs` table or list, a
workbook with no tabs — say so and hand over to the JSON view rather than
editing part of a structure and leaving the rest.

These are not `app-sheets`, `app-docs` and `app-slides`, which are separate
applications on their own origin. Reaching those would mean widening
`connect-src 'self'` in the page CSP, which is a decision about what this app
may talk to and not one to make as a side effect of adding an editor.

### References between documents

`docs.model` has had `:table-ref`, `:file-ref` and `:deck-ref` blocks all
along, each carrying a `:docs/target` string, and nothing ever resolved one.
Four surfaces sharing a pane is not the same as four surfaces that know about
each other; this is the difference.

A target is a Drive item id — not a URL and not the `slides:intro-deck`-style
scheme the seed document in `docs.model` uses, which is a placeholder rather
than a format anything parses. An id is what `documents/locate` already
resolves, so **a reference obeys the same permission answer as everything
else**: a link to a document you may read resolves, and a link to one you may
not is indistinguishable from a link to nothing. Backlinks are filtered the
same way, so an incoming reference never tells you a document exists that you
were not shown.

Dangling and mistyped references are save-time **warnings**, not errors. A
document being written may name something that is about to be shared, and
`docs.model` names the kinds without saying a `:table-ref` must be a
workbook, so pointing one at a deck is reported and not refused.

The check belongs to the app rather than to `docs.validate`: the validator
sees a target string and has no way to know whether it names anything,
because what it could name lives in a Drive it does not know about.

Only `docs` documents carry references today. A workbook has no block that
names another document, and a deck's links live on a `slides` *workspace*
rather than in the deck — the envelope carries one deck, so there is nowhere
in it for a link to sit.

### Comments

`:commenter` was a grantable role backed by nothing — `can-write?` excludes
it, so a commenter could read and do nothing else, which is `:viewer` with a
longer name. Comments are what it means.

They are kept beside the document rather than in `docs.model`'s
`:docs/comments`, and the reason is a boundary rather than a convenience. A
comment written into the resource is a write to the document, and
`drive.workspace` says a commenter may not make one — correctly, since a
commenter who could rewrite content would be an editor under a quieter name.
The alternative is to perform that write as somebody who may, and since
`:drive.version/author` now records who wrote each version, that would file a
comment under the wrong name in the one record that says who changed what.

The costs are real: a comment does not travel with the exported envelope, and
`docs.validate`'s comment checks never see it. If comments must travel with
the bytes, the fix is a constrained-write operation in `drive` that a
commenter may reach — not a workaround here.

Anyone who may read a document sees its comments; anyone above `:viewer` may
leave one; its author or the document's owner may delete it. An editor may
rewrite the document and still not delete what somebody said about it.

**Threads are one level deep.** A reply to a reply joins the same thread,
because a conversation about one anchor is one conversation — and a tree
would let somebody resolve half of it. A reply takes its anchor from the
comment it answers: one that could point elsewhere would not be a reply.

**Resolution belongs to the thread**, and anyone who may comment may resolve.
That is wider than deleting on purpose: resolving takes nothing away and is
undone by unresolving, which is the reason deleting is narrower and does not
apply here. Replying to a resolved thread is refused — reopening is an act
somebody takes on purpose, not something a reply does on their behalf.

Deleting the start of a thread takes its replies with it. A reply to nothing
is not something a reader can make sense of, and leaving one behind so the
deletion looks smaller is not honesty.

### Answering a form

A form is the one surface with a second thing to do to it. Editing changes
the questions; answering does not, and an answer is not a version of the
form — writing one into the stored envelope would charge every response to
the owner's quota and change the document every respondent is reading from.
So submissions are kept beside the document, in app state, keyed by its id.

Whoever may read a form may answer it, including through a share link:
requiring write access to submit would make every respondent an editor of
the questions. The answers belong to the owner, and only the owner reads
them — an editor may change the questions and still not see the responses.

`forms.validate/submission-problems` is what refuses one, on a **rehydrated**
form. Against a projected payload `missing-required` reads `:forms/fields`,
finds nil, and reports that nothing is required, so an empty submission would
pass. There is a test asserting exactly that difference.

### A grant is a signed capability

A grant used to be `{principal-id role}` in this server's state: nothing
outside the process could check it, nothing expired, and the only evidence
that alice had let bob read a document was that this server said so.

A grant now also mints a CACAO (CAIP-74) — a SIWE statement naming an
audience, a `drive:<id>#<verb>` resource and an expiry, signed with Ed25519.
It can be verified without asking this server, it stops being true on its
own, and `cacao.core/verify-chain` is already there for re-granting.

**The expiry is real, not decorative.** `documents/honour-capabilities` drops
lapsed grants from the workspace before `drive` is asked anything, so
`drive.workspace/effective-role` needs to know nothing about capabilities and
every read is already filtered. The owner still sees the lapsed entry, marked
unverified — an owner re-granting without ever learning why is worse than one
who is told.

Grants made before this existed have no capability and are not filtered.
Retroactively expiring a share nobody was warned about would be the change
taking something away rather than adding something.

**What this does not do, and saying otherwise would be the whole lie of this
layer.** The issuer is the Drive's own key, not the granting user's, so the
Drive can mint any capability it likes. It is the Drive attesting "I let bob
read this, until then", verifiable by anyone afterwards — not alice proving
she chose to. Revocation is likewise still this server's word: the
capability is deleted with the ACL entry, and one already handed out would
still verify until its expiry.

The reason it is not alice's key is concrete. Her identity here is a
`did:key` derived from a WebAuthn P-256 credential, and WebAuthn signs its
own `authenticatorData || clientDataHash` with ES256; `cacao.core/mint`
signs a SIWE string with EdDSA. Making a user-issued CACAO possible means
teaching `org-chainagnostic-cacao` a WebAuthn signature type.

**This is authorization, not confidentiality.** The stored objects are
plaintext EDN and this server reads all of them — which is what
`documents/search` and every validator depend on. Encryption at rest, and
then per-document keys wrapped per member, are separate steps with a real
cost: an end-to-end encrypted document cannot be searched or validated here.

### Sharing

Each principal has their own `drive.workspace`, and a grant is recorded on the
item — which lives in the granter's workspace. So a grantee looking only at
their own Drive would be told the document does not exist, and a grant nobody
can act on is a button that does nothing. `documents/locate` is what closes
that: own Drive first, then a scan of the others for an item this principal
has a role on.

- **A version says who wrote it.** `:drive.version/author` is the principal
  `drive.object/write-item` checked against the ACL, not a value this app
  passes in — an author the caller names is a history the caller can write.
  Before sharing this was redundant; with two possible writers a history that
  cannot distinguish them is a history of nothing.
- **The owner's Drive is where the bytes stay.** An editor saving a shared
  document writes into the owner's workspace and is charged against the
  owner's quota. Writing it back under the editor would fork it into a second
  copy the owner never sees; charging the editor would let anyone fill someone
  else's Drive by accepting a share.
- **Editing and disposing are different rights.** `can-write?` does not
  distinguish them, so the app does: trash, restore, purge, and all sharing
  changes are owner-only. An editor who could re-share could widen the access
  the owner granted narrowly.
- **`:owner` is not grantable.** `drive.workspace/grant` would accept it, and
  two owners either of whom can purge is a transfer dressed as a share.
- **A link may read and never write.** `create-share-link` refuses any role
  but `:viewer` and `:commenter`, and `drive.object/read-via-share-link`
  checks trash and expiry itself. Redeeming a link still requires an app
  session: the server binds loopback-only, so an unauthenticated route would
  be the only one in the app and would serve nobody who could not already
  reach the port.

## Contracts

`GET /api/contracts` answers what is being paid for and what it costs to stop.
The app owns none of it. `kagi` owns the vault, which is end-to-end encrypted on
disk and in `kotobase.net` alike (ADR-2607170500); `kagitaba` owns the item shape
a contract lives in — a `Contract` section beside the login on the same item, so
the two cannot drift apart; `kaiyaku` owns the disclosed cancellation procedures
and the 縁 ledger they belong to. This app decrypts, joins, and renders.

`kagi.vault-read` is a read-only, non-prompting seam. It never writes the vault,
never asks a TTY for a passphrase (a server has none), and runs every reveal
through the same `kagi.operation` graph the CLI uses, so the AccessGovernor and
its audit ledger stay in the path. Enumeration reads metadata only, so the
contract screen decrypts membership items rather than the whole vault, and
sensitive field values are redacted before this app ever sees them — a
credential has no route into the response.

Three consequences are stated on the screen rather than left to be discovered:

- **The list cannot be queried server-side.** End-to-end encryption means the
  totals and the notice deadlines are computable only here, after an unlock on
  this device. Leaking amounts or dates in clear to make them queryable would
  undo the reason for the vault, so it is not done.
- **A locked vault is not an empty one.** `report` returns `nil` contracts, not
  `[]`, and the badge shows `—` rather than `0`. The three states — absent,
  locked, open — reach the UI intact.
- **Nothing here cancels anything.** The tier `kaiyaku` selects (T1 official API
  > T2 ToS-permitted browser > T3 self-submit) is displayed, and the disclosed
  notice period and penalty are displayed *because* they are the cost of leaving
  — never to be planned around. Every catalog entry stays
  `:operator-verified false` and says so on screen.

Money is per currency and never converted: JPY and USD are summed separately,
because adding them needs today's rate and would make "what am I paying" depend
on the day it was asked. A contract whose amount nobody recorded is counted as
`:unpriced`, not as zero.

## Artificial-organism workers

An organization can include an independently running artificial organism
through an `OrganismWorker` assignment. This is distinct from the ephemeral
background model runs in `cloud.itonami.app.worker`. Cloud Itonami projects the
organism's redacted activity and sends expiring typed intents, but does not own
its supervisor, memory, incarnation, or repository authority.

For Etzhayyim, the Tamaki repository AO runs under its existing local or
Murakumo supervisor and appears as an Etzhayyim worker. UI or network loss
therefore interrupts observation, not organism lifecycle. See
[ADR-0002](adr/0002-external-artificial-organism-workers.md).

The local management API exposes the active organization boundary:

- `POST /api/identity/organizations/accept` — accept a User-bound,
  one-time Organization invitation and select its Membership;
- `GET /api/organism-workers` — assigned AO directory;
- `GET /api/organism-workers/:id/snapshot` — safe current projection;
- `GET /api/organism-workers/:id/activity?cursor=…` — bounded cursor page.
- `GET /api/organism-workers/:id/receipts` — redacted admission/effect state;
- `POST /api/organism-workers/:id/intents` — enqueue an expiring typed intent;
- `POST /api/organism-workers/:id/intents/:intent-id/decision` — enqueue a
  human approval or rejection bound to its parent intent.

The activity adapter seeks directly to the append-only event byte cursor. An
initial request starts near the tail, and no request folds the complete Tamaki
history. Prompt, command, goal, private body, credential, and arbitrary event
data are excluded; only allow-listed lifecycle and runner metadata cross the
workplace boundary.

The cursor is persisted in the local Cloud state under the authenticated User,
active Organization, and Worker ID. Switching Organizations or AO workers
therefore cannot reuse another boundary's position. Event-file truncation or
rotation falls back to a bounded tail instead of leaving the observer stuck
beyond EOF.

Intent bodies do not enter Cloud's durable state or a public repository. The
local adapter atomically writes the complete envelope into Tamaki's private
`.tamaki/workplace/inbox/` and exposes only a digest-bearing receipt to the UI.
The UI says `admitted / not-executed` until the external supervisor emits an
effect receipt. Stop and approval intents additionally require the active
membership to be an Organization owner or admin.

## Runtime boundaries

The application window cannot call arbitrary remote URLs. It is an app-mode
window over the loopback web surface, and its content security policy admits
only that origin. The server selects a provider after policy evaluation. The
default configuration binds only to `127.0.0.1`, enables only Ollama, and
denies cloud egress.

```text
application window ── loopback only ──> web surface (jp-go-dds)
                                          │
                                   durable state
                                          │
                                   provider policy
                                     │          │
                                  local      cloud gate
```

There is no host mirror any more. Since 2026-08-11 `policy.cljc` executes the
shipped `resources/cloud/itonami/app/oracle/policy.kir.edn` through
`cloud.itonami.app.kotoba-oracle`, so the `.kotoba` is what decides and the
Clojure side only projects the two config maps into the four booleans the rule
asks about. `fleet_core` and `organism_worker` moved the same way.

`GET /health` is the first HTTP judgement on that seam (ADR-0038). The JVM
still owns the socket — amu's http-ingress kit is host-listen and its
`:native-aot` is pending — and the JSON body stays a host constant. Kotoba
admits the named route. A handler that kept the two string equals and dropped
the call would still 200, so the HTTP test inverts the artifact.

That is delegation through the KIR interpreter, not a tendered Wasm component:
the artifact is loaded and executed in-process, without a capability gate or a
supervisor around it. The cores are `kotoba/pure` — no effects, no
capabilities — so there is nothing for a tender to gate yet. Tendering becomes
the next hardening step when a core needs an effect, and this paragraph should
not be read as claiming it already happened.

The default test suite does run one native-crossable export
(`policy/loopback-host?`) as machine code, differentially against the same
compile's interpreter (ADR-0037). That is a canary, not the production path:
`kotoba-oracle/call` is not pointed at kexe artifacts. Native is a process
spawn, and the exports that take a record cannot cross a kexe boundary.

## MCP surface

`cloud.itonami.app.mcp` is one dispatcher with two adapters. `clojure -M:mcp`
keeps stdio for a process the operator launched. `POST /mcp` exposes the same
manifest over stateless Streamable HTTP, with bearer authentication, Origin
validation, protocol negotiation and RFC 9728 protected-resource discovery.
Externally issued tokens are introspected and audience/scope checked; local
opaque agent sessions remain usable for loopback clients.

The manifest includes fleet, session-gated tenant connections, and direct
tenant repository read/write/publish tools. Repository writes are CID-guarded,
storage-budgeted plaintext edits to the local projection; publish uses the
Kagi/DataLad/Kotobase encrypted pipeline. Approval remains browser Passkey-only
and is deliberately absent from MCP. See ADR-0004, ADR-0014 and ADR-0015.

The fleet capability gate is honoured, so `tools/list` is empty until it is
enabled — the same fail-closed default as the other agent capabilities. Browser
and computer tools are excluded because their approval path verifies the
frontmost application between approval and action, which cannot survive a
protocol whose consent model belongs to the client; the workspace reads are
excluded because they sit behind the Passkey session. See ADR-0004.

## API profile

The public compatibility slice is:

- `GET /v1/models`
- `POST /v1/chat/completions`

Management endpoints live under `/api` and are not part of the OpenAI
compatibility claim. Function-call deltas, Responses API, embeddings, Anthropic
compatibility are future profiles. MCP has separate stdio and HTTP compatibility
tests.

`POST /v1/chat/completions` honours `stream: true` as Server-Sent Events in the
`chat.completion.chunk` format: a role chunk, one chunk per provider delta, a
`finish_reason: "stop"` chunk, the usage chunk when
`stream_options.include_usage` asked for it, then `data: [DONE]`. Every chunk
repeats the completion id, which is the same id the store records for the turn.

The response headers are written on the first frame rather than when the
request arrives. Once `200` and `text/event-stream` are sent the status can no
longer change, so a provider refusal or a refused local model would have to be
reported inside a successful stream — where a client reads it as an empty
answer. Deferring means those failures reach the same `ex-data` status mapping
as a non-streaming request: a denied cloud provider is a `403`, under
`stream: true` as much as without it. After the first delta that is no longer
possible and the failure is an `error` frame instead, with no `stop` chunk
claiming the answer finished.

The first-party chat UI uses `POST /api/chat/stream`, a chunked NDJSON
management endpoint. Provider deltas are forwarded as they arrive; only a
completed assistant turn is persisted. A client disconnect or Stop action
therefore leaves the submitted user turn but does not record a partial
assistant message.

## Background worker runs

The Worker surface queues prompts that should outlive a single interactive turn.
A run is admitted by a fair semaphore (`:worker :max-concurrency`, default 2) so
background work cannot starve interactive chat of the local model, then streamed
through the same `service` path as chat — which means the fail-closed provider
policy applies to worker runs identically. There is no separate egress route.

| Endpoint | Purpose |
|---|---|
| `GET /api/workspace/worker` | Live queue: counts, per-run status, streamed output |
| `POST /api/workers` | Enqueue a run |
| `POST /api/workers/{id}/cancel` | Ask a queued or running run to stop |
| `POST /api/workers/clear` | Drop finished runs, keep active ones |

Runs are held in memory only, and this is a deliberate limit rather than an
oversight: `store/transact!` rewrites the whole state file on every change, so
streaming deltas through durable state would rewrite `state.edn` once per token.
The durable store instead receives one bounded `:worker/finished` event per run,
and each run's scratch chat session is dropped on completion — the run record
already carries its prompt and output, so keeping the session would only grow
`state.edn`. **Runs therefore do not survive a restart, and the UI says so.**

Cancellation is cooperative: the flag is observed at the next streamed delta, so
a provider that has stopped emitting can keep an in-flight HTTP request open
until its own timeout. Output is capped at 16,000 characters per run and marked
`truncated?` rather than silently trimmed.

## Persistence

`data/state.edn` is the durable local state. Each message is represented both
as ordered session data and three EAV datoms:

```clojure
[message-id :message/session session-id]
[message-id :message/role role]
[message-id :message/content content]
```

The ordered projection keeps chat reconstruction cheap; the datom projection
provides the Kotoba-native graph basis for later memory extraction and
relevance queries. Writes replace the state file atomically.

## Threat model

- Network exposure: non-loopback binding is rejected while
  `:bind-loopback-only?` is true.
- Accidental cloud fallback: no fallback chain exists. A remote provider needs
  provider enablement, the global cloud gate, and the explicit review-policy
  gate.
- Secret disclosure: config stores the environment-variable name, not the
  secret. Public state omits both.
- UI authority: the surface declares intent but owns no network or filesystem
  capability.
- Prompt retention: chats are stored locally by design. Deleting a session
  removes the ordered transcript; compaction/retraction of its historical
  datoms is not yet implemented and the UI does not claim secure erasure.

## Next slices

1. Tender the provider policy Wasm in the live request path.
2. Add tool manifests with per-working-folder capabilities and approval
   receipts.
3. Add an MCP **client** profile. The server half now supports stdio and hosted
   Streamable HTTP; see ADR-0004 and ADR-0015.
4. Add memory distillation and relevance retrieval over kgraph.
5. Add schedules/watchers after tool isolation is available.
6. Add a function-call compatibility suite. (Streaming has one:
   `test/cloud/itonami/app/openai_compat_test.clj` reads the SSE frames over
   real HTTP, in both modes.)
