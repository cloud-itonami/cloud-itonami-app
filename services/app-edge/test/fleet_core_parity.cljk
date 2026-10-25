(ns fleet-core-parity
  "fleet-core, run under the CLJS reader against the real 1,215-actor catalog.

  This is the gate that keeps the split honest. `cloud.itonami.app.fleet-core`
  is .cljc and both surfaces call it, so parity is structural — but structural
  parity is worth nothing if the namespace does not actually load and run on
  the CLJS side, or if the catalog stops parsing there. That is what this
  checks, on real data rather than a fixture: a fixture would keep passing
  after the generator changed shape."
  (:require [cljs.reader :as reader]
            ["fs" :as fs]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.fleet-core :as fleet]))

;; `fleet-core` runs the shipped decision core rather than reimplementing it,
;; and ClojureScript has no classpath to read that from. Node has a filesystem,
;; so this hands the seam the SAME bytes `io/resource` gives the JVM — the
;; Worker does the same thing with its asset binding. Without it every
;; assertion below dies at the first `validate-catalog`, which is exactly what
;; happened, unnoticed, from the day the delegation landed.
(oracle/set-resource-loader!
 (fn [path]
   (let [file (str "../../resources/" path)]
     (when (.existsSync fs file)
       (.readFileSync fs file "utf8")))))

(def ^:private catalog
  (fleet/validate-catalog
   (reader/read-string
    (fs/readFileSync "../../resources/itonami-fleet-catalog.edn" "utf8"))))

(def ^:private actors (:actors catalog))

(defonce ^:private failures (atom 0))

(defn- check [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))

(defn- check-true [label actual]
  (check label true (boolean actual)))

(println "fleet-core parity —" (count actors) "actors")

;; The catalog says how many it has. If the reader dropped records, every
;; other assertion below would still pass on a truncated fleet.
(check "count matches the catalog's own :count" (:count catalog) (count actors))

;; Empty criteria constrain nothing.
(check "empty criteria returns every actor" (count actors)
       (count (fleet/search actors {})))

;; The regression this filter was written for: :execution was accepted by the
;; tool descriptor before search understood it, so it silently matched
;; everything — "show me the resident ones" answered "all 1,206".
(let [resident (fleet/search actors {:execution :resident})]
  (check-true "an execution filter actually constrains"
              (< (count resident) (count actors)))
  (check-true "every result honours the execution filter"
              (every? #(= :resident (:execution %)) resident)))

;; :callable? is derived, and the catalog counts it independently at
;; generation time. Two ways of counting the same thing that must agree.
(check "callable? agrees with the catalog's :callable-count"
       (:callable-count catalog)
       (count (fleet/search actors {:callable? true})))

(check "callable? false is the complement, not everything"
       (count actors)
       (+ (count (fleet/search actors {:callable? true}))
          (count (fleet/search actors {:callable? false}))))

;; Text search is case-insensitive over id/name/domain.
(let [lower (fleet/search actors {:text "marketplace"})
      upper (fleet/search actors {:text "MARKETPLACE"})]
  (check-true "text search finds something" (pos? (count lower)))
  (check "text search is case-insensitive" (count lower) (count upper)))

;; ANDed, not ORed.
(let [both (fleet/search actors {:text "marketplace" :callable? true})]
  (check-true "criteria are ANDed"
              (<= (count both) (count (fleet/search actors {:text "marketplace"})))))

;; isic matches whichever revision an actor happens to code in. Taking the
;; sample from :isic-rev5 specifically is the point: a search that only looked
;; at :isic would return nothing here and read as "no such industry".
(let [code (first (keep :isic-rev5 actors))
      hits (fleet/search actors {:isic code})]
  (check-true "an isic-rev5 code is reachable through :isic" (pos? (count hits)))
  (check-true "every isic hit carries that code in some revision"
              (every? #(= code (or (:isic %) (:isic-rev5 %) (:isic-rev4 %))) hits)))

;; Facets are ordered most-common-first and sum to the number of actors that
;; carry the field at all.
(let [f (fleet/facets actors :execution)]
  (check-true "facets are non-empty" (pos? (count f)))
  (check-true "facets are ordered by descending count"
              (= (map second f) (sort > (map second f))))
  (check "facet counts sum to the actors carrying the field"
         (count (keep :execution actors))
         (reduce + (map second f))))

(if (pos? @failures)
  (do (println "\nFAILED:" @failures) (js/process.exit 1))
  (println "\nall checks passed"))
