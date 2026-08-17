(ns cloud.itonami.app.archive
  "The kotobase.net content-addressed archive: one raw object in, one out.

  `PUT https://kotobase.net/ipfs/{cid}` with a bearer token; `GET` is
  unauthenticated and the server recomputes the digest, so a wrong CID is
  refused at the door rather than stored under a name that lies.

  ## Only raw CIDv1

  `kotobase.archive-put` accepts `01 55 12 20` and nothing else. That is a
  property of the *archive*, not of the objects: a dag-cbor commit or a
  dag-pb node is archived by putting its bytes under the raw CID of those
  same bytes, which is the identical sha2-256 digest with a different codec
  byte in front. Identity stays the object's own CID; the raw spelling is
  only where it is kept (ADR-2608148200), and `location-cid` is that
  one-line conversion rather than a lookup table.

  This namespace was extracted from `cloud.itonami.app.bundle`, which had
  the only copy while there was only one caller. There are now three — the
  app document, the L2 graph commit, and every block of every file in the
  Drive — and a second copy of a token-reading HTTP client is not something
  to discover later."
  (:require [clojure.string :as str]
            [kotoba.protocol.cid :as cid])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.time Duration]))

(def origin "https://kotobase.net")

(def max-object-bytes
  "`kotobase.archive-put/max-object-bytes`. Anything larger has to be more
  than one object — which for file bytes is what UnixFS chunking is for."
  (* 4 1024 1024))

(def ^:dynamic *environment* #(System/getenv %))

(defn token
  "The bearer token, or nil. Two slots because the live Worker rotates by
  addition: a token accepted today may be in either."
  []
  (or (some-> (*environment* "KOTOBASE_ARCHIVE_TOKEN") str/trim not-empty)
      (some-> (*environment* "KOTOBASE_ARCHIVE_TOKEN_2") str/trim not-empty)))

(defn configured? [] (some? (token)))

(defn- sha256 [^bytes body]
  (mapv #(bit-and % 0xff) (.digest (MessageDigest/getInstance "SHA-256") body)))

(defn raw-cid
  "CIDv1 raw sha2-256 of `body` (`bafkrei…`) — the layout archive-put verifies."
  [^bytes body]
  (cid/cid-bytes->string (into [0x01 0x55 0x12 0x20] (sha256 body))))

(defn location-cid
  "Where `identity-cid` is kept in this archive.

  A raw CID is its own location. Any other codec keeps the digest and
  changes only the codec byte, so nothing has to be recorded to find it
  again — which is the whole reason this is a function and not a table."
  [identity-cid]
  (let [{:keys [codec digest error]} (cid/parse-cid identity-cid)]
    (when error
      (throw (ex-info "archive: not a CIDv1/sha2-256" {:cid identity-cid :error error})))
    (if (= :raw codec)
      identity-cid
      (cid/cid-bytes->string (into [0x01 0x55 0x12 0x20] digest)))))

(defn- http-client []
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 30)) .build))

(defn put!
  "PUT one raw object. Returns `{:status :body :url}` and never the token."
  [{:keys [cid bytes content-type] :as opts
    :or {content-type "application/octet-stream"}}]
  (let [bearer (or (:token opts) (token))]
    (when (str/blank? (str bearer))
      (throw (ex-info "archive put token missing" {:env "KOTOBASE_ARCHIVE_TOKEN"})))
    (when (> (alength ^bytes bytes) max-object-bytes)
      (throw (ex-info "archive: object exceeds the 4 MiB cap"
                      {:cid cid :size (alength ^bytes bytes) :cap max-object-bytes})))
    (let [url (str origin "/ipfs/" cid)
          req (-> (HttpRequest/newBuilder (URI/create url))
                  (.timeout (Duration/ofSeconds 60))
                  (.header "Authorization" (str "Bearer " bearer))
                  (.header "Content-Type" (str content-type))
                  (.PUT (HttpRequest$BodyPublishers/ofByteArray bytes))
                  .build)
          resp (.send ^HttpClient (http-client) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp) :url url})))

(defn get-bytes
  "Unauthenticated GET. Returns `{:status :bytes :url}`."
  [cid]
  (let [url (str origin "/ipfs/" cid)
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 30))
                (.GET)
                .build)
        resp (.send ^HttpClient (http-client) req (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode resp) :bytes (.body resp) :url url}))
