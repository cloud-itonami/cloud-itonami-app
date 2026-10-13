#!/usr/bin/env hy
;; Read-only registration completeness check for the itonami profile.
(import glob)
(import os)
(import sys)

(setv profile-dir (or (os.environ.get "ITONAMI_PROFILE_HOME")
                      (os.path.expanduser "~/.hermes/profiles/itonami")))
(setv docs-dir (os.path.join profile-dir "90-docs"))
(setv adr-path (os.path.join docs-dir "adr" "adr-20260115-01-islmapping.md"))
(setv business-dir (os.path.join docs-dir "business"))
(setv valueflows-dir (os.path.join docs-dir "valueflows"))
(setv adr-exists (os.path.isfile adr-path))
(setv business-files (glob.glob (os.path.join business-dir "*.datoms.edn")))
(setv valueflow-files (glob.glob (os.path.join valueflows-dir "*.datoms.edn")))
(setv expected-business ["bakery-cafe-cambodia-canvas.datoms.edn"
                         "salon-cambodia-canvas.datoms.edn"
                         "school-cambodia-canvas.datoms.edn"
                         "retail-cambodia-canvas.datoms.edn"
                         "restaurant-cambodia-canvas.datoms.edn"
                         "fitness-cambodia-canvas.datoms.edn"])
(setv found-business (lfor f business-files (os.path.basename f)))
(setv missing (lfor f expected-business :if (not (in f found-business)) f))
(setv complete (and adr-exists (not missing)))

(if complete
  (do
    (print "SUCCESS: All ISIC/ISCO registration artifacts present")
    (print f"- ADR: {adr-path}")
    (print f"- Business datoms: {(len business-files)} files")
    (print f"- Valueflows datoms: {(len valueflow-files)} files")
    (sys.exit 0))
  (do
    (print "REFUSED: ISIC/ISCO registration incomplete")
    (print f"ADR exists: {adr-exists}")
    (print f"Business datoms found: {(len found-business)}/6")
    (print f"Missing: {missing}")
    (print f"Valueflows datoms: {(len valueflow-files)} files")
    ;; Refusal is evidence, not a runtime failure; the agent should stop.
    (sys.exit 0)))
