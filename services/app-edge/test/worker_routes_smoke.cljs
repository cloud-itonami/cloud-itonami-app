(ns worker-routes-smoke
  "Every route of the deployed Worker, including the ones that are supposed to
  refuse.

  This exists because the build did not catch a real bug and could not have.
  `/api/fleet/facets` with no field returned a bare Response where every other
  route returned a promise; `app` attaches .catch to what a route hands back,
  a Response has no .catch, and the deliberate 400 became a TypeError and a
  Cloudflare 1042. The release build compiled clean with zero warnings and
  `wrangler deploy --dry-run` was happy — neither of them calls a route.

  So the refusals are checked as carefully as the successes. A surface whose
  error paths are unexercised is a surface whose error paths do not work.

  Usage: nbb test/worker_routes_smoke.cljs <base-url>")

(def ^:private base
  (or (first *command-line-args*)
      "http://127.0.0.1:8787"))

(defonce ^:private failures (atom 0))

(defn- fail! [label detail]
  (swap! failures inc)
  (println "  FAIL" label "—" detail))

(defn- check-route
  "GET path, assert the status, then hand the parsed body to `body-check`
  (which returns nil when happy or a string describing what was wrong).

  `header-check` is handed the response headers and answers the same way. It
  runs before the body is looked at, because a header can be the whole answer:
  the mobile app reads nothing at all from a 200 that arrives without
  `access-control-allow-origin`, and the body it did not get to read is not
  where that failure shows up."
  [{:keys [label path method expect-status body-check header-check]}]
  (-> (js/fetch (str base path) #js {:method (or method "GET")})
      (.then (fn [r]
               (-> (.text r)
                   (.then (fn [text]
                            (cond
                              (not= expect-status (.-status r))
                              (fail! label (str "expected HTTP " expect-status
                                                ", got " (.-status r)
                                                " — " (subs text 0 (min 120 (count text)))))

                              (and header-check (header-check (.-headers r)))
                              (fail! label (header-check (.-headers r)))

                              (nil? body-check)
                              (println "  ok  " label)

                              :else
                              (let [parsed (try (js->clj (js/JSON.parse text))
                                                (catch :default _ ::unparseable))]
                                (if (= ::unparseable parsed)
                                  (fail! label (str "body is not JSON: "
                                                    (subs text 0 (min 120 (count text)))))
                                  (if-some [why (body-check parsed)]
                                    (fail! label why)
                                    (println "  ok  " label))))))))))))

(println "worker routes smoke —" base)

(-> (js/Promise.resolve)
    (.then #(check-route
             {:label "/health answers without touching the catalog"
              :path "/health" :expect-status 200
              :body-check (fn [b] (when-not (true? (get b "ok"))
                                    (str "ok was " (pr-str (get b "ok")))))}))
    (.then #(check-route
             {:label "callable=true matches the catalog's own count (77)"
              :path "/api/fleet/search?callable=true&limit=1" :expect-status 200
              :body-check (fn [b] (when-not (= 77 (get b "matched"))
                                    (str "matched was " (get b "matched"))))}))
    (.then #(check-route
             {:label "an execution filter constrains, and every hit honours it"
              :path "/api/fleet/search?execution=resident&limit=5" :expect-status 200
              :body-check (fn [b]
                            (cond
                              (not (pos? (get b "matched"))) "matched 0"
                              (>= (get b "matched") 1215) "execution filter matched everything"
                              (not (every? (fn [a] (= "resident" (get a "execution")))
                                           (get b "actors"))) "a hit was not resident"))}))
    (.then #(check-route
             {:label "a blank parameter does not constrain"
              :path "/api/fleet/search?domain=&limit=1" :expect-status 200
              :body-check (fn [b] (when-not (= 1215 (get b "matched"))
                                    (str "matched was " (get b "matched"))))}))
    (.then #(check-route
             {:label "limit pages the result but `matched` stays honest"
              :path "/api/fleet/search?limit=3" :expect-status 200
              :body-check (fn [b]
                            (cond
                              (not= 3 (count (get b "actors"))) "did not return 3 actors"
                              (not= 1215 (get b "matched")) "matched was paged too"))}))
    (.then #(check-route
             {:label "facets are ordered most-common-first"
              :path "/api/fleet/facets?field=execution" :expect-status 200
              :body-check (fn [b]
                            (let [counts (map (fn [v] (get v "count")) (get b "values"))]
                              (when-not (= counts (sort > counts))
                                "facet counts were not descending")))}))
    ;; ── the refusals ──────────────────────────────────────────────────
    (.then #(check-route
             {:label "facets without a field refuses with 400, not 500"
              :path "/api/fleet/facets" :expect-status 400
              :body-check (fn [b] (when-not (= "facets/field-required"
                                               (get-in b ["error" "type"]))
                                    (str "error type was "
                                         (pr-str (get-in b ["error" "type"])))))}))
    (.then #(check-route
             {:label "the stylesheet the page links to is actually served"
              :path "/dds.css" :expect-status 200}))
    (.then #(check-route
             {:label "the directory page renders as HTML"
              :path "/" :expect-status 200}))
    (.then #(check-route
             {:label "an unknown path is 404"
              :path "/no-such-route" :expect-status 404}))
    (.then #(check-route
             {:label "the surface is read-only: POST is 405"
              :path "/api/fleet/search" :method "POST" :expect-status 405}))
    ;; The mobile app is a cross-origin caller by construction — a web bundle
    ;; at kotoba-webbundle://app or https://appassets.androidplatform.net — so
    ;; without this header a 200 is a blank screen (ADR-2608311000).
    (.then #(check-route
             {:label "the JSON surface is readable cross-origin"
              :path "/api/fleet/search?limit=1" :expect-status 200
              :header-check (fn [^js h]
                              (let [v (.get h "access-control-allow-origin")]
                                (when-not (= "*" v)
                                  (str "access-control-allow-origin was " (pr-str v)))))}))
    ;; And scoped to it. The header belongs to the read API, not to the page;
    ;; asserting only the presence would pass a Worker that put it everywhere.
    (.then #(check-route
             {:label "the HTML page does not carry the API's CORS header"
              :path "/" :expect-status 200
              :header-check (fn [^js h]
                              (when-some [v (.get h "access-control-allow-origin")]
                                (str "the page carried access-control-allow-origin " (pr-str v))))}))
    (.then (fn [_]
             (if (pos? @failures)
               (do (println "\nFAILED:" @failures) (js/process.exit 1))
               (println "\nall routes behaved")))))
