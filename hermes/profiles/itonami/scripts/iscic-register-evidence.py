#!/usr/bin/env python3
"""Presence-only ISIC/ISCO artifact check.

This script measures expected file presence in the repository at the job workdir.
It does not validate code assignments, create files, or open a pull request.
"""

from pathlib import Path

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

    adr_present = adr_path.is_file()
    missing_business = [
        name for name in EXPECTED_BUSINESS
        if not (business_dir / name).is_file()
    ]
    business_present = len(EXPECTED_BUSINESS) - len(missing_business)
    valueflows_count = len(list(valueflows_dir.glob("*.datoms.edn")))
    expected_valueflows = len(EXPECTED_BUSINESS)

    missing_items = []
    if not adr_present:
        missing_items.append("90-docs/adr/adr-20260115-01-islmapping.md")
    missing_items.extend(
        f"90-docs/business/{name}" for name in missing_business
    )
    if valueflows_count < expected_valueflows:
        missing_items.append(
            f"Valueflows datom count below minimum: "
            f"{valueflows_count}/{expected_valueflows}"
        )

    if missing_items:
        print("REFUSED: ISIC/ISCO artifact presence is incomplete.")
    else:
        print("OBSERVED: expected artifact presence threshold is met.")

    print(f"ADR present: {adr_present}")
    print(f"Business datoms present: {business_present}/{len(EXPECTED_BUSINESS)}")
    print(f"Valueflows datom count: {valueflows_count}/{expected_valueflows} minimum")
    print(f"Missing items: {missing_items or 'none'}")
    print("Limit: presence only; contents and code assignments were not validated.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
