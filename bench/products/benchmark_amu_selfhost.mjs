import { readFileSync } from "node:fs";
import os from "node:os";
import { spawnSync } from "node:child_process";
import { hrtime } from "node:process";

const [domain, buildDir, outputPath] = process.argv.slice(2);
const sourceFiles = {
  murakumo: "compute_market_admission.kotoba",
  aozora: "agent_biscuit_admission_core.kotoba",
  oppai: "challenge_window_core.kotoba",
  isekai: "publish_admission_core.kotoba",
  babiniku: "governor_core.kotoba",
  x402: "transfer_preflight_core.kotoba",
  shinshi: "video_black_frame_core.kotoba",
  awai: "chat_admission_core.kotoba",
};
if (!Object.hasOwn(sourceFiles, domain) || !buildDir) {
  throw new Error(`usage: node benchmark_amu_selfhost.mjs <${Object.keys(sourceFiles).join("|")}> <build-dir> [output.txt]`);
}

const build = JSON.parse(readFileSync(`${buildDir}/report.json`, "utf8"));
const loader = `${buildDir}/host/kexe-loader`;
const nativeImage = build.artifacts.decision.nativeImage;
const nativeOffset = build.artifacts.decision.exports.main.offset;
const emptyImage = build.artifacts.empty.nativeImage;
const emptyOffset = build.artifacts.empty.exports.main.offset;
const iterations = 1_000_000;
const rounds = 7;

const workloads = {
  murakumo: {
    acceptedResult: 0,
    cases: [{ args: [127, 950, 1000], result: 0 }, { args: [63, 950, 1000], result: 7 }],
    policy: ([bits, estimated, maximum]) => {
      if (estimated < 0 || maximum < 0 || (bits & 1) === 0) return 1;
      for (const [mask, result] of [[2, 2], [4, 3], [8, 4], [16, 5], [32, 6], [64, 7]]) {
        if ((bits & mask) === 0) return result;
      }
      return estimated > maximum ? 8 : 0;
    },
  },
  aozora: {
    acceptedResult: 0,
    cases: [
      { args: [1, 0, 0, 0, 1, 1], result: 0 },
      { args: [1, 0, 0, 0, 1, 2], result: 3 },
      { args: [0, 1, 0, 1, 1, 0], result: 0 },
      { args: [0, 1, 0, 0, 0, 1], result: 2 },
      { args: [0, 0, 1, 6, 0, 0], result: 0 },
      { args: [1, 0, 0, 4, 0, 0], result: 0 },
      { args: [0, 0, 1, 5, 1, 0], result: 0 },
      { args: [1, 1, 1, 99, 1, 1], result: 1 },
    ],
    policy: ([repoPost, repoWrite, accountBind, opCode, repoMatch, collectionCode]) =>
      authorizeOp({ repoPost, repoWrite, accountBind, opCode, repoMatch, collectionCode }),
  },
  oppai: {
    acceptedResult: 1,
    cases: [
      { args: [1, 1000000, 1000000], result: 1 },
      { args: [1, 1000000, 1060000], result: 1 },
      { args: [1, 1000000, 1060001], result: 0 },
      { args: [1, 1000000, 999999], result: 0 },
      { args: [0, 1000000, 1030000], result: 0 },
    ],
    policy: ([valid, issuedAt, now]) => Number(valid === 1 && issuedAt >= 0 && issuedAt <= 9999999999999
      && now >= 0 && now <= 9999999999999 && now >= issuedAt && now - issuedAt <= 60000),
  },
  isekai: {
    acceptedResult: 0,
    cases: [{ args: [2], result: 0 }, { args: [1], result: 1 }],
    policy: ([tier]) => Number(tier !== 2 && tier !== 3),
  },
  babiniku: {
    acceptedResult: 0,
    cases: [
      { args: [0, 0, 0, 0], result: 0 }, { args: [1, 0, 0, 1], result: 1 },
      { args: [0, 99, 0, 0], result: 2 }, { args: [0, 0, 99, 0], result: 3 },
      { args: [0, 0, 0, 1], result: 4 }, { args: [0, 5, 4, 0], result: 0 },
      { args: [0, 99, 0, 1], result: 2 }, { args: [0, 0, 99, 1], result: 3 },
    ],
    policy: ([blocked, emotion, motion, ambiguous]) => {
      if (blocked === 1 || (blocked !== 0 && blocked !== 1)) return 1;
      if (emotion < 0 || emotion > 5) return 2;
      if (motion < 0 || motion > 4) return 3;
      if (ambiguous !== 0 && ambiguous !== 1) return 4;
      return ambiguous === 1 ? 4 : 0;
    },
  },
  x402: {
    acceptedResult: 0,
    cases: [
      { args: [1, 1, 1, 1, 1, 1, 100, 500, 100, 1000], result: 0 },
      { args: [0, 1, 1, 1, 1, 1, 100, 500, 100, 1000], result: 1 },
      { args: [1, 0, 1, 1, 1, 1, 100, 500, 100, 1000], result: 2 },
      { args: [1, 1, 0, 1, 1, 1, 100, 500, 100, 1000], result: 3 },
      { args: [1, 1, 1, 0, 1, 1, 100, 500, 100, 1000], result: 4 },
      { args: [1, 1, 1, 1, 0, 1, 100, 500, 100, 1000], result: 5 },
      { args: [1, 1, 1, 1, 1, 0, 100, 500, 100, 1000], result: 6 },
      { args: [1, 1, 1, 1, 1, 1, 600, 500, 100, 1000], result: 7 },
      { args: [1, 1, 1, 1, 1, 1, 100, 500, 950, 1000], result: 8 },
      { args: [1, 0, 0, 0, 1, 1, 100, 500, 100, 1000], result: 2 },
    ],
    policy: ([principalValid, walletScopeValid, grantActive, requestValid, recipientAllowed,
      budgetAvailable, amount, perTransfer, used, daily]) => transferVerdict({
      principalValid, walletScopeValid, grantActive, requestValid, recipientAllowed,
      budgetAvailable, amount, perTransfer, used, daily,
    }),
  },
  shinshi: {
    acceptedResult: 0,
    cases: [
      { args: [1, 0.0], result: 1 }, { args: [1, 7.9], result: 1 },
      { args: [1, 8.0], result: 0 }, { args: [1, 102.0], result: 0 },
      { args: [0, 0.0], result: 1 },
    ],
    policy: ([measured, meanLuma]) => Number(measured !== 1 || meanLuma < 8.0),
  },
  awai: {
    acceptedResult: 0,
    cases: [
      { args: [1, 1, 5000], result: 0 }, { args: [0, 0, 0], result: 1 },
      { args: [1, 0, 0], result: 2 }, { args: [1, 41, 0], result: 3 },
      { args: [1, 1, 24001], result: 4 }, { args: [1, 40, 24000], result: 0 },
      { args: [1, 1, 24000], result: 0 }, { args: [0, 41, 24001], result: 1 },
    ],
    policy: ([messagesArray, messageCount, totalChars]) => {
      if (messagesArray !== 1) return 1;
      if (messageCount <= 0) return 2;
      if (messageCount > 40) return 3;
      if (totalChars < 0 || totalChars > 24000) return 4;
      return 0;
    },
  },
};

const { acceptedResult, cases, policy } = workloads[domain];
const expectedChecksum = cases.reduce((sum, item) => sum + Number(item.result === acceptedResult), 0)
  * (iterations / cases.length);

function authorizeOp(input) {
  const { repoPost: post, repoWrite: write, accountBind: account, opCode: op,
    repoMatch, collectionCode: collection } = input;
  if (op < 0 || op > 6) return 1;
  if (![post, write, account, repoMatch].every(value => value === 0 || value === 1)) return 3;
  if (![4, 5, 6].includes(op) && repoMatch === 0) return 2;
  const allowed = op === 0 ? post === 1 || write === 1
    : [1, 2, 3].includes(op) ? write === 1
      : op === 4 ? post === 1 || write === 1
        : account === 1;
  if (!allowed) return 3;
  if (op === 0 && post === 1 && write === 0 && collection !== 1) return 3;
  return 0;
}

const unitsRange = value => Number.isInteger(value) && value >= 0 && value <= 999999999999999;
function transferVerdict(input) {
  const { principalValid, walletScopeValid, grantActive, requestValid,
    recipientAllowed, budgetAvailable, amount, perTransfer, used, daily } = input;
  if (principalValid !== 1) return 1;
  if (walletScopeValid !== 1) return 2;
  if (grantActive !== 1) return 3;
  if (requestValid !== 1 || !unitsRange(amount) || amount === 0) return 4;
  if (recipientAllowed !== 1) return 5;
  if (budgetAvailable !== 1 || !unitsRange(perTransfer) || !unitsRange(used) || !unitsRange(daily)) return 6;
  if (amount > perTransfer) return 7;
  if (used + amount > daily) return 8;
  return 0;
}

function referenceBatch() {
  let accepted = 0;
  for (let i = 0; i < iterations; i += 1) {
    const expected = cases[i % cases.length];
    const result = policy(expected.args);
    if (result !== expected.result) throw new Error(`reference precondition mismatch at ${i}`);
    accepted += Number(result === acceptedResult);
  }
  return accepted;
}

function nativeRun(image, offset) {
  const start = hrtime.bigint();
  const result = spawnSync(loader, [image, String(offset), "0", "aarch64", "-"], {
    encoding: "utf8",
    env: { ...process.env, KEXE_FUEL: "off", KEXE_CPU_SECONDS: "1800",
      KEXE_WALL_SECONDS: "1800", KEXE_PAIRS: "16777216",
      KEXE_VECTORS: "4194304", KEXE_VECTOR_ITEMS: "134217728",
      KEXE_STRING_POOL: "268435456" },
  });
  const elapsedNs = hrtime.bigint() - start;
  if (result.status !== 0) throw new Error(`native driver failed (${result.status}): ${result.stderr.trim()}`);
  return { elapsedNs: Number(elapsedNs), checksum: Number(result.stdout.trim()) };
}

function nativeBatch() {
  const result = nativeRun(nativeImage, nativeOffset);
  if (result.checksum !== expectedChecksum) throw new Error(`native checksum mismatch: ${result.checksum}`);
  return result.elapsedNs;
}
function emptyRun() {
  const result = nativeRun(emptyImage, emptyOffset);
  if (result.checksum !== 0) throw new Error(`empty checksum mismatch: ${result.checksum}`);
  return result.elapsedNs;
}

if (referenceBatch() !== expectedChecksum) throw new Error("reference checksum mismatch");
nativeBatch();
emptyRun();
const loadBefore = os.loadavg();
const nativeSamples = [];
const emptySamples = [];
const referenceSamples = [];
for (let round = 0; round < rounds; round += 1) {
  const runNative = () => { nativeSamples.push(nativeBatch()); emptySamples.push(emptyRun()); };
  const runReference = () => {
    const start = hrtime.bigint();
    if (referenceBatch() !== expectedChecksum) throw new Error("reference checksum mismatch");
    referenceSamples.push(Number(hrtime.bigint() - start) / iterations);
  };
  if (round % 2 === 0) { runNative(); runReference(); }
  else { runReference(); runNative(); }
}
const loadAfter = os.loadavg();
const logicalCpus = os.availableParallelism();
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const nativeRawNs = median(nativeSamples);
const emptyNs = median(emptySamples);
const nativeNs = Math.max(0, nativeRawNs - emptyNs) / iterations;
const referenceNs = median(referenceSamples);
const qualification = Math.max(loadBefore[0], loadAfter[0]) <= logicalCpus
  ? "1-minute load stayed within logical CPU count"
  : "contended diagnostic; 1-minute load exceeded logical CPU count";
const output = [
  `benchmark=${domain}-product-policy-amu-selfhost-native`,
  `source-sha256=${build.sourceClosure[Object.keys(build.sourceClosure).find(path => path.endsWith(sourceFiles[domain]))]}`,
  `compiler-sha256=${build.compilerSha256}`,
  `native-image-sha256=${build.artifacts.decision.nativeImageSha256}`,
  `workload=${iterations} decisions; ${rounds} alternating rounds; 1 warmup per executable path`,
  `selfhost-native-adjusted-median-ns/decision=${nativeNs}`,
  `javascript-reference-median-ns/decision=${referenceNs}`,
  `ratio-selfhost-native-over-js=${nativeNs / referenceNs}`,
  `load-before-1-5-15=${loadBefore.join(" / ")}`,
  `load-after-1-5-15=${loadAfter.join(" / ")}`,
  `logical-cpus=${logicalCpus}`,
  `qualification=${qualification}`,
  "timing=native process wall median minus empty-loader median; JavaScript uses prebuilt mixed cases, allocating record inputs for the two record-ABI policies",
  "scope=decision-core microbenchmark only; excludes app adapters, cryptography, storage, chain/peer reads, network, rendering, and full request lifecycle",
].join("\n") + "\n";
console.log(output);
if (outputPath) {
  const { writeFileSync } = await import("node:fs");
  writeFileSync(outputPath, output);
}
