#!/usr/bin/env node
// Compile the bundle's JavaScript: mobile/dist/js/main.js.
//
// Why this exists. `npm run build` used to end in `npx shadow-cljs release
// app`. The 2026-09-11 mechanical rewrite (bd620311) replaced that with
// `amu compile --target wasm32-browser app`, which cannot do the job: amu
// compiles ONE .kotoba/.cljk/.cljc source to a Wasm module, while this bundle
// is a reagent/React app whose document loads `js/main.js`. Measured
// 2026-10-14 with kotoba-lang/amu a81ba22: `amu compile --target
// wasm32-browser app` answers `:error :usage "source input must use .kotoba,
// .cljk, or .cljc"`. There is no amu target that emits a browser JS bundle.
//
// So shadow-cljs (the `:cljs` alias already pins thheller/shadow-cljs) stays
// the bundle compiler. What changed underneath it is the extension: since the
// cljk rename (adr-2609111500) every source on this classpath — this
// project's, jp-go-dds's, cloud-itonami-commands's, kotoba-lang/text's — ends
// in `.cljk`, which shadow-cljs does not read. This script stages each `.cljk`
// on the classpath back to the extension it had before the rename (the lib's
// own `cljk-origin.edn` when it records one, otherwise `.cljc`, which shadow
// compiles for :cljs and which reads the same as `.cljs` for a file with no
// reader conditionals), puts the staged tree FIRST on the classpath, and runs
// shadow-cljs's release build. Nothing is written outside `mobile/target/`
// and `mobile/dist/js/`.
//
// Env:
//   JP_GO_DDS  absolute path of a kotoba-lang/jp-go-digital-design-system
//              checkout, overriding deps.edn's superproject-relative
//              :local/root (needed from a worktree outside the superproject).

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const here = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
process.chdir(here);

const sdeps = process.env.JP_GO_DDS
  ? ["-Sdeps", `{:aliases {:dds-local {:override-deps {io.github.kotoba-lang/jp-go-digital-design-system {:local/root "${process.env.JP_GO_DDS}"}}}}}`]
  : [];
const aliases = process.env.JP_GO_DDS ? "-M:cljs:dds-local" : "-M:cljs";

const cp = spawnSync("clojure", [...sdeps, "-Spath", aliases], { encoding: "utf8" });
if (cp.status !== 0) {
  process.stderr.write(cp.stderr);
  process.exit(cp.status ?? 1);
}
const entries = cp.stdout.trim().split(":");

const stageRoot = path.join(here, "target", "cljk-stage");
fs.rmSync(stageRoot, { recursive: true, force: true });

function originsFor(dir) {
  // `cljk-origin.edn` sits at the lib root, one level above `src`.
  for (const root of [dir, path.dirname(dir)]) {
    const f = path.join(root, "cljk-origin.edn");
    if (fs.existsSync(f)) {
      const text = fs.readFileSync(f, "utf8");
      const m = new Map();
      for (const [, p, ext] of text.matchAll(/"([^"]+\.cljk)"\s+"(\.clj[cs]?)"/g)) {
        m.set(path.join(root, p), ext);
      }
      return m;
    }
  }
  return new Map();
}

function* walk(dir) {
  for (const d of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, d.name);
    if (d.isDirectory()) yield* walk(p);
    else if (d.name.endsWith(".cljk")) yield p;
  }
}

const staged = [];
let files = 0;
entries.forEach((entry, i) => {
  const dir = path.resolve(here, entry);
  if (entry.endsWith(".jar") || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return;
  const origins = originsFor(dir);
  const out = path.join(stageRoot, String(i));
  let any = false;
  for (const f of walk(dir)) {
    let rel = path.relative(dir, f).slice(0, -".cljk".length);
    let ext = origins.get(f);
    const inner = /\.(clj[cs]?)$/.exec(rel);
    if (inner) { ext = ext ?? "." + inner[1]; rel = rel.slice(0, -inner[0].length); }
    ext = ext ?? ".cljc";
    const target = path.join(out, rel + ext);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(f, target);
    any = true;
    files++;
  }
  if (any) staged.push(out);
});
console.error(`staged ${files} .cljk file(s) from ${staged.length} classpath dir(s)`);

const classpath = [...staged, ...entries].join(":");
const args = process.argv.slice(2);
const build = spawnSync(
  "java",
  ["-cp", classpath, "clojure.main", "-m", "shadow.cljs.devtools.cli", ...(args.length ? args : ["release", "app"])],
  { stdio: "inherit" },
);
// shadow-cljs writes its module manifest beside the bundle. Nothing in the
// app reads it, and everything in dist/ ships (README), so it does not stay.
fs.rmSync(path.join(here, "dist", "js", "manifest.edn"), { force: true });
process.exit(build.status ?? 1);
