# Oppai native Bot connection

The sign-in-only SCI resident does not implement `/api/agent-bots`. Package the
full native Bot server using `scripts/package-controller.py`. The generated JVM
adapter preserves canonical CLJK sources and their dependency pins. It supports
new CLJK filenames, Kotoba export metadata, the documented JVM exception class
boundary and persisted full EDN sets. The portable web resource loader retains
Node behavior and uses classpath resources on the JVM.

Before activating against an existing owner store, fence the previous writer and
back up its state and configuration. Use `:bots {:recover-on-start? false :tick
{:enabled? false}}` for the initial connection so historical checkpointed jobs
are not silently replayed. This is a controlled connection, not a resumption of
the entire fleet. The default startup recovery behavior remains unchanged.

Run `test/native_resident_smoke.cljk` with the packaged classpath and a fresh
`-Dcloud.itonami.data-dir=/private/tmp/itonami-native-smoke-NAME`. It requires real
HTTP responses from the complete server and checks the shared page assets.

`scripts/run-native-resident.sh` takes explicit release, data directory, Java
runtime and the existing scoped Murakumo key path. It never loads OpenRouter
credentials. Disable every non-Murakumo provider in the owner configuration.

Use the existing Hermes import preview/stage/provision agent API to connect the
Producer profile. Keep carry-over permissions false: story planning and review
can run in the native Bot without a new publication grant. Image generation,
native vision review and publication continue through the existing receipt-locked
Producer CLI/MCP workflow. Creation of human sessions and owner grants remains
behind the application's human authentication boundary.
