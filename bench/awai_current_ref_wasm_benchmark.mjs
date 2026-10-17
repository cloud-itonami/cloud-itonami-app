import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import os from "node:os";
import { pathToFileURL } from "node:url";
import { performance } from "node:perf_hooks";

const [wasmPath, runtimePath] = process.argv.slice(2);
if (!wasmPath || !runtimePath) {
  throw new Error("usage: node bench/awai_current_ref_wasm_benchmark.mjs <wasm> <amu-runtime/browser-host.mjs>");
}

const { instantiateKotoba } = await import(pathToFileURL(runtimePath).href);
const hosted = await instantiateKotoba(new Uint8Array(await readFile(wasmPath)), {
  allowCapabilities: []
});
const wasmBatch = () => Number(hosted.instance.exports.main());

const profile = {
  profileId: "awai-test-profile",
  chainId: "awai-test-chain",
  genesisHash: "test-genesis",
  validatorSetId: "test-validator-set",
  quorumPolicyId: "test-quorum-policy"
};

const proof = quorumPolicyId => ({
  trust: "amano-inga-verified-current-ref",
  decisionVerified: true,
  ...profile,
  quorumPolicyId,
  ref: "awai/domain/murakumo.cloud",
  headCid: "bafkrei-test-head",
  challenge: "test-challenge-123456",
  current: true,
  quorumReadVerified: true,
  currentReadProof: { trust: "test-quorum-read" },
  finalityProofVerified: true,
  finalizedEventCid: "bafkrei-test-head",
  finalityProof: { status: "finalized" },
  observedAt: 100,
  expiresAt: 200,
  signatureValid: true
});

const valid = proof("test-quorum-policy");
const quorumMismatch = proof("other-quorum-policy");
const referenceVerified = value =>
  Object.values(profile).every(item => typeof item === "string" && item.length > 0) &&
  value.trust === "amano-inga-verified-current-ref" &&
  value.decisionVerified &&
  value.profileId === profile.profileId &&
  value.chainId === profile.chainId &&
  value.genesisHash === profile.genesisHash &&
  value.validatorSetId === profile.validatorSetId &&
  value.quorumPolicyId === profile.quorumPolicyId &&
  value.ref === "awai/domain/murakumo.cloud" &&
  value.headCid === "bafkrei-test-head" &&
  value.challenge === "test-challenge-123456" &&
  value.current && value.quorumReadVerified &&
  Object.keys(value.currentReadProof).length > 0 &&
  value.finalityProofVerified &&
  value.finalizedEventCid === "bafkrei-test-head" &&
  Object.keys(value.finalityProof).length > 0 &&
  value.observedAt <= 150 && 150 < value.expiresAt && value.signatureValid;

const referenceBatch = () => {
  let accepted = 0;
  for (let index = 0; index < 300; index += 1) {
    if (referenceVerified(index % 2 === 0 ? valid : quorumMismatch)) accepted += 1;
  }
  return accepted;
};

const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const measure = fn => {
  const start = performance.now();
  const accepted = fn();
  return { nsPerDecision: (performance.now() - start) * 1e6 / 300, accepted };
};

assert.equal(referenceBatch(), 150);
assert.equal(wasmBatch(), 150);
for (let i = 0; i < 2; i += 1) {
  assert.equal(referenceBatch(), 150);
  assert.equal(wasmBatch(), 150);
}

const hostSamples = [];
const wasmSamples = [];
for (let round = 0; round < 7; round += 1) {
  const order = round % 2 === 0
    ? [[referenceBatch, hostSamples], [wasmBatch, wasmSamples]]
    : [[wasmBatch, wasmSamples], [referenceBatch, hostSamples]];
  for (const [run, samples] of order) {
    const result = measure(run);
    assert.equal(result.accepted, 150);
    samples.push(result.nsPerDecision);
  }
}

const hostNs = median(hostSamples);
const wasmNs = median(wasmSamples);
console.log("benchmark=awai-current-ref-amuwasm");
console.log("compiler=Amu-Node-bootstrap; selfhost=false");
console.log("runtime=wasm32-browser via Amu browser-host.mjs");
console.log("workload=300 alternating valid/quorum-policy-mismatch decisions per call");
console.log("rounds=7; order=alternating; warmup=2 per implementation");
console.log("host-reference-median-ns/decision=", hostNs);
console.log("amu-wasm-median-ns/decision=", wasmNs);
console.log("ratio-amu-wasm-over-host=", wasmNs / hostNs);
console.log("host-loadavg=", os.loadavg());
console.log("qualification=small-sample diagnostic-only; selfhost and quiet-host gates remain open");
