#!/usr/bin/env node
// Runs scripts/audit-viewport.cljk with kotoba-lang/shinkansen on the classpath,
// and checks dist/ holds only what the app ships.
//   SHINKANSEN  a kotoba-lang/shinkansen checkout (default: the superproject's)
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const here = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
process.chdir(here);

const shipped = new Set(["index.html", "dds.css", "js", "js/main.js"]);
const extra = [];
(function walk(dir, rel) {
  for (const d of fs.readdirSync(dir, { withFileTypes: true })) {
    const r = rel ? `${rel}/${d.name}` : d.name;
    if (!shipped.has(r)) extra.push(r);
    else if (d.isDirectory()) walk(path.join(dir, d.name), r);
  }
})("dist", "");
if (extra.length) {
  console.error(`dist/ carries files the app does not ship: ${extra.join(", ")}`);
  process.exit(1);
}

const shinkansen = process.env.SHINKANSEN ?? path.resolve(here, "../../../kotoba-lang/shinkansen");
const run = spawnSync("kbb", ["--backend", "sci", "--classpath", path.join(shinkansen, "src"),
  "scripts/audit-viewport.cljk"], { stdio: ["ignore", "inherit", "pipe"], encoding: "utf8" });
process.stderr.write((run.stderr ?? "").split("\n").filter(l => !l.startsWith("kbb: implicit stdlib")).join("\n"));
process.exit(run.status ?? 1);
