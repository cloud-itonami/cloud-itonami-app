#!/usr/bin/env hy
"Marketing and outreach analysis for cloud-itonami.

Collects market signals, potential customer profiles, and drafts outreach messages.
Decision-free: only outputs MEASURE lines and drafted messages for human review.
"
(import os)
(import sys)
(import json)
(import datetime [datetime])

(defn refuse [why]
  (print "REFUSED — no marketing evidence was gathered this run.")
  (print why)
  (sys.exit 2))

(defn main []
  (setv customer_leads "/tmp/itonami_leads.json")
  (setv market_reports "/tmp/itonami_market_reports.json")
  (print "=== MARKETING SIGNALS ===")
  (if ((. os.path exists) customer_leads)
    (do
      (try
        (with [f (open customer_leads "r")]
          (setv leads (json.load f)))
        (when (isinstance leads dict)
          (setv leads (leads.get "records" (list (leads.values)))))
        (print f"MEASURE\tcustomer_leads_count\t{(len leads)}")
        (print f"MEASURE\tleads_timestamp\t{((. (datetime.now) isoformat))}")
        (except [e Exception] (print "MEASURE\tcustomer_leads_count\tUNMEASURED"))))
    (do (print "MEASURE\tcustomer_leads_count\tUNMEASURED") (print f"MEASURE\tleads_reason\tfile not found: {customer_leads}")))
  (if ((. os.path exists) market_reports)
    (do
      (try
        (with [f (open market_reports "r")]
          (setv reports (json.load f)))
        (print f"MEASURE\tmarket_reports_count\t{(len reports)}")
        (except [e Exception] (print "MEASURE\tmarket_reports_count\tUNMEASURED"))))
    (do (print "MEASURE\tmarket_reports_count\tUNMEASURED")))
  (print)
  (print "=== OUTREACH DRAFTS (for human review) ===")
  (print "Draft: itonami.cloud value proposition for logistics sector")
  (print "Target: ISIC 49 (Land Transport) companies with >50 employees")
  (print "Message: itonami.cloud offers L3 business automation for logistics -")
  (print "         real-time route optimization, cargo tracking integration,")
  (print "         and automated compliance reporting.")
  (print "Status: DRAFT - awaiting human send approval"))

(when (= __name__ "__main__") (main))