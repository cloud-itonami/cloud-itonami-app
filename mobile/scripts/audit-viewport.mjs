#!/usr/bin/env node
// Checks the static shinkansen viewport contract over the shipped document and
// stylesheet, and rejects files the app does not ship.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
process.chdir(here);

const shipped = new Set(["index.html", "dds.css", "js", "js/main.js", "js/app.mjs",
  "runtime", "runtime/browser-host.mjs"]);
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

const html = fs.readFileSync("dist/index.html", "utf8");
const css = fs.readFileSync("dist/dds.css", "utf8");
const combined = `${html} ${css}`;
const viewport = /<meta[^>]+name=["']viewport["']/u.test(html)
  && /content=["'][^"']*width=device-width/u.test(html);
const bands = [...combined.matchAll(/@media[^{]*max-width\s*:\s*([0-9.]+)\s*(px|rem|em)/gu)]
  .map(match => Math.round(Number(match[1]) * (match[2] === "px" ? 1 : 16)))
  .filter((value, index, values) => values.indexOf(value) === index)
  .sort((a, b) => a - b);
const fixedWidthBody = /body\s*\{[^}]*width\s*:\s*[0-9]{3,}px/u.test(combined);
const safeAreaTop = /env\(safe-area-inset-top/u.test(combined);
const problems = [];
if (!viewport) problems.push("viewport-missing");
if (!bands.some(value => value <= 480)) problems.push("no-xs-band");
if (fixedWidthBody) problems.push("fixed-width-body");
const result = problems.length > 0
  ? { ok: false, file: "index.html", problems }
  : { ok: true, file: "index.html", viewport: "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">",
    bands, safeAreaTop };
console.log(JSON.stringify(result));
if (!result.ok || !safeAreaTop) process.exit(1);
