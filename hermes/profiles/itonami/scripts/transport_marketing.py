#!/usr/bin/env python3
"""Marketing and outreach analysis for cloud-itonami.

Collects market signals, potential customer profiles, and drafts outreach messages.
Decision-free: only outputs MEASURE lines and drafted messages for human review.
"""
import os
import sys
import json
from datetime import datetime

def refuse(why):
    print("REFUSED — no marketing evidence was gathered this run.")
    print(why)
    sys.exit(2)

def main():
    # Marketing data sources (placeholder paths - would be populated by crawlers)
    customer_leads = "/tmp/itonami_leads.json"
    market_reports = "/tmp/itonami_market_reports.json"
    
    print("=== MARKETING SIGNALS ===")
    
    # Check for lead data
    if os.path.exists(customer_leads):
        try:
            with open(customer_leads, 'r') as f:
                leads = json.load(f)
            if isinstance(leads, dict):
                # itonami_leads_v1 wrapper: count records, not top-level keys
                leads = leads.get("records", list(leads.values()))
            print(f"MEASURE\tcustomer_leads_count\t{len(leads)}")
            print(f"MEASURE\tleads_timestamp\t{datetime.now().isoformat()}")
        except Exception as e:
            print(f"MEASURE\tcustomer_leads_count\tUNMEASURED")
    else:
        print(f"MEASURE\tcustomer_leads_count\tUNMEASURED")
        print(f"MEASURE\tleads_reason\tfile not found: {customer_leads}")
    
    # Check for market reports
    if os.path.exists(market_reports):
        try:
            with open(market_reports, 'r') as f:
                reports = json.load(f)
            print(f"MEASURE\tmarket_reports_count\t{len(reports)}")
        except Exception as e:
            print(f"MEASURE\tmarket_reports_count\tUNMEASURED")
    else:
        print(f"MEASURE\tmarket_reports_count\tUNMEASURED")
    
    print()
    print("=== OUTREACH DRAFTS (for human review) ===")
    print("Draft: itonami.cloud value proposition for logistics sector")
    print("Target: ISIC 49 (Land Transport) companies with >50 employees")
    print("Message: itonami.cloud offers L3 business automation for logistics -")
    print("         real-time route optimization, cargo tracking integration,")
    print("         and automated compliance reporting.")
    print("Status: DRAFT - awaiting human send approval")

if __name__ == "__main__":
    main()
