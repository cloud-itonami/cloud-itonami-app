(ns cloud.itonami.app.fleet-core
  "The fleet directory's decisions, over actors handed in rather than read.

  `cloud.itonami.app.fleet` used to own both halves: how to reach the catalog
  (a JVM classpath resource) and what a directory query means. Only the first
  half is platform-bound. Splitting them here is what lets the Cloudflare
  Worker answer the same query as the JVM server without a second
  implementation — parity is structural rather than something a test has to
  keep discovering (ADR-2608081500).

  Every function takes the actor collection. Nothing in this namespace reads,
  fetches, caches or probes: an address is data here, never something to call.
  `probe*` and the health machinery stay on the platform side, because
  measuring reachability is exactly the part that differs."
  (:require [clojure.string :as str]))

(def schema "cloud.itonami.fleet-catalog.v1")

(defn validate-catalog
  "Return the catalog, or throw when it is not the schema this code reads.

  A catalog whose shape drifted is worse than a missing one: it answers
  queries, and the answers are wrong in ways no caller can see."
  [c]
  (when-not (= schema (:schema c))
    (throw (ex-info "fleet catalog schema mismatch"
                    {:type :fleet/schema-mismatch
                     :expected schema :found (:schema c)})))
  c)

(defn callable?
  "True when this actor declares an address. Absent means not deployed, or
  deployed with no route — not 'unknown, try it and see'."
  [actor]
  (some? (:endpoint actor)))

(defn probeable?
  "True when the actor names a health path. A callable actor need not be
  probeable: the Pages actors serve a real API under /api/* and have no health
  endpoint at all, so there is nothing honest to probe."
  [actor]
  (and (callable? actor) (some? (:health-path actor))))

(defn- matches-text? [actor q]
  (let [q (str/lower-case q)]
    (some (fn [v] (and v (str/includes? (str/lower-case (str v)) q)))
          [(:id actor) (:name actor) (:domain actor)])))

(defn- isic-of [actor]
  (or (:isic actor) (:isic-rev5 actor) (:isic-rev4 actor)))

(defn actor-by-repo
  "Look up by repository directory, which is unique. Prefer this over
  find-by-id: :id collides for three actors and cannot resolve them."
  [actors repo]
  (first (filter #(= repo (:repo %)) actors)))

(defn find-by-id
  "Every actor declaring this id — usually one, occasionally two. Returns a
  vector rather than a single record because returning the first match would
  make a collision look like a clean answer."
  [actors id]
  (filterv #(= id (:id %)) actors))

(defn search
  "Filter the directory. All criteria are ANDed; omitted criteria do not
  constrain. `:isic` matches whichever revision an actor happens to code in —
  the fleet uses :isic, :isic-rev4 and :isic-rev5 inconsistently, and making a
  caller know which would leak that inconsistency into every query."
  [actors {:keys [text domain governor maturity status isic iso3166 execution role]
           want-callable :callable?}]
  (cond->> actors
    text      (filter #(matches-text? % text))
    domain    (filter #(= domain (:domain %)))
    governor  (filter #(= governor (:governor %)))
    maturity  (filter #(= maturity (:maturity %)))
    status    (filter #(= status (:status %)))
    isic      (filter #(= isic (isic-of %)))
    iso3166   (filter #(= iso3166 (:iso3166 %)))
    ;; :execution and :role were accepted by the tool descriptor before search
    ;; understood them, so an execution filter silently matched everything —
    ;; a query that answers "all 1,206" to "show me the resident ones".
    execution (filter #(= execution (:execution %)))
    role      (filter #(= role (:role %)))
    ;; bound as want-callable so the criterion does not shadow callable?
    (some? want-callable) (filter #(= (boolean want-callable) (callable? %)))
    true      vec))

(defn facets
  "Value → count for one field, most common first. Drives the directory's
  filter UI without hardcoding a list that would drift from the fleet."
  [actors field]
  (->> actors
       (keep field)
       frequencies
       (sort-by (juxt (comp - val) (comp str key)))
       vec))
