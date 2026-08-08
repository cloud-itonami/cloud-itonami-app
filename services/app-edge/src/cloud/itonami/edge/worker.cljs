(ns cloud.itonami.edge.worker
  "cloud-itonami-app's read surface, on Cloudflare Workers.

  Slice 1 of ADR-2608081500: the fleet directory and nothing else. It is the
  read-only corner of the app, which is what makes it the right first cut —
  it needs none of the three things Workers does not have (a filesystem, a
  subprocess, a thread), so it proves the shadow-cljs → Worker → real-data
  path without any of them being papered over.

  What a query MEANS is not decided here. `cloud.itonami.app.fleet-core` is
  the same .cljc namespace the JVM server calls, so parity is structural: the
  two surfaces cannot disagree about what `?execution=resident` selects,
  because there is one implementation of it."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [cloud.itonami.app.fleet-core :as fleet]))

;; ── catalog ──────────────────────────────────────────────────────────
;;
;; 791 KB of EDN, parsed once per isolate and held here.
;;
;; Parsed lazily, on the first request that needs it, NOT at module scope.
;; Cloudflare gives module-scope evaluation a much smaller CPU budget than a
;; request gets, and a 1,215-actor parse is not obviously inside it. Doing it
;; here also keeps /health honest: it answers without touching the catalog, so
;; "the Worker is up" and "the catalog is readable" stay separate facts.

(defonce ^:private catalog-cache (atom nil))

(defn- load-catalog!
  "Promise of the validated catalog. Second and later callers get the cached
  value; a failed load is not cached, so a transient asset error does not
  poison the isolate for its lifetime.

  `^js` is load-bearing, not decoration: without it the compiler cannot infer
  a target type for `.-ASSETS`, and release optimizations are free to rename
  that property — the binding would be `undefined` at runtime in a build that
  compiled without error."
  [^js env]
  (if-some [c @catalog-cache]
    (js/Promise.resolve c)
    (-> (.fetch (.-ASSETS env)
                (js/Request. "https://assets.local/itonami-fleet-catalog.edn"))
        (.then (fn [r]
                 (when-not (.-ok r)
                   (throw (ex-info "fleet catalog asset unreadable"
                                   {:type :fleet/catalog-missing
                                    :status (.-status r)})))
                 (.text r)))
        (.then (fn [text]
                 (let [c (fleet/validate-catalog (reader/read-string text))]
                   (reset! catalog-cache c)
                   c))))))

;; ── request → criteria ───────────────────────────────────────────────
;;
;; The catalog codes some fields as keywords and some as strings, and which is
;; which is not a detail a caller should have to know. `:isic` is "0126" and
;; `:execution` is :on-demand, so a single coercion rule would break one of
;; them silently — the query would simply match nothing and look like an empty
;; fleet rather than a type error.

(def ^:private keyword-params [:domain :governor :maturity :status :execution :role])
(def ^:private string-params  [:text :isic :iso3166])

(defn- criteria
  "Search criteria from the query string. Absent and empty are both 'do not
  constrain' — `?domain=` from a cleared form field must not select the actors
  whose domain is the empty string, of which there are none."
  [^js url]
  (let [params (.-searchParams url)
        present (fn [k] (let [v (.get params (name k))]
                          (when-not (str/blank? v) (str/trim v))))]
    (cond-> {}
      true (into (keep (fn [k] (when-some [v (present k)] [k (keyword v)]))
                       keyword-params))
      true (into (keep (fn [k] (when-some [v (present k)] [k v]))
                       string-params))
      ;; Only "true"/"false" constrain. Anything else — including "1" — is
      ;; refused rather than guessed: `callable=1` reading as false would be a
      ;; wrong answer that looks like a right one.
      (contains? #{"true" "false"} (.get params "callable"))
      (assoc :callable? (= "true" (.get params "callable"))))))

;; ── responses ────────────────────────────────────────────────────────

(defn- json
  ([body] (json 200 body))
  ([status body]
   (js/Response. (js/JSON.stringify (clj->js body))
                 #js {:status status
                      :headers #js {"content-type" "application/json; charset=utf-8"
                                    "cache-control" "no-store"}})))

(defn- problem [status type message]
  (json status {:error {:type type :message message}}))

;; ── routes ───────────────────────────────────────────────────────────

(def ^:private max-limit 200)

(defn- limit-of [^js url]
  (let [raw (.get (.-searchParams url) "limit")
        n   (when-not (str/blank? raw) (js/parseInt raw 10))]
    (if (and n (not (js/isNaN n)) (pos? n)) (min n max-limit) 50)))

(defn- fleet-search [env ^js url]
  (-> (load-catalog! env)
      (.then (fn [c]
               (let [found (fleet/search (:actors c) (criteria url))
                     n     (limit-of url)]
                 ;; `matched` is the honest count and `actors` is the page.
                 ;; Returning only the page would let a caller read 50 as "the
                 ;; fleet has 50 of these".
                 (json {:matched (count found)
                        :limit n
                        :actors (vec (take n found))}))))))

(defn- fleet-facets [env ^js url]
  (let [field (.get (.-searchParams url) "field")]
    (if (str/blank? field)
      ;; js/Promise.resolve, not a bare Response. Every route here returns a
      ;; promise because `app` attaches .catch to whatever comes back — and a
      ;; Response has no .catch, so returning one raw turned this deliberate
      ;; 400 into a TypeError and a Cloudflare 1042. Found by calling the
      ;; deployed Worker; the release build compiled clean and the dry-run
      ;; was happy, because neither of them calls the route.
      (js/Promise.resolve
       (problem 400 "facets/field-required"
                "field= is required, e.g. ?field=domain"))
      (-> (load-catalog! env)
          (.then (fn [c]
                   (json {:field field
                          :values (mapv (fn [[v n]] {:value v :count n})
                                        (fleet/facets (:actors c)
                                                      (keyword field)))})))))))

(defn- health []
  ;; Deliberately does not load the catalog. See the note above the cache.
  (json {:ok true
         :service "cloud-itonami-app-edge"
         :slice "fleet-directory-read-only"
         :adr "ADR-2608081500"}))

(defn- handle [^js request ^js env]
  (let [url    (js/URL. (.-url request))
        path   (.-pathname url)
        method (.-method request)]
    (cond
      (not (contains? #{"GET" "HEAD"} method))
      (js/Promise.resolve
       (problem 405 "method-not-allowed" "This surface is read-only."))

      (= path "/health")               (js/Promise.resolve (health))
      (= path "/api/fleet/search")     (fleet-search env url)
      (= path "/api/fleet/facets")     (fleet-facets env url)

      :else
      (js/Promise.resolve
       (problem 404 "not-found" (str "No route for " path))))))

(def app
  #js {:fetch
       (fn [request env _ctx]
         (-> (handle request env)
             (.catch (fn [e]
                       ;; The type travels; the message does not. An asset
                       ;; path or a parse offset is operator information.
                       (let [t (or (some-> (ex-data e) :type name) "internal")]
                         (js/console.error "app-edge" t (str e))
                         (problem 500 t "The edge surface could not answer."))))))})
