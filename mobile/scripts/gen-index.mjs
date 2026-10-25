#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'index.template.html');
const output = path.join(root, 'dist', 'index.html');
const expected = fs.readFileSync(source, 'utf8');
const check = process.argv.includes('--check');

if (check) {
  const actual = fs.existsSync(output) ? fs.readFileSync(output, 'utf8') : null;
  if (actual === expected) {
    console.log(`OK\t${path.relative(root, output)} matches the checked-in template`);
  } else {
    console.error(`STALE\t${path.relative(root, output)} differs from index.template.html; run: npm run index`);
    process.exit(1);
  }
} else {
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, expected);
  console.log(`WROTE\t${path.relative(root, output)}\t${Buffer.byteLength(expected)} bytes`);
}
