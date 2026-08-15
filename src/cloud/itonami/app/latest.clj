(ns cloud.itonami.app.latest
  "The update channel for the workspace document.

  `:kotoba.app/latest` is this app's key-derived IPNS name (`k51…`). Holding
  the Ed25519 seed *is* authority over that name (`ipns.core`). The name
  points at `/ipfs/{lock-cid}` — the last GET-verified archive object
  in `kotoba.app.edn`, not a working-tree hash, not a UnixFS directory,
  and not the auth host.

  HTTPS Location stays `GET https://kotobase.net/ipfs/{cid}`
  (ADR-2608140500, ADR-2608130000). kotobase.net retired `GET /ipns/`
  because that route reached the peer-to-peer network; a 410 there is not
  a failure of this pointer. This namespace publishes a real IPNS Record
  to delegated DHT routers so `ipns://{k51}` resolves on the network that
  actually names things.

  The seed is never committed. Read it from `CLOUD_ITONAMI_APP_IPNS_SEED`
  (64 hex chars). Live custody is kagi item `cloud-itonami-app-latest`
  (compartment `personal`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.bundle :as bundle]
            [ed25519.core :as ed]
            [ipns.core :as ipns]
            [ipns.record :as rec]
            [kad.routing :as routing]
            [kotoba.protocol.app :as app]
            [protobuf.wire :as pb])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant ZoneOffset]
           [java.time.temporal ChronoUnit]))

(def seed-env "CLOUD_ITONAMI_APP_IPNS_SEED")
(def kagi-item "cloud-itonami-app-latest")
(def validity-days 365)
(def resolve-attempts 8)
(def resolve-wait-ms 2000)
(def record-ttl-ns
  "Five minutes, in nanoseconds. The spec default is one hour; that TTL is
  also what delegated-ipfs.dev caches GET for, so a later sequence cannot
  be GET-verified until it expires."
  (* 5 60 1000000000))

(defn- ->octets [b]
  (cond
    (nil? b) nil
    (bytes? b) (mapv #(bit-and % 0xff) b)
    (sequential? b) (mapv #(bit-and % 0xff) b)
    :else (mapv #(bit-and % 0xff) (vec b))))

(defn- ->bytes [octets]
  (byte-array (map unchecked-byte octets)))

(defn parse-seed
  "64 hex chars → 32-byte seed. Refuse anything else."
  [s]
  (let [hex (str/replace (str s) #"\s" "")]
    (when-not (re-matches #"[0-9a-fA-F]{64}" hex)
      (throw (ex-info "IPNS seed must be 64 hex chars"
                      {:env seed-env :kagi kagi-item})))
    (ed/unhex hex)))

(defn load-seed
  []
  (parse-seed (or (System/getenv seed-env)
                  (throw (ex-info "IPNS seed missing"
                                  {:env seed-env :kagi kagi-item})))))

(defn name-from-seed
  [seed]
  (ipns/pubkey->name (->octets (ed/pubkey-from-seed seed))))

(defn- sign-fn [seed]
  (fn [octets]
    (->octets (ed/sign seed (->bytes octets)))))

(defn- verify-fn [pubkey-octets message-octets signature-octets]
  (ed/verify (->bytes pubkey-octets)
             (->bytes message-octets)
             (->bytes signature-octets)))

(defn- validity-at [^Instant instant]
  (let [utc (.atOffset instant ZoneOffset/UTC)]
    (rec/rfc3339-nanos {:year (.getYear utc)
                        :month (.getMonthValue utc)
                        :day (.getDayOfMonth utc)
                        :hour (.getHour utc)
                        :minute (.getMinute utc)
                        :second (.getSecond utc)
                        :nanos (.getNano utc)})))

(defn signed-record
  "IPNS Record whose Value is `/ipfs/{cid}`."
  [{:keys [seed cid sequence now]
    :or {now (Instant/now) sequence 1}}]
  (let [value (str "/ipfs/" cid)
        validity (validity-at (.plus now validity-days ChronoUnit/DAYS))
        record (rec/create {:value value
                            :validity validity
                            :sequence sequence
                            :ttl record-ttl-ns
                            :sign-fn (sign-fn seed)})
        name (name-from-seed seed)
        parsed-ok (rec/validate record name
                                {:verify-fn verify-fn
                                 :now-ms (.toEpochMilli now)})]
    (when-not (:valid? parsed-ok)
      (throw (ex-info "signed record does not validate"
                      {:reason (:reason parsed-ok)})))
    {:name name
     :cid cid
     :value value
     :sequence sequence
     :record record
     :octets (rec/serialize record)}))

(defn- http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 30))
      .build))

(defn cache-busted-url
  "A delegated router may CDN-cache GET beyond the IPNS record TTL. A publish
  must verify the record that was just accepted, not a still-valid cached
  predecessor, so each read gets a transport-only query nonce. Routers ignore
  unknown query parameters; the signed IPNS name and record are unchanged."
  [url nonce]
  (str url (if (str/includes? url "?") "&" "?") "fresh=" nonce))

(defn kad-http
  "Synchronous http-fn for `kad.routing`. Body is octets either way."
  [{:keys [method url headers body]}]
  (let [url (if (= method :get)
              (cache-busted-url url (System/nanoTime))
              url)
        bldr (reduce-kv (fn [b k v]
                          (.header b (name k) (str v)))
                        (-> (HttpRequest/newBuilder (URI/create url))
                            (.timeout (Duration/ofSeconds 45)))
                        (or headers {}))
        req (case method
              :get (.build (.GET bldr))
              :put (.build (.PUT bldr
                                 (HttpRequest$BodyPublishers/ofByteArray
                                  (if (bytes? body) body (->bytes body)))))
              (throw (ex-info "unsupported kad method" {:method method})))
        resp (.send ^HttpClient (http-client) req
                    (HttpResponse$BodyHandlers/ofByteArray))
        raw (.body resp)]
    {:status (.statusCode resp)
     :body (when (pos? (alength ^bytes raw)) (->octets raw))}))

(defn validate-octets
  "validate-fn for kad.routing/resolve. Returns the parsed record or nil."
  [name octets]
  (try
    (let [parsed (rec/parse octets)
          v (rec/validate parsed name
                          {:verify-fn verify-fn
                           :now-ms (System/currentTimeMillis)})]
      (when (:valid? v) parsed))
    (catch Exception _ nil)))

(defn next-sequence
  [published]
  (inc (long (or (get-in published [:latest :sequence]) 0))))

(defn- protocol-attrs [m]
  (into {} (filter (fn [[k _]]
                     (and (keyword? k)
                          (= "kotoba.app" (namespace k))))
                   m)))

(defn write-latest!
  [path {:keys [name cid sequence value]}]
  (let [prev (edn/read-string (slurp path))
        record (assoc prev
                      :kotoba.app/latest name
                      :latest {:cid cid
                               :value value
                               :sequence sequence
                               :at (str (Instant/now))})
        problems (app/validate-manifest (protocol-attrs record))]
    (when (seq problems)
      (throw (ex-info "manifest invalid after latest"
                      {:problems problems})))
    (spit path (str (pr-str record) "\n"))
    record))

(defn- resolve-value
  [http-fn name {:keys [cid sequence]}]
  (let [want (str "/ipfs/" cid)]
    (loop [attempt 0]
      (let [got (routing/resolve http-fn name
                                 {:validate-fn (fn [octets]
                                                 (validate-octets name octets))
                                  :select-fn rec/select})
            record (when (:ok? got) (:record got))
            value (when record (pb/utf8-string (:value record)))
            seqn (when record (:sequence record))
            fresh? (and (:ok? got)
                        (= want value)
                        (>= (or seqn 0) sequence))]
        (cond
          fresh? got
          (>= attempt (dec resolve-attempts)) got
          :else (do (Thread/sleep resolve-wait-ms)
                    (recur (inc attempt))))))))

(defn publish!
  "Sign → DHT PUT → DHT GET-verify → write `:kotoba.app/latest` into the lock.

  Does not talk to kotobase.net `/ipns/` (410 by ADR-2608130000). Does not
  move the auth host. Fail closed if no router accepts, or if the name
  does not resolve back to this CID."
  ([]
   (publish! {:seed (load-seed)
              :lock-path (or (some-> (io/resource bundle/published-resource)
                                     io/file
                                     .getPath)
                             "resources/cloud/itonami/app/kotoba.app.edn")}))
  ([{:keys [seed lock-path http-fn]
    :or {http-fn kad-http}}]
   (let [published (edn/read-string (slurp lock-path))
         cid (:kotoba.app/bundle-cid published)
         _ (when (str/blank? cid)
             (throw (ex-info "lock has no bundle CID"
                             {:lock-path lock-path})))
         seqn (next-sequence published)
         signed (signed-record {:seed seed :cid cid :sequence seqn})
         name (:name signed)
         put (routing/publish http-fn name (:octets signed) {})]
     (when-not (:ok? put)
       (throw (ex-info "IPNS publish refused"
                       {:rejected (:rejected put)})))
     (let [got (resolve-value http-fn name {:cid cid :sequence seqn})
           value (when (:ok? got)
                   (pb/utf8-string (:value (:record got))))]
       (when-not (:ok? got)
         (throw (ex-info "IPNS resolve after publish failed"
                         {:reason (:reason got)
                          :responses (:responses got)})))
       (when-not (= (str "/ipfs/" cid) value)
         (throw (ex-info "resolved IPNS value is not this CID"
                         {:want (str "/ipfs/" cid) :got value})))
       (let [record (write-latest! lock-path
                                   {:name name
                                    :cid cid
                                    :sequence seqn
                                    :value (str "/ipfs/" cid)})]
         {:name name
          :cid cid
          :sequence seqn
          :embed (str "ipns://" name)
          :accepted (:accepted put)
          :published record})))))

(defn -main
  [& _args]
  (let [result (publish!)]
    (println "latest" (:name result))
    (println "embed" (:embed result))
    (println "points" (str "/ipfs/" (:cid result)))
    (println "sequence" (:sequence result))
    (println "accepted" (pr-str (:accepted result)))))
