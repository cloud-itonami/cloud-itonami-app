(ns cloud.itonami.app.bundle-test
  "The workspace document has a raw CID. Offline: no PUT, no GET."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bundle :as bundle]
            [kotoba.protocol.app :as app]
            [kotoba.protocol.cid :as cid]))

(deftest the-document-bytes-are-stable
  (let [a (bundle/document-bytes)
        b (bundle/document-bytes)]
    (is (pos? (alength a)))
    (is (java.util.Arrays/equals a b))))

(deftest the-cid-is-raw-sha256-of-those-bytes
  (let [{:keys [bytes cid digest size]} (bundle/snapshot)]
    (is (re-matches #"bafkrei[a-z2-7]+" cid))
    (is (cid/digest-matches? cid digest))
    (is (= cid (bundle/raw-cid bytes)))
    (is (= size (alength bytes)))
    (is (< size bundle/max-object-bytes)
        (str "archive-put cap is 4 MiB; document is " size " bytes"))))

(deftest the-manifest-is-one-ipfs-cid
  (let [{:keys [cid manifest problems]} (bundle/snapshot)]
    (is (= [] problems) (pr-str problems))
    (is (app/bundle-cid-consistent? manifest))
    (is (= "cloud.itonami.app" (:kotoba.app/id manifest)))
    (is (= "appview" (:kotoba.app/kind manifest)))
    (is (= cid (:kotoba.app/bundle-cid manifest)))
    (is (= (str "ipfs://" cid) (:kotoba.app/embed-url manifest)))
    (testing "fragments are not identity"
      (is (not (re-find #"#" (:kotoba.app/embed-url manifest)))))))

(deftest the-identity-is-of-the-real-document
  (let [html (bundle/document-html)]
    (is (re-find #"パスキーでサインイン" html))
    (is (re-find #"パスキーを作る" html))
    (is (re-find #"auth\.itonami\.cloud" html))
    (is (re-find #"data-view-panel=\"signin\"" html))))

(deftest the-published-lock-matches-the-current-document
  (let [published (bundle/published-manifest)
        {:keys [cid manifest]} (bundle/snapshot)]
    (is (map? published) "kotoba.app.edn is the last computed identity")
    (is (= cid (:kotoba.app/bundle-cid published))
        "source changed; republish before landing")
    (is (= (:kotoba.app/embed-url manifest)
           (:kotoba.app/embed-url published)))))

(deftest republishing-preserves-the-monotonic-latest-record
  (let [path (.getPath (java.io.File/createTempFile "kotoba-app-lock" ".edn"))
        old {:kotoba.app/latest "k51-existing"
             :latest {:cid "bafkreiold" :sequence 3}}
        _ (spit path (str (pr-str old) "\n"))
        {:keys [cid manifest size]} (bundle/snapshot)
        written (bundle/write-published!
                 path {:manifest manifest :cid cid :size size
                       :put {:status 201} :get {:status 200}})]
    (is (= "k51-existing" (:kotoba.app/latest written)))
    (is (= 3 (get-in written [:latest :sequence])))
    (is (= cid (:kotoba.app/bundle-cid written)))))
