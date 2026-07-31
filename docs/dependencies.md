# Dependency policy

Runtime dependencies use immutable Maven versions or Git SHAs. The release
map in `deps.edn` must be resolvable from a fresh clone; sibling `:local/root`
entries belong only in the `:dev` override.

## Kotoba dependencies

| Repository | Purpose | Pin |
|---|---|---|
| kotoba-lang/kotoba | kgraph and Kotoba runtime | tag `v0.6.29` |
| kotoba-lang/shell | desktop and EventKit host | immutable SHA |
| kotoba-lang/calendar | calendar model | immutable SHA |
| kotoba-lang/mail | mail model | immutable SHA |
| kotoba-lang/drive | drive model, ACL and the object-store boundary | immutable SHA |
| kotoba-lang/sheets | workbook model and office envelope | immutable SHA |
| kotoba-lang/docs | document model and office envelope | immutable SHA |
| kotoba-lang/forms | form model and office envelope | immutable SHA |
| kotoba-lang/slides | deck model and office envelope | immutable SHA |
| kotoba-lang/transit | the office envelope itself | immutable SHA |
| kotoba-lang/identity | directory and identity model | immutable SHA |
| kotoba-lang/oauth | OAuth request model | immutable SHA |
| kotoba-lang/com-github | GitHub Projects adapter | immutable SHA |
| kotoba-lang/agent | bounded AgentRun state machine | immutable SHA |
| kotoba-lang/hil | portable human-approval contract | immutable SHA |
| kotoba-lang/wallet | EIP-4361 message and secp256k1 signature verification | immutable SHA |
| kotoba-lang/bitcoin-node | backend-neutral watch-only node contract and hardened Bitcoin Core adapter | immutable SHA |
| kotoba-lang/org-anthropic-mcp | portable MCP manifest and JSON-RPC dispatcher | immutable SHA |
| kotoba-lang/jp-go-digital-design-system | DADS UI components | immutable SHA |

`transit` is named directly even though `sheets`, `docs` and `forms` each
bring their own, and even though all three now agree on it. They did not:
sheets and docs pinned `77e3ce7d`, the last commit before the office wire
moved from Transit-tagged JSON to plain JSON, while forms pinned the current
one because `:forms/form` is only an admitted resource kind there. Resolution
picked a winner without being asked. Naming it here means that does not happen
again silently.

`slides` is much heavier than the other three: it is a Pages application as
well as a model, so it brings `office`, `ooxml`, `drawingml`,
`presentationml`, `office-style`, `canvaskit`, `css`, `xml` and the kotoba-ui
stack. This app requires three of its namespaces — `slides.model`,
`slides.wire`, `slides.validate` — and none of the rest. The cost is fetch
time and classpath length, not code that runs.

`transit` is now used for one thing only: projecting EDN onto the wire.
Storage is EDN (see the architecture note), so the `rehydrate-*` functions in
`sheets`, `docs`, `forms` and `slides` are reached on the way *in* — a
payload arriving over HTTP — and never on the way out.

A payload read back from an envelope is plain JSON — string keys, `"text"`
where `:text` went in — which is what `transit.core/read-office-envelope-body`
documents. Each surface now ships a `rehydrate-*` that undoes it, and this app
calls it before validating anything, because the validators read namespaced
keys and report no problems at all on a payload that has none.

The umbrella `kotoba-lang/authentication` dependency is intentionally absent.
It currently contains workspace-relative transitive dependencies and the app
only needed its email normalization helper. Keeping that small validation
locally avoids making a standalone clone depend on an unrelated authentication
bundle.

## License gate

Before distributing a bundled binary, release engineering must confirm license
and notice metadata for every resolved dependency. Several Kotoba repositories
are public but do not yet expose GitHub-detectable license metadata. Public
source availability is not treated as a redistribution license.

Updating or assigning licenses in those independent repositories requires an
explicit decision by their copyright holder and is not performed by this app.
