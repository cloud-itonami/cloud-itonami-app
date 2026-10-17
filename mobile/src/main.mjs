/* Browser effects for the Amu/Kotoba mobile app.

   The Kotoba guest owns the screen state and transitions. This host owns the
   DOM, HTTPS requests, and event delivery; it grants the guest no ambient
   capability. */
import { instantiateKotoba, reconcileUiDocument } from "../runtime/browser-host.mjs";
import * as appModule from "./app.mjs";

const API_BASE = __MOBILE_API_BASE__;
const MAX_USER_EVENT_BYTES = 1024;
const MAX_GUEST_EVENT_BYTES = 65536;
const MAX_TERMINAL_RESPONSE_BYTES = 8192;
const PAGE_LIMIT = 32;
const container = document.getElementById("app");

function mapValue(value, key) {
  if (!Array.isArray(value) || value[0] !== "map" || !Array.isArray(value[1])) return undefined;
  const wanted = `:${key}`;
  for (const entry of value[1]) {
    const k = entry?.[0];
    if (Array.isArray(k) && k[0] === "keyword" && k[1] === wanted) return entry[1];
  }
  return undefined;
}

function scalar(value, tag) {
  return Array.isArray(value) && value[0] === tag ? value[1] : undefined;
}

function text(value) {
  return scalar(value, "string") ?? "";
}

function integer(value) {
  const n = scalar(value, "i64");
  return typeof n === "bigint" ? n : -1n;
}

function vectorPairs(value) {
  if (!Array.isArray(value) || value[0] !== "vector" || !Array.isArray(value[1])) return [];
  return value[1].map(item => ({
    name: text(mapValue(item, "name")),
    value: text(mapValue(item, "value")),
  })).filter(item => item.name !== "");
}

function utf8Length(value) {
  return new TextEncoder().encode(value).byteLength;
}

function clippedText(value, limit) {
  if (utf8Length(value) <= limit) return value;
  let end = value.length;
  while (end > 0) {
    const candidate = value.slice(0, end);
    if (utf8Length(candidate) <= limit && !/[\uD800-\uDBFF]$/u.test(candidate)) return candidate;
    end -= 1;
  }
  return "";
}

async function readResponseText(response, limit) {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let received = 0;
  let value = "";
  let truncated = false;
  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    const remaining = Math.max(0, limit - received);
    if (chunk.value.byteLength > remaining) {
      value += decoder.decode(chunk.value.subarray(0, remaining), { stream: true });
      truncated = true;
      await reader.cancel().catch(() => {});
      break;
    }
    received += chunk.value.byteLength;
    value += decoder.decode(chunk.value, { stream: true });
  }
  value += decoder.decode();
  return truncated ? `${value}\n\n[応答本文を表示上限で省略しました]` : value;
}

function encodedPathValue(value) {
  if (value === "." || value === "..") throw new Error("ドットだけのパス引数は使えません。");
  return encodeURIComponent(value)
    .replaceAll("!", "%21")
    .replaceAll("'", "%27")
    .replaceAll("(", "%28")
    .replaceAll(")", "%29")
    .replaceAll("~", "%7E")
    .replaceAll("*", "%2A");
}

let state = appModule.instantiateKotoba().init();
let requestSequence = 0;
let lastEffectId = 0n;
let mounted = true;

function renderWith(guest, next) {
  reconcileUiDocument(container, guest.view(next));
}

function readActors(stateValue) {
  const query = text(mapValue(stateValue, "applied-query")).trim();
  const role = text(mapValue(stateValue, "applied-role")).trim();
  const url = new URL("/api/fleet/search", API_BASE);
  url.searchParams.set("limit", String(PAGE_LIMIT));
  if (query !== "") url.searchParams.set("text", query);
  if (role !== "") url.searchParams.set("role", role);
  return url.href;
}

function readFacets() {
  const url = new URL("/api/fleet/facets", API_BASE);
  url.searchParams.set("field", "role");
  return url.href;
}

function dispatch(target, kind, value = "", limit = MAX_USER_EVENT_BYTES) {
  if (!mounted) return false;
  const bounded = clippedText(value, limit);
  const guest = appModule.instantiateKotoba();
  const next = guest.step(state, target, kind, bounded);
  if (next === state) return false;
  renderWith(guest, next);
  state = next;
  observeEffect(state);
  return true;
}

function projectActor(actor) {
  const field = key => typeof actor?.[key] === "string" ? actor[key] : "";
  return JSON.stringify({
    repo: field("repo"),
    name: field("name"),
    role: field("role"),
    domain: field("domain"),
    execution: field("execution"),
    status: field("status"),
    governor: field("governor"),
    maturity: field("maturity"),
    isicRev5: field("isic-rev5"),
    isic: field("isic"),
    iso3166: field("iso3166"),
    isco08: field("isco-08"),
    socialImpact: Array.isArray(actor?.["social-impact"]) ? actor["social-impact"] : [],
    requiredTechnologies: Array.isArray(actor?.["required-technologies"])
      ? actor["required-technologies"] : [],
    robotics: typeof actor?.robotics === "boolean" ? actor.robotics : null,
    referenceOnly: typeof actor?.["reference-only"] === "boolean" ? actor["reference-only"] : null,
    endpoint: field("endpoint"),
    remote: field("remote"),
    path: field("path"),
    revision: field("revision"),
    authorityLibrary: {
      repoName: typeof actor?.["authority-library"]?.["repo-name"] === "string"
        ? actor["authority-library"]["repo-name"] : "",
    },
  });
}

async function loadFleet(effectId) {
  const currentRequest = ++requestSequence;
  try {
    const response = await fetch(readActors(state), {
      method: "GET",
      credentials: "omit",
      redirect: "error",
      headers: { accept: "application/json" },
    });
    if (!response.ok) {
      if (currentRequest === requestSequence)
        dispatch("host", "fleet-error", `目録が ${response.status} を返しました。`, MAX_GUEST_EVENT_BYTES);
      return;
    }
    const body = await response.json();
    if (!Array.isArray(body?.actors) || !Number.isSafeInteger(body?.matched) || body.matched < 0) {
      if (currentRequest === requestSequence)
        dispatch("host", "fleet-error", "目録の応答を読めませんでした。", MAX_GUEST_EVENT_BYTES);
      return;
    }
    if (currentRequest !== requestSequence || integer(mapValue(state, "effect-id")) !== effectId) return;
    dispatch("host", "fleet-start", "", MAX_GUEST_EVENT_BYTES);
    const page = body.actors.slice(0, PAGE_LIMIT);
    for (const actor of page) {
      const projected = projectActor(actor);
      if (utf8Length(projected) > MAX_GUEST_EVENT_BYTES) {
        dispatch("host", "fleet-error", "目録の actor 情報が上限を超えています。", MAX_GUEST_EVENT_BYTES);
        return;
      }
      dispatch("host", "actor", projected, MAX_GUEST_EVENT_BYTES);
    }
    const unfiltered = text(mapValue(state, "applied-query")).trim() === ""
      && text(mapValue(state, "applied-role")).trim() === "";
    dispatch("host", "fleet-count", JSON.stringify({
      matched: String(body.matched),
      total: unfiltered ? String(body.matched) : "",
      shown: String(page.length),
    }), MAX_GUEST_EVENT_BYTES);
  } catch (error) {
    if (currentRequest === requestSequence)
      dispatch("host", "fleet-error",
        `目録に届きませんでした（${error?.message ?? "network error"}）。端末のネットワークを確認してください。`,
        MAX_GUEST_EVENT_BYTES);
  }
}

async function loadRoleFacets() {
  try {
    const response = await fetch(readFacets(), {
      method: "GET",
      credentials: "omit",
      redirect: "error",
      headers: { accept: "application/json" },
    });
    if (!response.ok) return;
    const body = await response.json();
    if (!Array.isArray(body?.values)
        || !body.values.every(item => typeof item?.value === "string"
          && Number.isSafeInteger(item?.count) && item.count >= 0)) return;
    dispatch("host", "role-facets", JSON.stringify({
      values: body.values.map(item => ({ value: item.value, count: String(item.count) })),
    }), MAX_GUEST_EVENT_BYTES);
  } catch {
    // The role menu is a best-effort narrowing aid; the fleet read reports the
    // user-visible network failure.
  }
}

function observeEffect(current) {
  const effect = text(mapValue(current, "effect"));
  if (!["search", "pairing-save", "terminal-request"].includes(effect)) return;
  const effectId = integer(mapValue(current, "effect-id"));
  if (effectId <= lastEffectId) return;
  lastEffectId = effectId;
  if (effect === "pairing-save") {
    queueMicrotask(() => {
      if (!mounted || integer(mapValue(state, "effect-id")) !== effectId) return;
      const pairing = {
        base: text(mapValue(state, "base-draft")).trim(),
        token: text(mapValue(state, "token-draft")).trim(),
      };
      try {
        window.localStorage.setItem("cloud.itonami.mobile.pairing.v1", JSON.stringify(pairing));
      } catch {
        // WebViews can deny storage; the in-memory pairing remains usable.
      }
      dispatch("host", "pairing-saved", "", MAX_GUEST_EVENT_BYTES);
    });
    return;
  }
  if (effect === "terminal-request") {
    queueMicrotask(() => {
      if (mounted && integer(mapValue(state, "effect-id")) === effectId)
        void sendTerminalRequest(effectId);
    });
    return;
  }
  queueMicrotask(() => {
    if (mounted && integer(mapValue(state, "effect-id")) === effectId)
      void loadFleet(effectId);
  });
}

function resolveTerminalUrl(current) {
  const method = text(mapValue(current, "request-method"));
  const template = text(mapValue(current, "request-template"));
  const offeredWrites = new Set([
    "/api/agent-bots/{id}/messages",
    "/p/{profile}/api/sessions",
    "/p/{profile}/api/sessions/{session}/messages",
  ]);
  if (method !== "GET" && method !== "POST") throw new Error("このHTTPメソッドは実行できません。");
  if (method === "POST" && !offeredWrites.has(template))
    throw new Error("この画面ではこの書き込みを許可していません。");

  let path = template;
  for (const { name, value } of vectorPairs(mapValue(current, "request-path-params"))) {
    const placeholder = `{${name}}`;
    if (!path.includes(placeholder)) throw new Error("コマンドのパス引数が一致しません。");
    path = path.replaceAll(placeholder, () => encodedPathValue(value));
  }
  if (/[{}]/u.test(path) || !path.startsWith("/")) throw new Error("コマンドのパスが不完全です。");

  const base = text(mapValue(current, "base")).trim();
  const baseUrl = new URL(base);
  if (baseUrl.protocol !== "https:" || baseUrl.username !== "" || baseUrl.password !== ""
      || baseUrl.pathname !== "/" || baseUrl.search !== "" || baseUrl.hash !== "")
    throw new Error("ingress には資格情報やパスを含まない HTTPS origin が必要です。");
  if (!path.startsWith("/") || path.startsWith("//")) throw new Error("コマンドのパスが不正です。");
  const url = new URL(path, `${baseUrl.origin}/`);
  if (url.origin !== baseUrl.origin) throw new Error("コマンドの接続先が ingress と一致しません。");
  for (const { name, value } of vectorPairs(mapValue(current, "request-query-params")))
    url.searchParams.set(name, value);
  return { method, url };
}

async function sendTerminalRequest(effectId) {
  const current = state;
  let request;
  let token;
  let headers;
  let body;
  try {
    request = resolveTerminalUrl(current);
    token = text(mapValue(current, "token")).trim();
    if (token === "") throw new Error("この端末は未ペアリングです。コマンドは送信していません。");
    headers = { accept: "application/json", authorization: `Bearer ${token}` };
    if (request.method === "POST") {
      body = text(mapValue(current, "request-body"));
      if (body === "") throw new Error("書き込みの本文がありません。");
      headers["content-type"] = "application/json";
    }
  } catch (error) {
    if (mounted && integer(mapValue(state, "effect-id")) === effectId)
      dispatch("host", "terminal-result", JSON.stringify({
        kind: "refused",
        status: "",
        text: error?.message ?? "コマンドを送信しませんでした。",
      }), MAX_GUEST_EVENT_BYTES);
    return;
  }
  try {
    const response = await fetch(request.url, {
      method: request.method,
      headers,
      body,
      credentials: "omit",
      redirect: "error",
      cache: "no-store",
    });
    const responseText = await readResponseText(response, MAX_TERMINAL_RESPONSE_BYTES);
    if (!mounted || integer(mapValue(state, "effect-id")) !== effectId) return;
    dispatch("host", "terminal-result", JSON.stringify({
      kind: "answered",
      status: String(response.status),
      text: responseText.trim() === "" ? "(空の応答)" : responseText,
    }), MAX_GUEST_EVENT_BYTES);
  } catch (error) {
    if (!mounted || integer(mapValue(state, "effect-id")) !== effectId) return;
    dispatch("host", "terminal-result", JSON.stringify({
      kind: "failed",
      status: "",
      text: `接続先に届きませんでした（${error?.message ?? "network error"}）。`,
    }), MAX_GUEST_EVENT_BYTES);
  }
}

function nominalTarget(node) {
  for (let cursor = node; cursor && cursor !== container; cursor = cursor.parentNode) {
    if (cursor.nodeType !== 1) continue;
    const target = cursor.getAttribute("data-k");
    if (target) return target;
  }
  return null;
}

function onEvent(event) {
  const target = nominalTarget(event.target);
  if (!target) return;
  const value = event.type === "keydown" ? event.key
    : typeof event.target?.value === "string" ? event.target.value : "";
  if (event.type === "keydown" && event.key === "Enter") event.preventDefault();
  dispatch(target, event.type, value);
  if (event.type === "click" && (target === "back" || target.startsWith("open-")
      || target.startsWith("pane-"))) window.scrollTo(0, 0);
}

for (const kind of ["click", "input", "change", "keydown"]) container.addEventListener(kind, onEvent);
window.addEventListener("kotobaShell:back", event => {
  const selected = text(mapValue(state, "selected"));
  const pane = text(mapValue(state, "pane"));
  if (selected !== "-1") {
    event.preventDefault();
    dispatch("back", "click");
    window.scrollTo(0, 0);
  } else if (pane === "about" || pane === "terminal") {
    event.preventDefault();
    dispatch("pane-fleet", "click");
    window.scrollTo(0, 0);
  }
});

renderWith(appModule.instantiateKotoba(), state);
let savedPairing = { base: "https://agent.itonami.cloud", token: "" };
try {
  const parsed = JSON.parse(window.localStorage.getItem("cloud.itonami.mobile.pairing.v1") ?? "null");
  if (parsed && typeof parsed.base === "string" && typeof parsed.token === "string")
    savedPairing = { base: parsed.base.trim(), token: parsed.token.trim() };
} catch {
  // Private WebViews can deny storage; render the unpaired terminal anyway.
}
dispatch("host", "pairing", JSON.stringify(savedPairing), MAX_GUEST_EVENT_BYTES);
observeEffect(state);
void loadRoleFacets();
