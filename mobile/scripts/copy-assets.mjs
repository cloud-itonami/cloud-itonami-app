#!/usr/bin/env node
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const lock = Object.fromEntries(fs.readFileSync(path.join(here, "dds.lock"), "utf8")
  .trim().split(/\r?\n/u).map(line => {
    const index = line.indexOf("=");
    return [line.slice(0, index), line.slice(index + 1)];
  }));
const sourceRoot = process.env.JP_GO_DDS;

function fail(message) {
  console.error(`mobile assets: ${message}`);
  process.exit(1);
}

if (!sourceRoot) fail("set JP_GO_DDS to the checkout pinned in mobile/dds.lock");
const revision = spawnSync("git", ["-C", sourceRoot, "rev-parse", "HEAD"], { encoding: "utf8" });
if (revision.status !== 0) fail(`cannot read DADS checkout revision: ${revision.stderr.trim()}`);
if (revision.stdout.trim() !== lock.revision)
  fail(`DADS revision mismatch: expected ${lock.revision}, got ${revision.stdout.trim()}`);

const source = path.join(sourceRoot, ...lock.path.split("/"));
if (!fs.existsSync(source)) fail(`DADS stylesheet is missing: ${source}`);
const contents = fs.readFileSync(source);
const digest = createHash("sha256").update(contents).digest("hex");
if (digest !== lock.sha256) fail(`DADS stylesheet hash mismatch: expected ${lock.sha256}, got ${digest}`);

const dist = path.join(here, "dist");
fs.mkdirSync(dist, { recursive: true });
fs.copyFileSync(source, path.join(dist, "dds.css"));
console.log(`Copied DADS stylesheet from ${lock.repository}@${lock.revision}`);
