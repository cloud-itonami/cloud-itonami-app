#!/usr/bin/env python3
"""Collect the latest verified World Bank LPI rows for Itonami transport.

Python launcher restored 2026-10-18 from the surviving Hy source
`scripts/hermes-itonami-bots/transport_evidence.hy` (com-junkawasaki @
a29f8709c61, "bots: migrate Itonami transport evidence jobs to Hy"). Logic is a
faithful port: same World Bank country filter and numeric validation, same
MEASURE/OBS output contract. Hermes-owned profiles run the Python launcher.
"""

import datetime
import json
import math
import urllib.request

LPI_URL = "https://api.worldbank.org/v2/country/all/indicator/LP.LPI.OVRL.XQ?format=json&per_page=300&mrnev=1"
COUNTRY_URL = "https://api.worldbank.org/v2/country?format=json&per_page=400"
UA = "itonami-evidence/1.0"
INDICATOR = "LP.LPI.OVRL.XQ"


def fetch_json(url):
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, headers={"User-Agent": UA}), timeout=20
        ) as response:
            return json.loads(response.read())
    except Exception:
        return None


def try_lpi():
    try:
        data = fetch_json(LPI_URL)
        if not data or "_error" in data:
            raise RuntimeError("LPI response unavailable")
        countries_data = fetch_json(COUNTRY_URL)
        if not countries_data or "_error" in countries_data:
            raise RuntimeError("country metadata unavailable")
        countries = {
            country.get("id")
            for country in countries_data[1]
            if country.get("region", {}).get("value")
            and country.get("region", {}).get("value") != "Aggregates"
        }
        latest = {}
        if not countries:
            raise RuntimeError("country classification unavailable")
        for row in data[1]:
            code = row.get("countryiso3code")
            value = row.get("value")
            date = row.get("date")
            indicator = row.get("indicator", {}).get("id")
            if (
                isinstance(code, str)
                and len(code) == 3
                and code in countries
                and isinstance(date, str)
                and date.isdigit()
                and (isinstance(value, int) or isinstance(value, float))
                and not isinstance(value, bool)
                and math.isfinite(value)
                and 0 <= value <= 5
                and indicator == INDICATOR
            ):
                if code not in latest or date > latest[code][1]:
                    latest.update({code: [value, date]})
        return latest
    except Exception:
        return None


def main():
    print("=== TRANSPORT EFFICIENCY DATA ===")
    print("MEASURE\tobserved_at\t{}".format(
        datetime.datetime.now(datetime.timezone.utc).isoformat()))
    lpi = try_lpi()
    if lpi:
        print("MEASURE\tworldbank_lpi\tMEASURED")
        print(f"MEASURE\tworldbank_lpi_url\t{LPI_URL}")
        for code in sorted(lpi)[:10]:
            value = lpi[code][0]
            year = lpi[code][1]
            print("OBS\t{}\t{}\t{}".format(code, value, year))
    else:
        print("MEASURE\tworldbank_lpi\tUNMEASURED")
        print(f"MEASURE\tworldbank_lpi_url\t{LPI_URL}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
