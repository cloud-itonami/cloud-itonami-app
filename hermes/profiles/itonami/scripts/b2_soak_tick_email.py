#!/usr/bin/env python3
"""Email wrapper for the itonami/b2-soak-tick watchdog.

Runs the original b2_soak_tick.py unchanged, preserves its stdout/stderr for the
normal cron delivery, and additionally emails non-empty output to the operator
via email_report.py. Exit code is the original's.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "b2_soak_tick.py")
EMAIL = "/Users/junkawasaki/.hermes/scripts/email_report.py"

cmd = ["bash", TARGET] if TARGET.endswith(".sh") else [sys.executable, TARGET]
r = subprocess.run(cmd, capture_output=True, text=True, timeout=1200)
out = ((r.stdout or "") + (r.stderr or "")).strip()
if out:
    print(out)
    try:
        subprocess.run([sys.executable, EMAIL, "--subject",
                        "[hermes] itonami/b2-soak-tick watchdog"],
                       input=out, text=True, capture_output=True, timeout=120)
    except Exception:
        pass  # mail failure must not change the watchdog verdict
sys.exit(r.returncode)
