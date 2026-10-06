#!/usr/bin/env python3
"""transport_evidence.py — read-only transport efficiency data collection.

Cron-facing evidence script. Reads local pre-fetched data (/tmp/*.json) when
available; otherwise attempts a live HTTP pull (World Bank LPI) and reports
MEASURED/UNMEASURED per signal, never inventing values.

Fix 2026-09-20: the old URL /v2/indicator/LP.LPI.OVRL.XQ?per_page=20 returns
indicator METADATA only (total=1, no observation rows), so try_lpi() always
returned empty -> worldbank_lpi was permanently UNMEASURED. The working shape
is /v2/country/all/indicator/... with per_page=300 and mrnev=1. Aggregates
(region=Aggregates, e.g. AFE/AFW/ARB) are excluded via the /v2/country list.
"""
import glob
import json
import sys
import urllib.request

LPI_URL = ("https://api.worldbank.org/v2/country/all/indicator/LP.LPI.OVRL.XQ"
           "?format=json&per_page=300&mrnev=1")
COUNTRY_URL = "https://api.worldbank.org/v2/country?format=json&per_page=400"
LOCAL_GLOBS = ["/tmp/*.json"]

def print_sep(title):
    print(f"=== {title} ===")

def try_local_data():
    for pattern in LOCAL_GLOBS:
        for path in glob.glob(pattern):
            try:
                with open(path) as f:
                    data = json.load(f)
                if isinstance(data, dict) and any(
                    k in json.dumps(data)[:2000].lower() for k in ("lpi", "transport", "logistics")
                ):
                    return path, data
            except Exception:
                continue
    return None, None

def try_lpi():
    try:
        req = urllib.request.Request(LPI_URL, headers={"User-Agent": "itonami-evidence/1.0"})
        with urllib.request.urlopen(req, timeout=20) as r:
            rows_all = json.load(r)
        rows = rows_all[1]
        aggregates = set()
        try:
            c_req = urllib.request.Request(COUNTRY_URL, headers={"User-Agent": "itonami-evidence/1.0"})
            with urllib.request.urlopen(c_req, timeout=20) as r:
                c_all = json.load(r)
            aggregates = {c["id"] for c in c_all[1]
                          if c.get("region", {}).get("value") == "Aggregates"}
        except Exception:
            pass  # fail-open on the country list; primary data still returns
        latest = {}
        for row in rows:
            c = row.get("countryiso3code")
            v = row.get("value")
            d = row.get("date")
            if v is None or not c or c in aggregates:
                continue
            if c not in latest or d > latest[c][1]:
                latest[c] = (v, d)
        return latest
    except Exception:
        return None

def main():
    print_sep("TRANSPORT EFFICIENCY DATA")

    path, local = try_local_data()
    if local is not None:
        print(f"MEASURE\tlocal_transport_data\tMEASURED")
        print(f"MEASURE\tlocal_transport_data_url\t{path}")
    else:
        print("MEASURE\tlocal_transport_data\tUNMEASURED")

    lpi = try_lpi()
    if lpi:
        print("MEASURE\tworldbank_lpi\tMEASURED")
        print(f"MEASURE\tworldbank_lpi_url\t{LPI_URL}")
        for c in sorted(lpi)[:10]:
            v, yr = lpi[c]
            print(f"OBS\t{c}\t{v}\t{yr}")
        sys.exit(0)
    print("MEASURE\tworldbank_lpi\tUNMEASURED")
    print(f"MEASURE\tworldbank_lpi_url\t{LPI_URL}")

if __name__ == "__main__":
    main()
