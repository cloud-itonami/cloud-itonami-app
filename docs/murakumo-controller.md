# Murakumo controller migration

The control process can run on a Murakumo JVM host independently of the operator's Mac. Cloudflare Workers cannot directly run this JVM controller. The existing Worker bot runtime has a smaller tool vocabulary and is not a transparent replacement.

## Portable release

Run `python3 scripts/package-controller.py /absolute/new-release-directory` from a checkout with resolved Clojure dependencies. The package carries its ordered classpath, source commit, dirty flag, and SHA-256 file inventory. Missing optional tools.deps resource directories are explicitly recorded. No operator data directory, credential store, or environment is copied. Inspect the manifest before deployment; a dirty package is a staging artifact, not an immutable production release.

Run `run-controller.sh` with a dedicated `CLOUD_ITONAMI_DATA_DIR` and `JAVA_HOME`. Keep HTTP loopback-only and access it over an authenticated private transport. A public hostname needs a separately verified proxy and origin/authentication configuration.

## Cutover requirements

1. Use `CLOUD_ITONAMI_CONTROLLER_MODE=standby` with the controller-entry launcher and a separate port. This mode does not load app state, recovery or schedulers and rejects all non-health routes. Tick=false alone is insufficient because startup recovery can enqueue work. Health fingerprints identify paths, not stored content; verify contents by digest and IDs.
2. Inventory every Bot workspace, its required tools and secrets, pending AgentRuns, and browser/computer sessions. A local filesystem path is not a cloud mount. Do not mass-rewrite arbitrary transcript strings or copy the entire credential store.
3. Prepare explicit workspace mappings and scoped credentials. Configure Kotobase for production persistence. Test representative model, file, and tool operations on the destination.
4. Quiesce the source scheduler and drain/checkpoint in-flight runs. Copy a consistent snapshot plus journal and required workspace data while the source writer is stopped. Validate journal base digest and byte length.
5. Verify the destination preserves owners, grants, Bot IDs, and job/idempotency identifiers. Start exactly one scheduler only after the source is fenced off. Do not rely on process-local/file locks across two hosts.
6. Observe actual terminal outcomes and overdue backlog. Health alone is not cutover proof. Rollback requires stopping the destination first and preserving any new durable state before restarting the source.

## Observed staging, 2026-09-09

Murakumo node `judah`, private loopback port 1438, successfully booted the packaged controller. Staging store fingerprint `9b153b0fc76a` differs from the local production store `5a6c4a524837`. Staging scheduling is disabled and no production Bot state or secrets were copied. The 82 distinct workspace paths of the current fleet include operator-local directories and the superproject root; their migration remains outstanding. This is infrastructure readiness, not a completed Bot migration or proof of autonomous cloud operation.

## Scheduled observation

A Codex thread heartbeat checks migration and outcomes every 30 minutes, reporting meaningful changes only. `scripts/controller-status.clj DATA_DIR` provides a read-only aggregate snapshot, verifies journal SHA/size and completeness, and fails rather than returning healthy on invalid state. This Codex heartbeat is separate from the cloud controller and requires the Codex scheduler to be available.
