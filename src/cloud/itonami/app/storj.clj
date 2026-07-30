(ns cloud.itonami.app.storj
  "Storj as a `drive` object store.

  ## Not verified against a gateway

  **Nothing here has made a request to Storj.** No credential for one exists in
  this workspace, so what is proven is the composition — signing, the request
  shape, the byte contract, the 404-versus-error distinction — and not that a
  gateway accepts it. Stated here rather than discovered by whoever first sets
  `STORJ_ACCESS_KEY` and reads a green test suite as evidence of a working
  backend. `io-storj` signs against AWS's own SigV4 vectors, so the signature
  is not guesswork; the untested part is everything after it leaves this
  process.

  ## What this is next to

  `filecoin` in this app is the other `IObjectStore`, and the two are not
  alternatives in the way the shared interface suggests. Filecoin addresses by
  content — a reference *is* the PieceCID and the store verifies it — while a
  Storj bucket is addressed by key and will store whatever bytes are filed
  under whatever reference. A caller that treats the seam as interchangeable
  loses that guarantee silently on the way across.

  ## Bytes

  `drive` hands the write side a vector of unsigned ints and expects one back;
  `storj.store` already converts in both directions, so nothing here restates
  it (see `kotoba.bytes/->bytes`, which is where the workspace keeps that
  answer).

  ## The clock

  `storj.core/sign` reads no clock on purpose — that is what makes it testable
  against fixed AWS vectors — so `storj.store/store-fns` requires one to be
  handed in. This supplies the system clock, which is the right place for it:
  a signature is only reproducible if the instant is an argument, and only
  useful if something eventually passes the real time."
  (:require [clojure.string :as str]
            [drive.object :as object]
            [sigv4.crypto :as crypto]
            [storj.core :as core]
            [storj.protocols :as p]
            [storj.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def schema "cloud.itonami.app.storj.v1")

(defn- env [k] (some-> (System/getenv k) str/trim not-empty))

(def ^:private iso-basic
  (-> (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
      (.withZone ZoneOffset/UTC)))

(defn now-iso
  "The current instant in the basic ISO-8601 form the signer accepts."
  []
  (.format iso-basic (Instant/now)))

(defonce ^:private ^HttpClient http-client
  (-> (HttpClient/newBuilder)
      ;; A redirect invalidates the signature — the protocol docstring says so
      ;; and java.net.http follows them by default only if asked, but saying it
      ;; explicitly means a later reader does not have to know the default.
      (.followRedirects HttpClient$Redirect/NEVER)
      (.connectTimeout (Duration/ofSeconds 15))
      (.build)))

;; java.net.http refuses to set these from user code (they belong to the
;; client), and throws IllegalArgumentException rather than ignoring them.
;; `host` and `content-length` are both signed by SigV4, which is fine: the
;; client derives each from the URL and the body publisher, and derives the
;; same values the signer used. Anything else here would be a real mismatch.
(def ^:private client-owned-headers
  #{"host" "content-length" "connection" "expect" "upgrade"})

(defn- body-publisher [body]
  (cond
    (nil? body)     (HttpRequest$BodyPublishers/noBody)
    (bytes? body)   (HttpRequest$BodyPublishers/ofByteArray body)
    (string? body)  (HttpRequest$BodyPublishers/ofString body)
    :else           (HttpRequest$BodyPublishers/ofByteArray
                     (byte-array (map unchecked-byte body)))))

(defn build-request
  "The signed request as a `java.net.http.HttpRequest`.

  Separate from sending so that it can be tested, because the send cannot be:
  no credential for a gateway exists here, and the failure this most plausibly
  has is one that happens before any network — `.header` throws
  `IllegalArgumentException` on a name the client owns, which would make the
  very first real request die on a header SigV4 legitimately signed."
  ^HttpRequest [{:keys [method url headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (.timeout b (Duration/ofSeconds 60))
    (doseq [[k v] headers
            :when (not (client-owned-headers (str/lower-case (name k))))]
      (.header b (name k) (str v)))
    (.method b (str/upper-case (name method)) (body-publisher body))
    (.build b)))

(defn http
  "An `storj.protocols/IHttp` over `java.net.http`.

  Bytes stay bytes. `BodyHandlers/ofString` would decode the response as UTF-8
  and rewrite every byte above 0x7f — the same trap `filecoin/get-bytes` in
  this app documents, and one that does not announce itself: the corruption
  happens during decoding, so a later re-encode cannot undo it."
  []
  (reify p/IHttp
    (-request [_ req]
      (let [resp (.send http-client (build-request req)
                        (HttpResponse$BodyHandlers/ofByteArray))]
        {:status  (.statusCode resp)
         :headers (into {} (map (fn [[k v]] [k (first v)]))
                        (.map (.headers resp)))
         :body    (.body resp)}))))

(defn config
  "Gateway config from the environment, or nil when it is not set.

      STORJ_ACCESS_KEY  STORJ_SECRET_KEY  STORJ_BUCKET  [STORJ_ENDPOINT]

  Absent credentials are an ordinary state — this app runs without a Storj
  backend — so this returns nil rather than throwing. `store` is what refuses."
  []
  (when-let [bucket (env "STORJ_BUCKET")]
    (when-let [access-key (env "STORJ_ACCESS_KEY")]
      (when-let [secret-key (env "STORJ_SECRET_KEY")]
        (cond-> {:bucket bucket :access-key access-key :secret-key secret-key}
          (env "STORJ_ENDPOINT") (assoc :endpoint (env "STORJ_ENDPOINT")))))))

(defn configured?
  "Whether a Storj backend can be built at all. Says nothing about whether the
  credentials work — nothing here has ever asked a gateway."
  []
  (some? (config)))

(defn store
  "An `IObjectStore` over a Storj bucket.

      (store)                                   ; from the environment
      (store {:config {...} :prefix \"drive/\"})  ; explicit, for tests

  `:prefix` keeps one consumer's objects from colliding with anything else in
  the bucket. It is not a security boundary — a credential that can read the
  prefix can read the bucket — and `storj.store` does not treat it as one.

  Throws when there is no config: a store that quietly answered nil to every
  read would be indistinguishable from an empty bucket."
  ([] (store {}))
  ([{:keys [prefix http-impl now] cfg :config
     :or   {prefix "" now now-iso}}]
   (let [cfg (or cfg (config))]
     (when-not cfg
       (throw (ex-info "storj: no gateway config — set STORJ_BUCKET, STORJ_ACCESS_KEY, STORJ_SECRET_KEY"
                       {:type ::not-configured})))
     (-> (core/client cfg {:crypto (crypto/crypto) :http (or http-impl (http))})
         (store/store-fns {:now now :prefix prefix})
         (object/store-of)))))
