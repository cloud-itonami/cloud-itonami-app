(ns cloud.itonami.app.latest-test
  "IPNS latest pointer. Offline: injected kad http-fn, disposable seed."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bundle :as bundle]
            [cloud.itonami.app.latest :as latest]
            [ipns.record :as rec]
            [kotoba.protocol.app :as app]
            [protobuf.wire :as pb]))

(def disposable-seed
  "Test vector only. Never the live kagi seed."
  (byte-array (range 32)))

(defn- temp-lock
  [m]
  (let [f (java.io.File/createTempFile "kotoba.app." ".edn")]
    (spit f (str (pr-str m) "\n"))
    (.getPath f)))

(defn- fake-dht
  []
  (let [store (atom {})]
    (fn [{:keys [method url body]}]
      (case method
        :put (do (swap! store assoc url (vec body))
                 {:status 200 :body []})
        :get (if-let [record (get @store url)]
               {:status 200 :body record}
               {:status 404 :body []})))))

(deftest parse-seed-refuses-short-hex
  (is (thrown? clojure.lang.ExceptionInfo (latest/parse-seed "abcd")))
  (is (bytes? (latest/parse-seed (apply str (repeat 64 "a"))))))

(deftest delegated-router-reads-bypass-a-stale-http-cache
  (is (= "https://router.example/ipns/k51?fresh=5"
         (latest/cache-busted-url "https://router.example/ipns/k51" 5)))
  (is (= "https://router.example/ipns/k51?format=raw&fresh=6"
         (latest/cache-busted-url
          "https://router.example/ipns/k51?format=raw" 6))))

(deftest the-name-is-a-k51-from-the-seed
  (let [name (latest/name-from-seed disposable-seed)]
    (is (re-matches #"k51[a-z0-9]{50,}" name))
    (is (= name (latest/name-from-seed disposable-seed))
        "the same seed must always name the same channel")))

(deftest a-signed-record-points-at-the-bundle-cid
  (let [{:keys [cid]} (bundle/snapshot)
        signed (latest/signed-record {:seed disposable-seed
                                      :cid cid
                                      :sequence 1})
        parsed (rec/parse (:octets signed))]
    (is (= (str "/ipfs/" cid) (:value signed)))
    (is (= 1 (:sequence signed)))
    (is (= (:name signed) (latest/name-from-seed disposable-seed)))
    (is (= (str "/ipfs/" cid) (pb/utf8-string (:value parsed))))
    (is (some? (latest/validate-octets (:name signed) (:octets signed))))))

(deftest write-latest-keeps-the-snapshot-embed-url
  (let [{:keys [cid manifest]} (bundle/snapshot)
        path (temp-lock (assoc manifest :published {:archive "x"}))
        name (latest/name-from-seed disposable-seed)
        record (latest/write-latest! path {:name name
                                           :cid cid
                                           :sequence 1
                                           :value (str "/ipfs/" cid)})
        protocol (into {} (filter (fn [[k _]]
                                    (and (keyword? k)
                                         (some-> (namespace k)
                                                 (str/starts-with? "kotoba."))))
                                  record))]
    (is (= [] (app/validate-manifest protocol)) (pr-str (app/validate-manifest protocol)))
    (is (= name (:kotoba.app/latest record)))
    (is (= (str "ipfs://" cid) (:kotoba.app/embed-url record))
        "latest is the channel; embed-url stays the snapshot CID")
    (is (= {:archive "x"} (:published record)))
    (is (= 1 (get-in record [:latest :sequence])))
    (is (= cid (get-in (edn/read-string (slurp path)) [:latest :cid])))))

(deftest publish-round-trips-through-an-injected-router
  (let [{:keys [cid manifest]} (bundle/snapshot)
        path (temp-lock (merge manifest {:published {:get-status 200}}))
        result (latest/publish! {:seed disposable-seed
                                 :lock-path path
                                 :http-fn (fake-dht)})
        lock (edn/read-string (slurp path))]
    (is (= cid (:cid result)))
    (is (= 1 (:sequence result)))
    (is (= (str "ipns://" (:name result)) (:embed result)))
    (is (= (:name result) (:kotoba.app/latest lock)))
    (is (= (str "/ipfs/" cid) (get-in lock [:latest :value])))
    (is (= (str "ipfs://" cid) (:kotoba.app/embed-url lock)))))

(deftest publish-points-at-the-lock-cid
  (let [{:keys [manifest]} (bundle/snapshot)
        archived "bafkreiey52hai5obtqeg5w2ix63orset4o74kxljif2gwdkxt5upre2wsi"
        path (temp-lock (assoc manifest
                               :kotoba.app/bundle-cid archived
                               :kotoba.app/embed-url (str "ipfs://" archived)))
        result (latest/publish! {:seed disposable-seed
                                 :lock-path path
                                 :http-fn (fake-dht)})]
    (is (= archived (:cid result))
        "latest names the GET-verified lock CID, not a working-tree hash")
    (is (= (str "/ipfs/" archived) (get-in result [:published :latest :value])))))

(deftest publish-refuses-a-lock-without-a-cid
  (let [path (temp-lock {:kotoba.app/id "cloud.itonami.app"
                         :kotoba.app/version "0.1.0"
                         :kotoba.app/kind "appview"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"lock has no bundle CID"
                          (latest/publish! {:seed disposable-seed
                                            :lock-path path
                                            :http-fn (fake-dht)})))))

(deftest publish-refuses-when-no-router-accepts
  (let [{:keys [manifest]} (bundle/snapshot)
        path (temp-lock manifest)
        mute (fn [_] {:status 500 :body []})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"IPNS publish refused"
                          (latest/publish! {:seed disposable-seed
                                            :lock-path path
                                            :http-fn mute})))))

(deftest next-sequence-starts-at-one
  (is (= 1 (latest/next-sequence {})))
  (is (= 4 (latest/next-sequence {:latest {:sequence 3}}))))

(deftest this-slice-does-not-move-the-auth-host
  (is (re-find #"auth\.itonami\.cloud" (bundle/document-html)))
  (let [src (slurp "src/cloud/itonami/app/latest.clj")]
    (is (not (re-find #"auth\.itonami\.cloud" src)))
    (is (re-find #"Does not talk to kotobase.net `/ipns/`" src))))
