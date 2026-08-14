(ns cloud.itonami.app.bundle
  "The workspace document as a raw CIDv1 object.

  Identity is the bytes of one HTML document, addressed as `ipfs://{cid}`
  (CIDv1 / raw / sha2-256, `bafkrei…`). It is not a UnixFS directory, not a
  hostname, and not `#/signin` — a fragment is a view of the document, not
  the document (ADR-0045, ADR-2608145100).

  Live `GET /` on localhost still renders from process config. This namespace
  freezes an unauthenticated shell so the bytes have one identity. The
  hosted ceremony stays `https://auth.itonami.cloud`: serving this HTML from
  `{cid}.ipfs.itonami.cloud` would change the WebAuthn origin and break
  assertion. The CID is a snapshot of the client, not the RP.

  Put is `PUT https://kotobase.net/ipfs/{cid}` (Bearer
  `KOTOBASE_ARCHIVE_TOKEN`). The server recomputes sha256; mismatch is 422.
  Cap is 4 MiB (`kotobase.archive-put/max-object-bytes`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.web :as web]
            [kotoba.protocol.app :as app]
            [kotoba.protocol.cid :as cid])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration Instant]
           [java.util Arrays]))

(def publication-config
  "Unauthenticated frozen shell. Same shape the page tests already render:
  local, cloud off, no session. Live GET / still uses the process config."
  {:routing {:default-provider "local" :default-model "local"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers []
   :brand {:name "Cloud Itonami"}})

(def app-id "cloud.itonami.app")
(def app-version "0.1.0")
(def archive-origin "https://kotobase.net")
(def max-object-bytes (* 4 1024 1024))
(def published-resource "cloud/itonami/app/kotoba.app.edn")

(defn document-html
  "The one document whose bytes are the content identity."
  []
  (web/page-html publication-config))

(defn document-bytes
  []
  (.getBytes ^String (document-html) StandardCharsets/UTF_8))

(defn- sha256
  [^bytes body]
  (mapv #(bit-and % 0xff) (.digest (MessageDigest/getInstance "SHA-256") body)))

(defn raw-cid
  "CIDv1 raw sha2-256 of `body` (`bafkrei…`). Same layout archive-put verifies."
  [^bytes body]
  (cid/cid-bytes->string (into [0x01 0x55 0x12 0x20] (sha256 body))))

(defn snapshot
  []
  (let [body (document-bytes)
        cid (raw-cid body)
        manifest {:kotoba.app/id app-id
                  :kotoba.app/version app-version
                  :kotoba.app/kind "appview"
                  :kotoba.app/appview-of {:workspace "desktop"}
                  :kotoba.app/bundle-cid cid
                  :kotoba.app/embed-url (str "ipfs://" cid)}]
    {:bytes body
     :size (alength body)
     :cid cid
     :digest (sha256 body)
     :manifest manifest
     :problems (app/validate-manifest manifest)}))

(defn published-manifest
  "Last computed identity recorded in the repo, or nil.
  `:published` inside the map is filled only after a GET-verified put."
  []
  (when-let [res (io/resource published-resource)]
    (edn/read-string (slurp res))))

(defn- http-client
  []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 30))
      .build))

(defn put-archive!
  "PUT the raw object. Returns {:status :body :url}. Does not print the token."
  [{:keys [cid bytes token]
    :or {token (or (System/getenv "KOTOBASE_ARCHIVE_TOKEN")
                   (System/getenv "KOTOBASE_ARCHIVE_TOKEN_2"))}}]
  (when (str/blank? token)
    (throw (ex-info "archive put token missing"
                    {:env "KOTOBASE_ARCHIVE_TOKEN"})))
  (let [url (str archive-origin "/ipfs/" cid)
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 60))
                (.header "Authorization" (str "Bearer " token))
                (.header "Content-Type" "text/html")
                (.PUT (HttpRequest$BodyPublishers/ofByteArray bytes))
                .build)
        resp (.send ^HttpClient (http-client) req
                    (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (.body resp)
     :url url}))

(defn get-archive
  "Unauthenticated GET of the archived bytes."
  [cid]
  (let [url (str archive-origin "/ipfs/" cid)
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 30))
                (.GET)
                .build)
        resp (.send ^HttpClient (http-client) req
                    (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode resp)
     :bytes (.body resp)
     :url url}))

(defn write-published!
  "Record the last published identity. Extra keys are not :kotoba.app/* so
  validate-manifest still sees only the protocol attrs."
  [path {:keys [manifest cid size put get]}]
  (let [record (merge manifest
                      {:published {:archive (str archive-origin "/ipfs/" cid)
                                   :size size
                                   :at (str (Instant/now))
                                   :put-status (:status put)
                                   :get-status (:status get)}})]
    (spit path (str (pr-str record) "\n"))
    record))

(defn publish!
  "Compute → PUT → GET-verify → write the lock. Fail closed on mismatch."
  ([]
   (publish! {:token (or (System/getenv "KOTOBASE_ARCHIVE_TOKEN")
                         (System/getenv "KOTOBASE_ARCHIVE_TOKEN_2"))
              :lock-path (or (some-> (io/resource published-resource)
                                     io/file
                                     .getPath)
                             "resources/cloud/itonami/app/kotoba.app.edn")}))
  ([{:keys [token lock-path]}]
   (let [{:keys [bytes size cid manifest problems] :as snap} (snapshot)]
     (when (seq problems)
       (throw (ex-info "manifest invalid" {:problems problems})))
     (when (> size max-object-bytes)
       (throw (ex-info "document exceeds archive cap"
                       {:size size :cap max-object-bytes})))
     (when-not (cid/digest-matches? cid (:digest snap))
       (throw (ex-info "cid does not embed its digest" {:cid cid})))
     (let [put (put-archive! {:cid cid :bytes bytes :token token})]
       (when-not (#{200 201} (:status put))
         (println "put-refused" (:status put) (:body put) cid)
         (throw (ex-info "archive put refused"
                         {:status (:status put)
                          :body (:body put)
                          :cid cid})))
       (let [got (get-archive cid)]
         (when-not (= 200 (:status got))
           (throw (ex-info "archive get after put failed"
                           {:status (:status got) :cid cid})))
         (when-not (Arrays/equals ^bytes bytes ^bytes (:bytes got))
           (throw (ex-info "archived bytes differ from document"
                           {:cid cid
                            :put-size size
                            :get-size (alength ^bytes (:bytes got))})))
         (let [record (write-published! lock-path
                                        {:manifest manifest
                                         :cid cid
                                         :size size
                                         :put put
                                         :get got})]
           (assoc snap :put put :get {:status (:status got) :url (:url got)}
                  :published record)))))))

(defn -main
  [& args]
  (case (first args)
    "put"
    (let [result (publish!)]
      (println "cid" (:cid result))
      (println "size" (:size result))
      (println "embed" (get-in result [:manifest :kotoba.app/embed-url]))
      (println "put" (get-in result [:put :status]) (get-in result [:put :url]))
      (println "get" (get-in result [:get :status]) (get-in result [:get :url])))
    "latest"
    ((requiring-resolve 'cloud.itonami.app.latest/-main))
    (let [{:keys [cid size problems manifest]} (snapshot)]
      (println "cid" cid)
      (println "size" size)
      (println "embed" (:kotoba.app/embed-url manifest))
      (when (seq problems)
        (println "problems" (pr-str problems))
        (System/exit 1)))))
