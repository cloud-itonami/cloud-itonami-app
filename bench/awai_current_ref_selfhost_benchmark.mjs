import os from "node:os";
import { spawnSync } from "node:child_process";
import { hrtime } from "node:process";

const [loader, nativeImage, entryOffsetText, emptyImage, emptyOffsetText] = process.argv.slice(2);
if (!loader || !nativeImage || !entryOffsetText || !emptyImage || !emptyOffsetText) {
  throw new Error("usage: node awai_current_ref_selfhost_benchmark.mjs <kexe-loader> <native-image> <entry-offset> <empty-image> <empty-offset>");
}

const iterations = 1_000_000;
const rounds = 7;
const profile = {
  profileId: "awai-test-profile",
  chainId: "awai-test-chain",
  genesisHash: "test-genesis",
  validatorSetId: "test-validator-set",
  quorumPolicyId: "test-quorum-policy",
};
const validProof = {
  trust: "amano-inga-verified-current-ref",
  decisionVerified: true,
  profileId: profile.profileId,
  chainId: profile.chainId,
  genesisHash: profile.genesisHash,
  validatorSetId: profile.validatorSetId,
  quorumPolicyId: profile.quorumPolicyId,
  reference: "awai/domain/murakumo.cloud",
  headCid: "bafkrei-test-head",
  challenge: "test-challenge-123456",
  current: true,
  quorumReadVerified: true,
  currentReadProofPresent: true,
  finalityProofVerified: true,
  finalizedEventCid: "bafkrei-test-head",
  finalityProofPresent: true,
  observedAt: 100,
  expiresAt: 200,
  signatureValid: true,
};
const invalidProof = { ...validProof, quorumPolicyId: "other-quorum-policy" };
const request = {
  expectedRef: "awai/domain/murakumo.cloud",
  expectedHeadCid: "bafkrei-test-head",
  expectedChallenge: "test-challenge-123456",
  nowMs: 150,
};

function referenceVerified(proof, expected) {
  return Object.values(profile).every((value) => typeof value === "string" && value.length > 0)
    && proof.trust === "amano-inga-verified-current-ref"
    && proof.decisionVerified === true
    && proof.profileId === profile.profileId
    && proof.chainId === profile.chainId
    && proof.genesisHash === profile.genesisHash
    && proof.validatorSetId === profile.validatorSetId
    && proof.quorumPolicyId === profile.quorumPolicyId
    && proof.reference === expected.expectedRef
    && proof.headCid === expected.expectedHeadCid
    && proof.challenge === expected.expectedChallenge
    && proof.current === true
    && proof.quorumReadVerified === true
    && proof.currentReadProofPresent === true
    && proof.finalityProofVerified === true
    && proof.finalizedEventCid === expected.expectedHeadCid
    && proof.finalityProofPresent === true
    && Number.isSafeInteger(proof.observedAt)
    && Number.isSafeInteger(proof.expiresAt)
    && proof.observedAt >= 0
    && proof.observedAt <= expected.nowMs
    && expected.nowMs < proof.expiresAt
    && proof.signatureValid === true;
}

function referenceBatch() {
  let accepted = 0;
  let valid = true;
  for (let i = 0; i < iterations; i++) {
    const proof = valid ? validProof : invalidProof;
    accepted += Number(referenceVerified(proof, request));
    valid = !valid;
  }
  return accepted;
}

function nativeRun(image, offset) {
  const start = hrtime.bigint();
  const result = spawnSync(
    loader,
    [image, offset, "0", "aarch64", "-"],
    {
      encoding: "utf8",
      env: {
        ...process.env,
        KEXE_FUEL: "off",
        KEXE_PAIRS: "16777216",
        KEXE_VECTORS: "4194304",
        KEXE_VECTOR_ITEMS: "134217728",
        KEXE_STRING_POOL: "268435456",
        KEXE_CPU_SECONDS: "1800",
        KEXE_WALL_SECONDS: "1800",
      },
    },
  );
  const elapsed = hrtime.bigint() - start;
  if (result.status !== 0) {
    throw new Error(`native driver failed (${result.status}): ${result.stderr.trim()}`);
  }
  return { elapsedNs: Number(elapsed), checksum: Number(result.stdout.trim()) };
}

function nativeBatch() {
  const result = nativeRun(nativeImage, entryOffsetText);
  if (result.checksum !== iterations / 2) {
    throw new Error(`native checksum mismatch: ${result.checksum}`);
  }
  return result.elapsedNs;
}

function emptyRun() {
  const result = nativeRun(emptyImage, emptyOffsetText);
  if (result.checksum !== 0) throw new Error(`empty-loader checksum mismatch: ${result.checksum}`);
  return result.elapsedNs;
}

if (!referenceVerified(validProof, request) || referenceVerified(invalidProof, request)) {
  throw new Error("reference predicate precondition failed");
}
if (referenceBatch() !== iterations / 2) throw new Error("reference checksum mismatch");
nativeBatch(); // warm each executable path once before collecting samples
emptyRun();
referenceBatch();
const loadBefore = os.loadavg();

const nativeSamples = [];
const emptySamples = [];
const referenceSamples = [];
for (let round = 0; round < rounds; round++) {
  const runNative = () => {
    nativeSamples.push(nativeBatch());
    emptySamples.push(emptyRun());
  };
  const runReference = () => {
    const start = hrtime.bigint();
    const checksum = referenceBatch();
    const elapsed = hrtime.bigint() - start;
    if (checksum !== iterations / 2) throw new Error("reference checksum mismatch");
    referenceSamples.push(Number(elapsed) / iterations);
  };
  if (round % 2 === 0) {
    runNative();
    runReference();
  } else {
    runReference();
    runNative();
  }
}

const median = (values) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const nativeRawNs = median(nativeSamples);
const loaderNs = median(emptySamples);
const nativeNs = Math.max(0, nativeRawNs - loaderNs) / iterations;
const referenceNs = median(referenceSamples);
const loadAfter = os.loadavg();
const logicalCpus = os.availableParallelism();
const loadQualification = Math.max(loadBefore[0], loadAfter[0]) <= logicalCpus
  ? "1-minute load stayed within logical CPU count"
  : "contended diagnostic; 1-minute load exceeded logical CPU count";
console.log("benchmark=awai-current-ref-selfhost-native-core");
console.log("compiler=Amu native selfhost seed; pinned adopted source, 3-generation fixed point");
console.log("guest=Amu selfhost-compiled aarch64-macos native image via trusted KEXE loader");
console.log(`workload=${iterations} alternating valid/quorum-policy-mismatch decisions per sample`);
console.log(`rounds=${rounds}; warmup=1 per implementation; order=alternating`);
console.log(`selfhost-native-median-total-ms=${nativeRawNs / 1e6}`);
console.log(`empty-loader-median-total-ms=${loaderNs / 1e6}`);
console.log(`selfhost-native-adjusted-median-ns/decision=${nativeNs}`);
console.log(`javascript-reference-median-ns/decision=${referenceNs}`);
console.log(`ratio-selfhost-native-over-js=${nativeNs / referenceNs}`);
console.log(`host-loadavg-before-1-5-15=${loadBefore.join(" / ")}`);
console.log(`host-loadavg-after-1-5-15=${loadAfter.join(" / ")}`);
console.log(`host-logical-cpus=${logicalCpus}`);
console.log(`host-load-qualification=${loadQualification}`);
console.log("timing=native external wall time minus empty KEXE loader median; JS is warmed in-process");
console.log("qualification=small diagnostic sample; loader subtraction is approximate; no signature verifier, registry, network, content, or full app lifecycle");
