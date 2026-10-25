#!/usr/bin/env node
// Build the mobile document and its Amu/Kotoba guest without a Clojure or
// Shadow CLJS build. The compiler and runtime are supplied by one pinned Amu
// checkout, so the emitted guest and its host use the same runtime revision.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const dist = path.join(here, "dist");
const target = path.join(here, "target", "amu-mobile");
const expectedRevision = fs.readFileSync(path.join(here, "amu.lock"), "utf8").trim();
const commandLock = Object.fromEntries(fs.readFileSync(path.join(here, "commands.lock"), "utf8")
  .trim().split(/\r?\n/u).map(line => {
    const index = line.indexOf("=");
    return [line.slice(0, index), line.slice(index + 1)];
  }));
const engineLock = Object.fromEntries(fs.readFileSync(path.join(here, "engine.lock"), "utf8")
  .trim().split(/\r?\n/u).map(line => {
    const index = line.indexOf("=");
    return [line.slice(0, index), line.slice(index + 1)];
  }));
const amuRoot = process.env.AMU_ROOT;
const engineRoot = process.env.KBB_ENGINE_ROOT;
const apiBase = process.env.MOBILE_API_BASE
  ?? "https://cloud-itonami-app-edge.04-feasts-minded.workers.dev";

function fail(message) {
  console.error(`mobile Amu build: ${message}`);
  process.exit(1);
}

if (!amuRoot) fail("set AMU_ROOT to the checkout of the commit in mobile/amu.lock");
if (!fs.existsSync(path.join(amuRoot, "bin", "amu")))
  fail(`AMU_ROOT does not contain bin/amu: ${amuRoot}`);

const revision = spawnSync("git", ["-C", amuRoot, "rev-parse", "HEAD"], { encoding: "utf8" });
if (revision.status !== 0)
  fail(`cannot read the Amu checkout revision: ${revision.stderr.trim()}`);
const actualRevision = revision.stdout.trim();
if (actualRevision !== expectedRevision)
  fail(`Amu revision mismatch: expected ${expectedRevision}, got ${actualRevision || "unknown"}`);

if (!engineRoot) fail("set KBB_ENGINE_ROOT to the checkout pinned in mobile/engine.lock");
const engineRevision = spawnSync("git", ["-C", engineRoot, "rev-parse", "HEAD"], { encoding: "utf8" });
if (engineRevision.status !== 0)
  fail(`cannot read the KBB engine checkout revision: ${engineRevision.stderr.trim()}`);
if (engineRevision.stdout.trim() !== engineLock.revision)
  fail(`KBB engine revision mismatch: expected ${engineLock.revision}, got ${engineRevision.stdout.trim()}`);
const engineCli = path.join(engineRoot, engineLock.path);
if (!fs.existsSync(engineCli)) fail(`KBB engine CLI is missing: ${engineCli}`);

fs.rmSync(target, { recursive: true, force: true });
fs.mkdirSync(target, { recursive: true });
const output = path.join(target, "app.mjs");
const commandTablesPath = path.join(here, "data", "command-tables.json");
const commandTablesSource = fs.readFileSync(commandTablesPath, "utf8");
const commandTables = JSON.parse(commandTablesSource);
if (commandTables?.source?.repository !== commandLock.repository
    || commandLock.repository !== "cloud-itonami/cloud-itonami-commands"
    || commandTables?.source?.revision !== commandLock.revision
    || createHash("sha256").update(commandTablesSource).digest("hex") !== commandLock.sha256
    || commandTables?.routes?.length !== 211
    || !Array.isArray(commandTables?.aliases)
    || !Array.isArray(commandTables?.hostSide))
  fail("mobile/data/command-tables.json does not match its pinned source schema");
const guestTemplate = fs.readFileSync(
  path.join(here, "src/cloud/itonami/mobile/app.kotoba"), "utf8");
if (guestTemplate.split("__COMMAND_TABLES__").length !== 2)
  fail("Kotoba guest must contain exactly one __COMMAND_TABLES__ insertion point");
const guestSource = guestTemplate.replace("__COMMAND_TABLES__", JSON.stringify(commandTablesSource));
const generatedSource = path.join(target, "app.generated.kotoba");
fs.writeFileSync(generatedSource, guestSource);
const compiler = spawnSync(process.execPath, [
  path.join(amuRoot, "bin", "amu"), "compile",
  generatedSource,
  "--target", "js-kotoba-v1", "--jvm-free", "--output", output,
], { encoding: "utf8", env: { ...process.env, KBB_ENGINE: engineCli } });
if (compiler.stdout) process.stdout.write(compiler.stdout);
if (compiler.stderr) process.stderr.write(compiler.stderr);
if (compiler.status !== 0) process.exit(compiler.status ?? 1);

for (const artifact of [output, `${output}.manifest.edn`, `${output}.manifest.json`, `${output}.provenance.edn`]) {
  if (!fs.existsSync(artifact)) fail(`compiler did not write ${artifact}`);
}

const jsDist = path.join(dist, "js");
const runtimeDist = path.join(dist, "runtime");
fs.rmSync(jsDist, { recursive: true, force: true });
fs.rmSync(runtimeDist, { recursive: true, force: true });
fs.mkdirSync(jsDist, { recursive: true });
fs.mkdirSync(runtimeDist, { recursive: true });
fs.copyFileSync(output, path.join(jsDist, "app.mjs"));
fs.copyFileSync(path.join(amuRoot, "runtime/browser-host.mjs"),
  path.join(runtimeDist, "browser-host.mjs"));

const hostSource = fs.readFileSync(path.join(here, "src/main.mjs"), "utf8");
if (!hostSource.includes("__MOBILE_API_BASE__")) fail("mobile host API placeholder is missing");
fs.writeFileSync(path.join(jsDist, "main.js"),
  hostSource.replaceAll("__MOBILE_API_BASE__", JSON.stringify(apiBase)));
const hostCheck = spawnSync(process.execPath, ["--check", path.join(jsDist, "main.js")], { encoding: "utf8" });
if (hostCheck.stdout) process.stdout.write(hostCheck.stdout);
if (hostCheck.stderr) process.stderr.write(hostCheck.stderr);
if (hostCheck.status !== 0) process.exit(hostCheck.status ?? 1);

const indexPath = path.join(dist, "index.html");
let index = fs.readFileSync(indexPath, "utf8");
index = index.replace(/<script\s+src="js\/main\.js"\s+defer="defer"><\/script>/,
  '<script type="module" src="js/main.js"></script>');
if (!index.includes('<script type="module" src="js/main.js"></script>'))
  fail("dist/index.html must contain the generated module entry script");
fs.writeFileSync(indexPath, index);

console.log(`Amu/Kotoba mobile bundle built from ${expectedRevision}`);
