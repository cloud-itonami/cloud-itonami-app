// Load a kotoba wasm guest through amu's typed ABI host.
//
// This is not a new FFI. instantiateKotoba is amu's published browser host
// at the emit-frontend SHA in compiler-pin.edn. The host file is copied
// next to the artifact at compile time (`target/amu/browser-host.mjs`).
//
// http/accept and http/reply stay kotoba-lang HOLD
// (lang/capability-catalog.edn, :friendly-qualified, compiler-wire-id 17/18).
// Do not invent HttpClient, JNI, or a C .so here.

import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

function resolveHost(appDir) {
  const copied = path.resolve(appDir, "target/amu/browser-host.mjs");
  if (fs.existsSync(copied)) return copied;
  const amuHome = process.env.AMU_HOME;
  if (amuHome) {
    const candidate = path.resolve(amuHome, "runtime/browser-host.mjs");
    if (fs.existsSync(candidate)) return candidate;
  }
  const amu = process.env.AMU;
  if (amu) {
    const candidate = path.resolve(path.dirname(amu), "..", "runtime", "browser-host.mjs");
    if (fs.existsSync(candidate)) return candidate;
  }
  throw new Error(
    "amu browser-host.mjs missing; emit first (bin/kotoba compile --target wasm or bin/compile-amu)"
  );
}

export async function instantiateGuest(wasmPath, appDir) {
  if (!fs.existsSync(wasmPath)) {
    throw new Error(
      `guest wasm missing: ${wasmPath}; run bin/kotoba compile --target wasm`
    );
  }
  const hostPath = resolveHost(appDir);
  const { instantiateKotoba } = await import(pathToFileURL(hostPath).href);
  const bytes = fs.readFileSync(wasmPath);
  if (bytes.byteLength === 0) {
    throw new Error(`guest wasm empty: ${wasmPath}`);
  }
  return instantiateKotoba(bytes);
}

export function guestBool(value) {
  return value === true;
}

export function guestI64(value) {
  if (typeof value === "bigint") return Number(value);
  return Number(value);
}

export function callI64(hosted, name) {
  const fn = hosted.instance.exports[name];
  if (typeof fn !== "function") {
    throw new Error(`guest export missing: ${name}`);
  }
  return guestI64(fn());
}

export async function loadAndCallMain(wasmPath, appDir) {
  const hosted = await instantiateGuest(wasmPath, appDir);
  return { hosted, main: callI64(hosted, "main") };
}
