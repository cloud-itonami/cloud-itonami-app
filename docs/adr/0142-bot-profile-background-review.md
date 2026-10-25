# Bot profiles and post-conversation learning

Bot persona, user preferences, memory and procedures belong to each immutable
Bot/owner/organization tuple. The app stores revisioned documents and projects
SOUL.md, USER.md, MEMORY.md and skills/<slug>/SKILL.md into its private
bot-profiles directory. These are not files in the user's project checkout.
The durable profile is authoritative; the Markdown projection can be regenerated.
The profile tools and conversation UI read the same revision. Updates compare the
expected revision and retain twelve prior revisions with host-provided provenance.

Existing Hermes imports seed the matching persona and memory documents without
changing source profiles. Source archives and other imported context remain
available. Existing app learned-skill candidates and their execution validation
remain separate from Markdown procedures: a review does not prove execution.

After direct, goal or group conversation turns, the host may enqueue one
idempotent background review for each turn and Bot. A single review worker uses
the Bot's admitted provider through the normal model dispatcher. The bounded
conversation snapshot and current profile are evaluated in a separate model call;
it cannot use tools, contact anyone, mutate project files or queue another review.
It returns up to four document updates or an explicit no-op. Invalid output,
revision conflicts and provider failures retain the prior profile and a failure
receipt. The next normal conversation receives the current profile. Completed
reviews do not append artificial replies to the user's conversation.

This follows Hermes's post-turn review pattern, using the Itonami host and storage
rather than importing the Hermes Python runtime. It does not update model weights.
Profile documents are descriptive context, not tool grants or approval. Persona
changes must follow explicit human corrections; user preferences must be grounded
in human statements. The reviewer is instructed to preserve useful content and
label untested skills honestly. Credential-shaped updates, unsafe file names,
oversized batches and symlink targets are rejected by the host.

Pending reviews survive restart. Lost in-flight reviews are marked interrupted
and not silently replayed. Failed reviews are visible beside profile documents.
The feature can be disabled via bots.self-improvement.enabled=false. A queue cap
of 64 pending reviews bounds load; review model requests have a 120-second timeout.
Profiles for existing Bots are provisioned in batches during resident ticks.

Validation covers imported persona preservation, file materialization, owner
isolation, stale revision refusal, path/credential rejection, review deduplication,
actual background update-to-next-prompt reuse, and invalid-review non-mutation.
