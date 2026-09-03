# ADR-0086: A model assignment names a task this application actually calls

Status: accepted, 2026-08-31

## Context

This application calls a model from four places, and they are not the same
work: a Bot's own turn, a room where Bots answer each other, the loop that
drives this machine, and the plain chat surface. Until now all four read the
same two configuration keys. "Use the cheap model for the room rounds" was not
a sentence anybody could say.

Hermes Agent — the same product ADR-0073 took the scheduled-job pattern from —
solves this with two surfaces on one screen. **Applies to** scopes a
provider/model pair to the default or to one profile. **Auxiliary models**
lists the helper tasks that run on the main model by default and lets each be
pointed somewhere cheaper. Its list is vision, compaction, title generation,
skill search and a dozen more.

Copying that list would have produced a screen offering to route a vision model
in an application that never analyses an image. `bots.clj` was written to end
exactly that failure — a connector registry describing 37 tools that no code
path could run — and reproducing it one level up in a settings screen is not an
improvement.

Two further things were measured while wiring this, and both are older than
this change:

- an empty model turn — no prose, no tool call — was recorded as **completed**,
  and an empty message was appended. A run that did nothing and a run that
  finished were the same row, in the audit trail and in the one-line preview the
  Bot picker shows.
- a model proposing the same call with the same arguments ran until the tool
  budget ended it, and the budget then reported `:continuation-budget-exhausted`
  — a true statement that sends a person to raise a limit that was never the
  problem.

Hermes carries a guard for each (`empty_response_guard`, `repetition_guard`).
Both are decisions about when a bounded loop has stopped being a loop, so both
belong in the decision core rather than in an `if` inside the host.

## Decision

### The mechanism is reproduced; the task list is measured

`model_routing_core.kotoba` decides routing precedence.
`cloud.itonami.app.model-routing` names the tasks, and the set is exactly the
model calls this application makes:

| task       | what it is                       | where the assignment is read |
|------------|----------------------------------|------------------------------|
| `:bot`     | a Bot's own turn                 | `bots/provider-choice!`      |
| `:room`    | Bots answering each other        | `bots/group-send!`           |
| `:machine` | the loop that drives this laptop | `agent-control/create-run!`  |
| `:chat`    | the plain chat surface           | `service/chat-route`         |

The right-hand column is checked rather than documented:
`every-auxiliary-task-names-a-function-that-exists` resolves each one, so a
rename that leaves the table behind fails the suite. Adding a fifth task means
adding a fifth model call, and the two are one commit.

`:bot` is main and the only task with a per-Bot scope, because it is the only
one that has a Bot — a room round is many Bots at once, the machine loop belongs
to the workstation, and the chat surface predates Bots.

### One source of truth per Bot

A Bot's own pair stays on its record (`:bot/provider-id`, `:bot/model`), where
this application has kept it since Bots existed. Only the deployment-wide rows
are new storage, and `model-routing/index` drops a Bot-scoped `:bot` row if it
ever sees one. Two places a Bot's model can be written is the state where one is
stale and neither screen says which.

`public-bot` now reports both the effective pair and `:own-provider-id` /
`:own-model`. The effective values are filled in from three fallbacks, so a
screen reading only those cannot tell a Bot somebody assigned a model to from
one inheriting the default — and a settings screen whose every row looks
assigned has nothing worth reading on it.

### An unadmitted auxiliary override refuses

`auxiliary-route` returns `:refused`, not `:main`, when the assigned provider is
not admissible. Falling back reads as prudence and is the failure: somebody
assigns a free model *because* it is free, the review is later withdrawn, and
every round then runs on the expensive model while the screen still names the
free one. The bill is real, the belief is wrong, and nothing in the output
distinguishes them.

An assignment remains a preference and never a route around admission.
`policy/select-provider` decides whether a destination may be reached, first and
unchanged; this only says what to do when preference and admission disagree, and
the answer is never "pick something else and say nothing".

### The two loop guards

`bot_core.kotoba` gains `answer-empty?`, `may-nudge?` and
`repetition-exhausted?`.

An empty turn is asked once more and then refused as
`:provider/empty-answer` — once, because a dropped response is usually not
repeated and refusing the first turns a hiccup into an error somebody must act
on; only once, because a second is a pattern and a third spends the budget the
loop would have spent anyway while saying less about why it stopped. Blank prose
is empty prose.

The same call with the same arguments, proposed three times counting the one
about to run, fails as `:provider/repeating`. The call id is not identity — the
provider makes a fresh one each time — and argument order is not identity
either, since a model reissuing a call is not obliged to serialise its keys the
same way twice. An interleaved different call resets the count: a Bot
alternating between two tools is making progress, and refusing that would be a
worse failure than the one this prevents.

## Consequences

A deployment that never opens the screen keeps exactly the behaviour it had.
`resolve-main` returns `:provider` when there are no assignments, every
auxiliary task runs on main, and
`a-task-with-no-override-runs-on-main-unchanged` asserts the returned map is the
main one byte for byte.

Reasoning effort is still declared per provider in configuration and is not
user-settable. Hermes exposes it beside the model picker; this change does not,
and the screen does not pretend otherwise.

Both guards end runs that already ended. What changed is that the run now says
which of the two happened, so `:provider/empty-answer` and `:provider/repeating`
join the vocabulary a later reader of turn history can act on.

Verified by breaking each guard and confirming the failure matched its report:
inverting `auxiliary-route`'s refusal fails
`an-unadmitted-override-refuses-by-that-name`; renaming a task's `:source`
fails `every-auxiliary-task-names-a-function-that-exists`; disabling
`repetition-exhausted?` returns the loop to `:turn-budget-exhausted`, which is
the old answer named in that test's own message.
