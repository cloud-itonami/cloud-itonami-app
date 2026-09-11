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
  (:require [kotoba.lang.text :as str]
            [cloud.itonami.app.http-client :as http]
            [drive.object :as object]
            [sigv4.crypto :as crypto]
            [storj.core :as core]
            [storj.protocols :as p]
            [storj.store :as store])
  (:import [java.time Instant ZoneOffset]
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

;; Binary fidelity is preserved by an explicit base64 round trip: bytes in,
;; the same bytes back out. The shim carries strings only.

;; The workspace transport rejects headers the HTTP client owns; `host` and
;; `content-length` are both signed by SigV4, which is fine: the transport
;; derives each from the URL and the body, and derives the same values the
;; signer used. Anything else here would be a real mismatch.
(def ^:private client-owned-headers
  #{"host" "content-length" "connection" "expect" "upgrade"})

(defn http
  "An `storj.protocols/IHttp` over the workspace transport.

  Bytes stay bytes. A string decoding of the response would rewrite every
  byte above 0x7f — the same trap `filecoin/get-bytes` in this app
  documents, and one that does not announce itself: the corruption
  happens during decoding, so a later re-encode cannot undo it."
  []
  (reify p/IHttp
    (-request [_ req]
      (let [body-bytes (:body req)
            binary? (and body-bytes (not (string? body-bytes)))
            body (cond
                   (nil? body-bytes) nil
                   (string? body-bytes) body-bytes
                   :else (.encodeToString (java.util.Base64/getEncoder)
                                          ^bytes body-bytes))
            resp (http/request
                  {:url (:url req)
                   :method (keyword (str/lower (name (:method req))))
                   :timeout-seconds 60
                   :headers (into {}
                                  (comp (filter (fn [[k _]]
                                                  (not (client-owned-headers
                                                        (str/lower (name k))))))
                                        (map (fn [[k v]] [(name k) (str v)])))
                                  (:headers req))
                   :body body})
            resp-body (:body resp)]
        {:status  (:status resp)
         :headers (:headers resp)
         :body    (if (and binary? resp-body)
                    (.decode (java.util.Base64/getDecoder) ^String resp-body)
                    resp-body)}))))

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
