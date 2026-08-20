# ADR 0010: User testing is business evidence, not an agent opinion

Status: accepted

## Decision

Cloud Itonami consumes `kotoba-lang/user-test` as the shared EDN/CLI/MCP
contract and adds only host responsibilities: organization scoping, Business
joins, local persistence, browser capability gates and issue-shaped events.

A study belongs to exactly one Business. Its project id is forced to that
Business by the adapter. A participant may be human, synthetic or a deterministic
recipe, but the three result populations remain separate. Browser execution is
off by default and requires both `:user-test :execution-enabled?` and the existing
Agent Control browser gate.

Raw persona content, transcripts, screenshots, video and browser events do not
enter `state.edn`, Radicle or a public repository. They live in the operator's
private encrypted evidence store. Cloud Itonami persists opaque references,
content hashes, deterministic scores and issue-shaped findings.

The loop is:

```text
Business objective -> Study -> next plan -> participant/browser host
 -> observed Run -> deterministic evaluation -> finding/event
 -> issue/worktree/PR -> replay the same study against the new revision
```

An AI participant cannot evaluate its own success. A qualitative model judge may
add a finding but cannot overwrite task outcome, elapsed time, dead ends,
accessibility violations or evidence hashes.
