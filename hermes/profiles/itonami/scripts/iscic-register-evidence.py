#!/usr/bin/env python3
"""Presence-only ISIC/ISCO artifact check.

This script measures expected file presence in the repository at the job workdir.
It does not validate code assignments, create files, or open a pull request.
"""

from pathlib import Path
import sys

EXPECTED_BUSINESS = (
    "bakery-cafe-cambodia-canvas.datoms.edn",
    "salon-cambodia-canvas.datoms.edn",
    "school-cambodia-canvas.datoms.edn",
    "retail-cambodia-canvas.datoms.edn",
    "restaurant-cambodia-canvas.datoms.edn",
    "fitness-cambodia-canvas.datoms.edn",
)


def main() -> int:
    repo_root = Path.cwd()
    if not (repo_root / ".git").exists():
        print("REFUSED: workdir is not a Git checkout")
        return 0

    docs = repo_root / "90-docs"
    adr_path = docs / "adr" / "adr-20260115-01-islmapping.md"
    business_dir = docs / "business"
    valueflows_dir = docs / "valueflows"

    missing_business = [
        name for name in EXPECTED_BUSINESS
        if not (business_dir / name).is_file()
    ]
    valueflows_count = len(list(valueflows_dir.glob("*.datoms.edn")))
    expected_valueflows = len(EXPECTED_BUSINESS)
    complete = (
        adr_path.is_file()
        and not missing_business
        and valueflows_count >= expected_valueflows
    )

    if complete:
        print("OBSERVED: expected ADR, six business datoms, and six or more Valueflows datoms are present.")
        print("Code assignments and Valueflows contents were not validated.")
    else:
        print("REFUSED: ISIC/ISCO registration evidence is incomplete or unavailable.")
        print(f"ADR present: {adr_path.is_file()}")
        print(f"Business datoms present: {len(EXPECTED_BUSINESS) - len(missing_business)}/{len(EXPECTED_BUSINESS)}")
        print(f"Missing business files: {missing_business}")
        print(f"Valueflows datom count: {valueflows_count}/{expected_valueflows} minimum")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
