(ns cloud.itonami.app.graph
  "L2 graph CID of this appview.

  Hasher is `chain.core/commit!` (ADR-2608145400). This namespace does not
  invent a hash. `kotoba.protocol.graph` holds one overlay edge: desktop
  workspace → the GET-verified bundle CID. Session kgraph datoms stay local
  and are not published.

  kotobase archive PUT accepts only raw CIDv1 (`bafkrei…`, codec 0x55).
  The chain commit CID is dag-cbor (`bafy…`). Identity is the chain CID
  (`:kotoba.graph/cid`). Location is a raw CID of the same commit bytes,
  GET-verified at `https://kotobase.net/ipfs/{raw-cid}`.

  Does not republish IPNS. Does not move the auth host."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [chain.core :as chain]
            [cloud.itonami.app.bundle :as bundle]
            [kotoba.protocol.app :as app]
            [kotoba.protocol.graph :as g])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util Arrays]))

(def workspace-name "mobile")
(def workspace-body
  (.getBytes (str "{:kotoba.graph/name \"" workspace-name "\"}\n")
             StandardCharsets/UTF_8))

(defn- ->bytes [x]
  (cond
    (nil? x) (byte-array 0)
    (bytes? x) x
    (string? x) (.getBytes ^String x StandardCharsets/UTF_8)
    (sequential? x) (byte-array (map unchecked-byte x))
    :else (throw (ex-info "not bytes" {:class (class x)}))))

(defn memory-blocks
  "In-memory IPLD block store for `chain.core/commit!`."
  []
  (let [blocks (atom {})]
    {:blocks blocks
     :put! (fn [cid bytes]
             (swap! blocks assoc cid (->bytes bytes))
             nil)
     :get-fn (fn [cid] (get @blocks cid))}))

(defn overlay
  "Dirty protocol.graph store: workspace node CreateLink → bundle CID."
  [bundle-cid]
  (when (str/blank? bundle-cid)
    (throw (ex-info "bundle CID missing" {})))
  (let [root (bundle/raw-cid workspace-body)
        st (-> (g/store)
               (g/put-node {:cid root :body :workspace :merkle-links #{}})
               (g/put-node {:cid bundle-cid :body :archived :merkle-links #{}})
               (g/create-link {:from root :to bundle-cid :tag "appview"}))]
    (when (:error st)
      (throw (ex-info "overlay refused" st)))
    {:root root :store st}))

(defn seal
  "commit-log through chain.core/commit!. Returns chain CID + raw Location CID."
  ([st] (seal st (memory-blocks)))
  ([st {:keys [put! get-fn]}]
   (let [sealed (g/commit-log st (fn [prev state]
                                   (chain/commit! put! get-fn state prev)))]
     (when (:error sealed)
       (throw (ex-info "commit-log failed" sealed)))
     (let [cid (:graph-cid sealed)
           commit-bytes (->bytes (get-fn cid))]
       (when (zero? (alength commit-bytes))
         (throw (ex-info "sealed commit has no bytes" {:cid cid})))
       {:store sealed
        :cid cid
        :bytes commit-bytes
        :raw-cid (bundle/raw-cid commit-bytes)
        :entity (g/snapshot sealed)}))))

(defn snapshot
  "Seal the overlay of the lock's bundle CID. No network."
  ([]
   (snapshot (bundle/published-manifest)))
  ([published]
   (let [bundle-cid (:kotoba.app/bundle-cid published)
         {:keys [store root]} (overlay bundle-cid)
         sealed (seal store)]
     (assoc sealed
            :bundle-cid bundle-cid
            :root root
            :entity {:kotoba.graph/name workspace-name
                     :kotoba.graph/cid (:cid sealed)}))))

(defn- protocol-attrs [m]
  (into {} (filter (fn [[k _]]
                     (and (keyword? k)
                          (some-> (namespace k) (str/starts-with? "kotoba."))))
                   m)))

(defn write-graph!
  [path {:keys [cid raw-cid bundle-cid size put get]}]
  (let [prev (edn/read-string (slurp path))
        record (assoc prev
                      :kotoba.graph/name workspace-name
                      :kotoba.graph/cid cid
                      :graph {:cid cid
                              :raw-cid raw-cid
                              :bundle-cid bundle-cid
                              :size size
                              :archive (str bundle/archive-origin "/ipfs/" raw-cid)
                              :at (str (Instant/now))
                              :put-status (when put (:status put))
                              :get-status (when get (:status get))})
        problems (app/validate-manifest (protocol-attrs record))]
    (when (seq problems)
      (throw (ex-info "manifest invalid after graph"
                      {:problems problems})))
    (spit path (str (pr-str record) "\n"))
    record))

(defn publish!
  "Seal → PUT raw Location of the commit bytes → GET-verify → write the lock.

  Does not republish IPNS. Does not move the auth host."
  ([]
   (publish! {:token (or (System/getenv "KOTOBASE_ARCHIVE_TOKEN")
                         (System/getenv "KOTOBASE_ARCHIVE_TOKEN_2"))
              :lock-path (or (some-> (io/resource bundle/published-resource)
                                     io/file
                                     .getPath)
                             "resources/cloud/itonami/app/kotoba.app.edn")}))
  ([{:keys [token lock-path put-fn get-fn]
    :or {put-fn bundle/put-archive!
         get-fn bundle/get-archive}}]
   (let [published (edn/read-string (slurp lock-path))
         snap (snapshot published)
         raw-cid (:raw-cid snap)
         bytes (:bytes snap)
         size (alength ^bytes bytes)]
     (when (> size bundle/max-object-bytes)
       (throw (ex-info "graph commit exceeds archive cap"
                       {:size size :cap bundle/max-object-bytes})))
     (let [put (put-fn {:cid raw-cid :bytes bytes :token token
                        :content-type "application/vnd.ipld.dag-cbor"})]
       (when-not (#{200 201} (:status put))
         (throw (ex-info "graph archive put refused"
                         {:status (:status put)
                          :body (:body put)
                          :cid raw-cid})))
       (let [got (get-fn raw-cid)]
         (when-not (= 200 (:status got))
           (throw (ex-info "graph archive get after put failed"
                           {:status (:status got) :cid raw-cid})))
         (when-not (Arrays/equals ^bytes bytes ^bytes (:bytes got))
           (throw (ex-info "archived graph bytes differ from commit"
                           {:cid raw-cid
                            :put-size size
                            :get-size (alength ^bytes (:bytes got))})))
         (let [record (write-graph! lock-path
                                    {:cid (:cid snap)
                                     :raw-cid raw-cid
                                     :bundle-cid (:bundle-cid snap)
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
      (println "graph" (:cid result))
      (println "raw" (:raw-cid result))
      (println "bundle" (:bundle-cid result))
      (println "size" (alength ^bytes (:bytes result)))
      (println "put" (get-in result [:put :status]))
      (println "get" (get-in result [:get :status])))
    (let [{:keys [cid raw-cid bundle-cid bytes entity]} (snapshot)]
      (println "graph" cid)
      (println "raw" raw-cid)
      (println "bundle" bundle-cid)
      (println "size" (alength ^bytes bytes))
      (println "entity" (pr-str entity)))))
