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
| Desktop lifecycle and input | `kotoba-lang/shell` native host |
| UI semantics | pure `kotoba:dom` surface program |
| Provider selection | safe `.kotoba` policy + host-side mirror |
| Local/cloud model transport | localhost service adapters |
| Session memory | `kotoba.kgraph` EAV datoms + durable EDN |
| Compatible client access | OpenAI-compatible loopback HTTP API; MCP over stdio for the fleet |
| Secret access | named environment variables at provider boundary |

## Workspace integrations

`GET /api/workspace` composes read models from authorities that already exist
in this checkout. The UI does not synthesize missing mail bodies, files,
projects, or events.

| Surface | Authority | Current contract |
|---|---|---|
| Inbox | `m365-archive` and `net-kotobase/mail-worker` | Lists archive metadata; sealed reception remains recipient-key controlled |
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

Creating and editing a document is the one mutation here, and it does not
write to any of the external authorities above: it writes to a
`drive.workspace` held in the app's own state and to an object store the app
owns. Mutation adapters that write back to OneDrive, GitHub Projects or
EventKit still require a later capability and approval design.

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
`:export-warnings` does not yet cover it: `docs.markdown/unexpressed` is the
only one of these functions that exists, and neither `slides.pptx` nor
`sheets.xlsx` has one either. Those are the same function waiting to be
written, not a claim that any of them are lossless.

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

The desktop process cannot call arbitrary remote URLs. It emits typed action
events to `bin/cloud-itonami-app-action`, which only calls the fixed loopback API.
The server selects a provider after policy evaluation. The default
configuration binds only to `127.0.0.1`, enables only Ollama, and denies cloud
egress.

```text
native window ── action event ──> fixed action adapter
      ▲                                  │
      │ kotoba:dom                       ▼
 pure app entry <── durable state <── loopback server
                                          │
                                   provider policy
                                     │          │
                                  local      cloud gate
```

The `.kotoba` policy compiles to a portable Wasm artifact. The Clojure host
mirror is intentionally small and covered by the same truth table. Moving the
actual server decision into a tendered Wasm component is the next hardening
step; the current host mirror is not described as if it were already tendered.

## MCP surface

`cloud.itonami.app.mcp` serves the fleet capability's two tools — `fleet_search`
and `fleet_call` — over MCP on **stdio**, launched as `clojure -M:mcp`. It is an
adapter: `cloud.itonami.app.fleet` already owns the descriptors and behaviour for
the in-app agent loop, and this translates them for a client that is not that
loop. `mcp.model` holds the manifest, `mcp.execute` does the JSON-RPC dispatch,
and an `ITool` port calls the same two functions.

Stdio rather than a route on the loopback server: `/v1/*` is already the one
unauthenticated exception the loopback bind exists to protect, and an MCP route
would be a second. Over stdio the client is a process the operator launched, so
nothing new listens and the trust boundary is one they already set.

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
compatibility, and MCP are future profiles and will receive separate
compatibility tests.

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
3. Add an MCP **client** profile. (The server half exists for the fleet
   capability: `cloud.itonami.app.mcp` on stdio — see below and ADR-0004.)
4. Add memory distillation and relevance retrieval over kgraph.
5. Add schedules/watchers after tool isolation is available.
6. Add a function-call compatibility suite. (Streaming has one:
   `test/cloud/itonami/app/openai_compat_test.clj` reads the SSE frames over
   real HTTP, in both modes.)
