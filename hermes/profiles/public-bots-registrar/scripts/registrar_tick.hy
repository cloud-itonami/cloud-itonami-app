#!/usr/bin/env hy
(import json os sys time urllib.request urllib.error)
(setv HERE
  ((. os.path dirname) ((. os.path abspath) __file__)))
(setv PROFILE ((. os.path dirname) HERE))
(setv OUT ((. os.path join) PROFILE "workspace" "registrar_ledger.jsonl"))
(setv STATE ((. os.path join) PROFILE "workspace" "state.json"))
(setv UA "public-bots-registrar/0.1 (+https://itonami.cloud)")
(setv BASE "https://itonami.cloud/api/marketplace/public-bots")

(defn fetch_json [url [timeout 25]]
  (setv req
   ((. urllib.request Request) url :headers {"User-Agent" UA  "Accept" "application/json"}))
  (with [r ((. urllib.request urlopen) req :timeout timeout)]
    (return (json.loads ((. (r.read) decode) "utf-8" "replace")))))

(setv surfaces 0)
(setv result {})
(try
  (setv m (fetch_json (+ BASE "/metrics")))
  (+= surfaces 1)
  (setv tenants (or (m.get "tenants") {}))
  (setv (get result "metrics")
    {"registryEntries"      (tenants.get "registryEntries")
     "selfRegisteredOwners" (tenants.get "selfRegisteredOwners")
     "externalTotal"        (tenants.get "externalTotal")})
  (except [e Exception] (setv (get result "metrics_error") (repr e))))
(try
  (setv c (fetch_json (+ BASE "/catalog")))
  (setv ids
    (lfor e (or (c.get "bots") [])
      (e.get "id")))
  (+= surfaces 1)
  (setv (get result "catalog_ids") ids)
  (setv (get result "awai_arb_in_live_catalog") (in "awai-arb" ids))
  (except [e Exception] (setv (get result "catalog_error") (repr e))))
(try
  (setv req
    ((. urllib.request Request) (+ BASE "/register") :headers {"User-Agent" UA}))
  ((. urllib.request urlopen) req :timeout 25)
  (setv (get result "register_surface") "200-unexpected")
  (+= surfaces 1)
  (except [e (. urllib.error HTTPError)]
    (+= surfaces 1)
    (setv (get result "register_surface") (% "http-%d" e.code)))
  (except [e Exception] (setv (get result "register_error") (repr e))))
(setv (get result "ts") (time.strftime "%Y-%m-%dT%H:%M:%S%z"))
(with [f (open OUT "a")]
  (f.write (+ (json.dumps result :ensure_ascii False) "\n")))
(setv remind_needed False)
(setv st {})
(when ((. os.path exists) STATE)
  (setv st (json.load (open STATE))))
(setv last (st.get "last_owner_reminder"))
(if last
  (do
    (setv age (- (time.time) last))
    (when (> age (* 7 86400)) (setv remind_needed True)))
  (do (setv remind_needed True)))
(setv (get st "last_tick") (get result "ts"))
(setv (get st "register_surface") (result.get "register_surface"))
(when (result.get "awai_arb_in_live_catalog")
  (setv (get st "registered_live") True)
  (setv remind_needed False))
(json.dump st (open STATE "w"))
(if remind_needed (do (print "REMIND-OWNER")) (do (print "REMIND-OK")))
(print (% "SCANNED\t%d" surfaces))
(print (+ "LEDGER\t" OUT))
(sys.exit (if (> surfaces 0) 0 3))
