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
| kotoba-lang/transit | the office envelope itself | immutable SHA |
| kotoba-lang/identity | directory and identity model | immutable SHA |
| kotoba-lang/oauth | OAuth request model | immutable SHA |
| kotoba-lang/com-github | GitHub Projects adapter | immutable SHA |
| kotoba-lang/jp-go-digital-design-system | DADS UI components | immutable SHA |

`transit` is named directly even though `sheets`, `docs` and `forms` each
bring their own. They do not agree: sheets and docs pin `77e3ce7d`, the last
commit before the office wire moved from Transit-tagged JSON to plain JSON,
and forms pins the current one because `:forms/form` is only an admitted
resource kind there. Resolution would pick a winner without being asked;
naming it means the answer does not change the next time one of the three
advances. The consequence is that a payload read back from an envelope is
plain JSON — string keys, vectors — which is what
`transit.core/read-office-envelope-body` documents.

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
