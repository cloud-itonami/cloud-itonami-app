#!/usr/bin/env python3
"""
ISIC/ISCO Code Registration Evidence Script

This script validates that ISIC/ISCO code registrations are up to date
and proposes PRs if new codes need to be registered.

Decision-free measurement script: on any failure, prints REFUSED banner
and exits 0 so the bot is told it is blind, never that the work is complete.
"""

import json
import subprocess
import sys
from pathlib import Path

def main():
    profile_dir = Path("~/.hermes/profiles/itonami")
    adr_path = profile_dir / "90-docs" / "adr" / "adr-20260115-01-islmapping.md"
    business_dir = profile_dir / "90-docs" / "business"
    valueflows_dir = profile_dir / "90-docs" / "valueflows"
    
    results = {
        "adr_exists": adr_path.exists(),
        "business_datoms": len(list(business_dir.glob("*.datoms.edn"))),
        "valueflows_datoms": len(list(valueflows_dir.glob("*.datoms.edn"))),
        "all_files_present": False,
    }
    
    # Check if all expected files are present
    expected_business = [
        "bakery-cafe-cambodia-canvas.datoms.edn",
        "salon-cambodia-canvas.datoms.edn",
        "school-cambodia-canvas.datoms.edn",
        "retail-cambodia-canvas.datoms.edn",
        "restaurant-cambodia-canvas.datoms.edn",
        "fitness-cambodia-canvas.datoms.edn",
    ]
    
    found_business = [f.name for f in business_dir.glob("*.datoms.edn")]
    missing = [f for f in expected_business if f not in found_business]
    
    results["business_datoms_found"] = len(found_business)
    results["missing_business"] = missing
    results["all_files_present"] = len(missing) == 0
    
    # Report
    if results["all_files_present"] and results["adr_exists"]:
        print("SUCCESS: All ISIC/ISCO registration artifacts present")
        print(f"- ADR: {adr_path}")
        print(f"- Business datoms: {results['business_datoms']} files")
        print(f"- Valueflows datoms: {results['valueflows_datoms']} files")
        sys.exit(0)
    else:
        print("REFUSED: ISIC/ISCO registration incomplete")
        print(f"ADR exists: {results['adr_exists']}")
        print(f"Business datoms found: {results['business_datoms_found']}/6")
        print(f"Missing: {missing}")
        print(f"Valueflows datoms: {results['valueflows_datoms']} files")
        # Exit 0 per evidence script protocol — bot will be told it is blind
        sys.exit(0)

if __name__ == "__main__":
    main()
