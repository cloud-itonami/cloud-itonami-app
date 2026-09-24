#!/usr/bin/env python3
# public-bots-registrar tick: measure the live itonami.cloud public-bots registry
# state and decide the next owner action. Values are real HTTP responses only.
import json, os, sys, time, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
PROFILE = os.path.dirname(HERE)
OUT = os.path.join(PROFILE, "workspace", "registrar_ledger.jsonl")
STATE = os.path.join(PROFILE, "workspace", "state.json")
UA = "public-bots-registrar/0.1 (+https://itonami.cloud)"
BASE = "https://itonami.cloud/api/marketplace/public-bots"

def get(url, timeout=25):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))

surfaces = 0
result = {}

# 1) live metrics: is awai-arb visible in the registry yet?
try:
    m = get(BASE + "/metrics")
    surfaces += 1
    tenants = (m.get("tenants") or {})
    result["metrics"] = {
        "registryEntries": tenants.get("registryEntries"),
        "selfRegisteredOwners": tenants.get("selfRegisteredOwners"),
        "externalTotal": tenants.get("externalTotal"),
    }
except Exception as e:
    result["metrics_error"] = repr(e)

# 2) catalog endpoint (repo main already has the entry; live app may lag)
try:
    c = get(BASE + "/catalog")
    ids = [e.get("id") for e in (c.get("bots") or [])]
    surfaces += 1
    result["catalog_ids"] = ids
    result["awai_arb_in_live_catalog"] = ("awai-arb" in ids)
except Exception as e:
    result["catalog_error"] = repr(e)  # endpoint may not exist in the deployed version

# 3) register gate probe (GET is 405 by design; a 405 means the surface exists)
try:
    req = urllib.request.Request(BASE + "/register", headers={"User-Agent": UA})
    urllib.request.urlopen(req, timeout=25)
    result["register_surface"] = "200-unexpected"
    surfaces += 1
except urllib.error.HTTPError as e:
    surfaces += 1
    result["register_surface"] = "http-%d" % e.code
except Exception as e:
    result["register_error"] = repr(e)

result["ts"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
with open(OUT, "a") as f:
    f.write(json.dumps(result, ensure_ascii=False) + "\n")

# owner reminder bookkeeping: email once per 7 days while passkey is still pending
remind_needed = False
st = {}
if os.path.exists(STATE):
    st = json.load(open(STATE))
last = st.get("last_owner_reminder")
if last:
    age = time.time() - last
    if age > 7 * 86400:
        remind_needed = True
else:
    remind_needed = True
st["last_tick"] = result["ts"]
st["register_surface"] = result.get("register_surface")
if result.get("awai_arb_in_live_catalog"):
    st["registered_live"] = True
    remind_needed = False
json.dump(st, open(STATE, "w"))
if remind_needed:
    print("REMIND-OWNER")
else:
    print("REMIND-OK")
print("SCANNED\t%d" % surfaces)
print("LEDGER\t" + OUT)
sys.exit(0 if surfaces > 0 else 3)
