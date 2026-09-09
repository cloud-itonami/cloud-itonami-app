(ns cloud.itonami.app.http-client-async
  "The portable HTTP client: one calling shape on the JVM and on ClojureScript.

  `cloud.itonami.app.http-client` is the synchronous one, and it is `.clj`
  because it can only ever be. **Node has no in-process synchronous HTTP** --
  the only synchronous route is spawning a process, which is a strictly wider
  authority than the network read it would be standing in for. So a client that
  works on both runtimes has to be the asynchronous one, and that is this.

  ## Why a second namespace instead of changing the first

  36 namespaces call `http-client/request` and read the response on the next
  line. Flipping that file to promises would break all 36 in one commit, and a
  `.cljc` whose `request` returned a map on the JVM and a promise on
  ClojureScript would be one name meaning two contracts -- the thing
  `kotoba.lang.http.host.node` deliberately refused to do by not reifying
  `IHttp`.

  So the two coexist and call sites move one at a time. Each move is visible in
  `nbb scripts/jvm-exit-report.cljs`. When the last one has moved,
  `http_client.clj` is deleted and this becomes the only client. Until then,
  **the presence of `http_client.clj` is the honest measure of how far this
  has got** -- not the existence of this file.

  ## What it returns

      (request {:url … :method :get :headers {…} :body \"…\"})
      -> promise of {:status int :headers {lowercase-name value} :body string}

  The response map is `host/jvm`'s and `host/node`'s, which are the same map by
  construction: both fold their headers through `kotoba.lang.http/fold-headers`,
  and that rule is tested on both runtimes in kotoba-lang/http's own suite."
  (:require [promesa.core :as p]
            #?(:clj [kotoba.lang.http.host.jvm :as host]
               :cljs [kotoba.lang.http.host.node :as host])))

(def ^:private default-timeout-seconds
  "The same 120s `http-client` applies. Stated here rather than inherited so the
   two clients cannot drift apart while both exist."
  120)

(defn- build-transport
  "The JVM host has two transports and the Node host has one, because the Node
   host has nothing to be asynchronous in contrast to. The asymmetry is real,
   so it is written out rather than hidden behind an alias that would suggest
   host/node had a synchronous twin."
  [opts]
  #?(:clj (host/http-transport-async opts)
     :cljs (host/http-transport opts)))

(defonce ^:private transport
  ;; One client, not one per request: each construction opens its own
  ;; connection pool, and a pool per call is a pool that never gets reused.
  ;; Per-request `:timeout-seconds` still overrides, on both hosts.
  (delay (build-transport {:timeout-seconds default-timeout-seconds})))

(defn request
  "Perform an HTTP request. See the namespace docstring."
  ([req] (request req {}))
  ([req _opts]
   (p/promise (@transport req))))
