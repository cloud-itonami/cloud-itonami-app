#!/usr/bin/env python3
"""transport_evidence.py — read-only transport efficiency data collection.

Cron-facing evidence script. Pulls the official World Bank LPI observations
and country metadata directly, reporting
MEASURED/UNMEASURED per signal, never inventing values.

Fix 2026-09-20: the old URL /v2/indicator/LP.LPI.OVRL.XQ?per_page=20 returns
indicator METADATA only (total=1, no observation rows), so try_lpi() always
returned empty -> worldbank_lpi was permanently UNMEASURED. The working shape
is /v2/country/all/indicator/... with per_page=300 and mrnev=1. Aggregates
(region=Aggregates, e.g. AFE/AFW/ARB) are excluded via the /v2/country list.
"""
import json
import sys
import math
from datetime import datetime, timezone
import urllib.request

LPI_URL = ("https://api.worldbank.org/v2/country/all/indicator/LP.LPI.OVRL.XQ"
           "?format=json&per_page=300&mrnev=1")
COUNTRY_URL = "https://api.worldbank.org/v2/country?format=json&per_page=400"

def print_sep(title):
    print(f"=== {title} ===")

def try_lpi():
    try:
        req = urllib.request.Request(LPI_URL, headers={"User-Agent": "itonami-evidence/1.0"})
        with urllib.request.urlopen(req, timeout=20) as r:
            rows_all = json.load(r)
        rows = rows_all[1]
        countries = set()
        try:
            c_req = urllib.request.Request(COUNTRY_URL, headers={"User-Agent": "itonami-evidence/1.0"})
            with urllib.request.urlopen(c_req, timeout=20) as r:
                c_all = json.load(r)
            countries = {c["id"] for c in c_all[1]
                         if c.get("region", {}).get("value")
                         and c["region"]["value"] != "Aggregates"}
            if not countries:
                return None
        except Exception:
            return None  # country classification is required; never label aggregates as countries
        latest = {}
        for row in rows:
            c = row.get("countryiso3code")
            v = row.get("value")
            d = row.get("date")
            if (not isinstance(c, str) or len(c) != 3 or c not in countries
                    or not isinstance(d, str) or not d.isdigit()
                    or not isinstance(v, (int, float)) or isinstance(v, bool)
                    or not math.isfinite(v) or not 0 <= v <= 5
                    or row.get("indicator", {}).get("id") != "LP.LPI.OVRL.XQ"):
                continue
            if c not in latest or d > latest[c][1]:
                latest[c] = (v, d)
        return latest
    except Exception:
        return None

def main():
    print_sep("TRANSPORT EFFICIENCY DATA")

    print(f"MEASURE\tobserved_at\t{datetime.now(timezone.utc).isoformat()}")

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
