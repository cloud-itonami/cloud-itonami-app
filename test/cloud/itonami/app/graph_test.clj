(ns cloud.itonami.app.graph-test
  "L2 graph CID. Offline: in-memory chain store, no PUT."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [chain.core :as chain]
            [cloud.itonami.app.bundle :as bundle]
            [cloud.itonami.app.graph :as graph]
            [kotoba.protocol.app :as app]
            [kotoba.protocol.cid :as cid]))

(defn- temp-lock [m]
  (let [f (java.io.File/createTempFile "kotoba.app." ".edn")]
    (spit f (str (pr-str m) "\n"))
    (.getPath f)))

(deftest workspace-root-is-raw-cid-of-canonical-bytes
  (let [cid (bundle/raw-cid graph/workspace-body)]
    (is (re-matches #"bafkrei[a-z2-7]+" cid))
    (is (= cid (bundle/raw-cid graph/workspace-body)))))

(deftest seal-uses-chain-commit
  (let [{:keys [cid raw-cid bytes bundle-cid entity store]} (graph/snapshot)]
    (is (string? cid))
    (is (re-matches #"b[a-z2-7]{20,}" cid))
    (is (re-matches #"bafkrei[a-z2-7]+" raw-cid))
    (is (not= cid raw-cid)
        "chain identity is dag-cbor; archive Location is raw")
    (is (cid/digest-matches? raw-cid
                             (mapv #(bit-and % 0xff)
                                   (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                            bytes))))
    (is (= (:kotoba.app/bundle-cid (bundle/published-manifest)) bundle-cid))
    (is (= "mobile" (:kotoba.graph/name entity)))
    (is (= cid (:kotoba.graph/cid entity)))
    (is (false? (:log-dirty? store)))
    (is (chain/verify-chain (fn [c]
                              (when (= c cid) bytes))
                            cid))))

(deftest snapshot-is-stable
  (let [a (graph/snapshot)
        b (graph/snapshot)]
    (is (= (:cid a) (:cid b)))
    (is (= (:raw-cid a) (:raw-cid b)))
    (is (java.util.Arrays/equals ^bytes (:bytes a) ^bytes (:bytes b)))))

(deftest overlay-does-not-publish-session-datoms
  (let [src (slurp "src/cloud/itonami/app/graph.clj")]
    (is (not (re-find #":datoms" src)))
    (is (re-find #"Session kgraph datoms stay local" src))))

(deftest this-slice-does-not-move-auth-or-ipns
  (let [src (slurp "src/cloud/itonami/app/graph.clj")]
    (is (not (re-find #"auth\.itonami\.cloud" src)))
    (is (not (re-find #"routing/publish" src)))
    (is (re-find #"Does not republish IPNS" src))))

(deftest write-graph-keeps-app-identity
  (let [{:keys [cid manifest]} (bundle/snapshot)
        path (temp-lock manifest)
        {:keys [cid raw-cid bundle-cid bytes]} (graph/snapshot)
        record (graph/write-graph! path {:cid cid
                                         :raw-cid raw-cid
                                         :bundle-cid bundle-cid
                                         :size (alength ^bytes bytes)})]
    (is (= [] (app/validate-manifest
               (into {} (filter (fn [[k _]]
                                  (and (keyword? k)
                                       (some-> (namespace k)
                                               (str/starts-with? "kotoba."))))
                                record))))
        (pr-str record))
    (is (= cid (:kotoba.graph/cid record)))
    (is (= "mobile" (:kotoba.graph/name record)))
    (is (= (:kotoba.app/id manifest) (:kotoba.app/id record)))))

(deftest the-published-lock-matches-the-current-graph
  (let [published (bundle/published-manifest)
        {:keys [cid raw-cid]} (graph/snapshot)]
    (is (= cid (:kotoba.graph/cid published))
        "source or overlay changed; reseal before landing")
    (is (= raw-cid (get-in published [:graph :raw-cid])))
    (is (= "mobile" (:kotoba.graph/name published)))
    (is (nil? (:kotoba.graph/head published)))))

(deftest publish-points-at-the-chain-cid
  (let [{:keys [manifest]} (bundle/snapshot)
        path (temp-lock (assoc manifest
                               :kotoba.app/bundle-cid
                               (:kotoba.app/bundle-cid (bundle/published-manifest))))
        store (atom {})
        put-fn (fn [{:keys [cid bytes]}]
                 (swap! store assoc cid bytes)
                 {:status 201 :body "" :url (str "https://kotobase.net/ipfs/" cid)})
        get-fn (fn [cid]
                 (if-let [b (get @store cid)]
                   {:status 200 :bytes b :url (str "https://kotobase.net/ipfs/" cid)}
                   {:status 404 :bytes (byte-array 0)}))
        result (graph/publish! {:token "test"
                                :lock-path path
                                :put-fn put-fn
                                :get-fn get-fn})
        lock (edn/read-string (slurp path))]
    (is (= (:cid result) (:kotoba.graph/cid lock)))
    (is (= (:raw-cid result) (get-in lock [:graph :raw-cid])))
    (is (= 201 (get-in lock [:graph :put-status])))
    (is (= 200 (get-in lock [:graph :get-status])))))
