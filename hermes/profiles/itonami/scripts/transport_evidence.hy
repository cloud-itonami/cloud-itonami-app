#!/usr/bin/env hy
"transport_evidence.hy — read-only transport efficiency data collection.

Cron-facing evidence script. Pulls official World Bank LPI observations and
country metadata directly, reporting MEASURED/UNMEASURED without inventing
values.

Fix 2026-09-20: the old URL /v2/indicator/LP.LPI.OVRL.XQ?per_page=20 returns
indicator METADATA only (total=1, no observation rows), so try_lpi() always
returned empty -> worldbank_lpi was permanently UNMEASURED. The working shape
is /v2/country/all/indicator/... with per_page=300 and mrnev=1. Aggregates
(region=Aggregates, e.g. AFE/AFW/ARB) are excluded via the /v2/country list.
"
(import json)
(import sys)
(import math)
(import datetime [datetime timezone])
(import urllib.request)
(setv LPI_URL "https://api.worldbank.org/v2/country/all/indicator/LP.LPI.OVRL.XQ?format=json&per_page=300&mrnev=1")
(setv COUNTRY_URL "https://api.worldbank.org/v2/country?format=json&per_page=400")

(defn print_sep [title]
  (print f"=== {title} ==="))

(defn try_lpi []
  (try
   (setv req ((. urllib.request Request) LPI_URL :headers {"User-Agent" "itonami-evidence/1.0"}))
   (with [r ((. urllib.request urlopen) req :timeout 20)]
     (setv rows_all (json.load r)))
   (setv rows (get rows_all 1))
   (setv countries (set))
   (try
     (setv c_req ((. urllib.request Request) COUNTRY_URL :headers {"User-Agent" "itonami-evidence/1.0"}))
     (with [r ((. urllib.request urlopen) c_req :timeout 20)]
       (setv c_all (json.load r)))
     (setv countries
       (sfor c (get c_all 1)
         :if (and
               ((. (c.get "region" {}) get) "value")
               (!= ((. (c.get "region" {}) get) "value") "Aggregates"))
         (get c "id")))
     (when (not countries) (return None))
     (except [Exception] (return None)))
   (setv latest {})
   (for [row rows]
     (setv c (row.get "countryiso3code"))
     (setv v (row.get "value"))
     (setv d (row.get "date"))
     (when (or
             (not (isinstance c str))
             (!= (len c) 3)
             (not-in c countries)
             (not (and (isinstance d str) ((. d isdigit))))
             (not (isinstance v #(int float)))
             (isinstance v bool)
             (not (math.isfinite v))
             (< v 0)
             (> v 5)
             (!= ((. (row.get "indicator" {}) get) "id") "LP.LPI.OVRL.XQ"))
       (continue))
     (when (or (not-in c latest) (> d (get (get latest c) 1)))
       (setv (get latest c) #(v d))))
   (return latest)
   (except [Exception] (return None))))

(defn main []
  (print_sep "TRANSPORT EFFICIENCY DATA")
  (print f"MEASURE\tobserved_at\t{((. ((. datetime now) (. timezone utc)) isoformat))}")
  (setv lpi (try_lpi))
  (when lpi
    (print "MEASURE\tworldbank_lpi\tMEASURED")
    (print f"MEASURE\tworldbank_lpi_url\t{LPI_URL}")
    (for [c (cut (sorted lpi) 10)]
      (setv #(v yr) (get lpi c))
      (print f"OBS\t{c}\t{v}\t{yr}"))
    (sys.exit 0))
  (print "MEASURE\tworldbank_lpi\tUNMEASURED")
  (print f"MEASURE\tworldbank_lpi_url\t{LPI_URL}"))

(when (= __name__ "__main__") (main))
