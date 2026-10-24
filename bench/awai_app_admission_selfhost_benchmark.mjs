import os from "node:os";
import { spawnSync } from "node:child_process";
import { hrtime } from "node:process";

const [loader, nativeImage, entryOffsetText, emptyImage, emptyOffsetText] = process.argv.slice(2);
if (!loader || !nativeImage || !entryOffsetText || !emptyImage || !emptyOffsetText) {
  throw new Error("usage: node awai_app_admission_selfhost_benchmark.mjs <kexe-loader> <native-image> <entry-offset> <empty-image> <empty-offset>");
}

const iterations = 1_000_000;
const rounds = 7;
const valid = {
  prepared: true,
  installLockValid: true,
  runtimeInputsValid: true,
  clockValid: true,
  dnsProofValid: true,
  amanoState: 2,
  contentComplete: true,
  capabilitiesApproved: true,
};
const refused = { ...valid, capabilitiesApproved: false };

function finishInstallCode(input) {
  if (!input.prepared) return 1;
  if (!input.installLockValid) return 2;
  if (!input.runtimeInputsValid) return 3;
  if (!input.clockValid) return 4;
  if (!input.dnsProofValid) return 5;
  if (input.amanoState === 0) return 6;
  if (input.amanoState !== 2) return 7;
  if (!input.contentComplete) return 8;
  if (!input.capabilitiesApproved) return 9;
  return 0;
}

function referenceBatch() {
  let admitted = 0;
  let allow = true;
  for (let i = 0; i < iterations; i += 1) {
    admitted += Number(finishInstallCode(allow ? valid : refused) === 0);
    allow = !allow;
  }
  return admitted;
}

function nativeRun(image, offset) {
  const start = hrtime.bigint();
  const result = spawnSync(loader, [image, offset, "0", "aarch64", "-"], {
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
  });
  const elapsedNs = hrtime.bigint() - start;
  if (result.status !== 0) {
    throw new Error(`native driver failed (${result.status}): ${result.stderr.trim()}`);
  }
  return { elapsedNs: Number(elapsedNs), checksum: Number(result.stdout.trim()) };
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

if (finishInstallCode(valid) !== 0 || finishInstallCode(refused) !== 9) {
  throw new Error("reference predicate precondition failed");
}
if (referenceBatch() !== iterations / 2) throw new Error("reference checksum mismatch");
nativeBatch();
emptyRun();
referenceBatch();
const loadBefore = os.loadavg();

const nativeSamples = [];
const emptySamples = [];
const referenceSamples = [];
for (let round = 0; round < rounds; round += 1) {
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

const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const nativeRawNs = median(nativeSamples);
const loaderNs = median(emptySamples);
const nativeNs = Math.max(0, nativeRawNs - loaderNs) / iterations;
const referenceNs = median(referenceSamples);
const loadAfter = os.loadavg();
const logicalCpus = os.availableParallelism();
const loadQualification = Math.max(loadBefore[0], loadAfter[0]) <= logicalCpus
  ? "1-minute load stayed within logical CPU count"
  : "contended diagnostic; 1-minute load exceeded logical CPU count";
console.log("benchmark=awai-app-install-admission-selfhost-native-core");
console.log("compiler=Amu native selfhost seed; adopted pinned compiler image");
console.log("guest=Amu selfhost-compiled aarch64-macos native image via trusted KEXE loader");
console.log(`workload=${iterations} alternating admitted/capability-refused decisions per sample`);
console.log(`rounds=${rounds}; warmup=1 per executable path; order=alternating`);
console.log(`selfhost-native-median-total-ms=${nativeRawNs / 1e6}`);
console.log(`empty-loader-median-total-ms=${loaderNs / 1e6}`);
console.log(`selfhost-native-adjusted-median-ns/decision=${nativeNs}`);
console.log(`javascript-reference-median-ns/decision=${referenceNs}`);
console.log(`ratio-selfhost-native-over-js=${nativeNs / referenceNs}`);
console.log(`host-loadavg-before-1-5-15=${loadBefore.join(" / ")}`);
console.log(`host-loadavg-after-1-5-15=${loadAfter.join(" / ")}`);
console.log(`host-logical-cpus=${logicalCpus}`);
console.log(`host-load-qualification=${loadQualification}`);
console.log("timing=native wall time minus empty KEXE loader median; JS reference is warmed in-process");
console.log("qualification=diagnostic only; source-path build is unpinned and excludes adapters, cryptography, I/O, and full app lifecycle");
