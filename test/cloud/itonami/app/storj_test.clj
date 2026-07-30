(ns cloud.itonami.app.storj-test
  "Offline only. `IHttp` is a seam precisely so the whole signed request can be
  inspected without a gateway — which is also the limit of what these prove:
  that the request is well-formed and the byte contract holds, not that Storj
  accepts either. No credential for a real bucket exists in this workspace."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.storj :as storj]
            [drive.object :as object]
            [kotoba.bytes :as b]
            [storj.protocols :as p]))

(def ^:private cfg
  {:bucket "acme" :access-key "AKIAIOSFODNN7EXAMPLE"
   :secret-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"})

(defn- recording
  "An `IHttp` answering from `responses` and remembering every request."
  [responses]
  (let [seen (atom [])
        left (atom responses)]
    {:seen seen
     :http (reify p/IHttp
             (-request [_ req]
               (swap! seen conj req)
               (let [r (first @left)]
                 (swap! left rest)
                 r)))}))

(defn- store-with [responses & [opts]]
  (let [{:keys [seen http]} (recording responses)]
    {:seen seen
     :store (storj/store (merge {:config cfg :http-impl http
                                 :now (constantly "20260730T000000Z")}
                                opts))}))

;; ── configuration ───────────────────────────────────────────────────────────

(deftest an-absent-credential-is-an-ordinary-state
  ;; `config` reads the process environment, so asserting it is nil would make
  ;; this suite fail on a machine that happens to have STORJ_* set — a red
  ;; build caused by a developer's shell rather than by the code. The absence
  ;; is supplied instead of assumed.
  (with-redefs [storj/config (constantly nil)]
    (testing "no config is nil rather than a throw — this app runs without Storj"
      (is (false? (storj/configured?))))
    (testing "but building a store anyway is refused, not answered with nils"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no gateway config"
                            (storj/store)))))
  (testing "and a complete environment produces a config"
    (with-redefs [storj/config (constantly cfg)]
      (is (true? (storj/configured?))))))

;; ── the request that would go on the wire ───────────────────────────────────

(deftest a-get-is-signed-and-addressed
  (let [{:keys [seen store]} (store-with [{:status 200 :headers {} :body (byte-array [1 2 3])}])]
    (object/-get-object store "obj-1")
    (let [req (first @seen)]
      (is (= "GET" (:method req)))
      (is (str/includes? (:url req) "obj-1"))
      (is (str/includes? (:url req) "acme") "the bucket is in the endpoint or path")
      (is (contains? (:headers req) "authorization"))
      (is (str/starts-with? (get (:headers req) "authorization") "AWS4-HMAC-SHA256")
          "SigV4, not something that merely looks like a credential"))))

(deftest a-prefix-lands-in-the-key
  (let [{:keys [seen store]} (store-with [{:status 200 :headers {} :body (byte-array [1])}]
                                         {:prefix "drive/acme/"})]
    (object/-get-object store "obj-1")
    (is (str/includes? (:url (first @seen)) "drive/acme/obj-1"))))

;; ── the byte contract, across the seam ──────────────────────────────────────

(deftest what-comes-back-is-a-vector-of-unsigned-ints
  (let [{:keys [store]} (store-with [{:status 200 :headers {}
                                      :body (byte-array [7 8 -56])}])
        got (object/-get-object store "obj-1")]
    (is (vector? got) "not the transport's byte[]")
    (is (= [7 8 200] got) "and 0xC8 is not -56 by the time a consumer sees it")))

(deftest a-missing-object-is-nil-and-an-empty-one-is-empty
  (let [{:keys [store]} (store-with [{:status 404 :headers {} :body nil}])]
    (is (nil? (object/-get-object store "gone"))))
  (let [{:keys [store]} (store-with [{:status 200 :headers {} :body (byte-array 0)}])]
    (is (= [] (object/-get-object store "empty"))
        "a consumer deciding whether its own records are wrong needs these apart")))

(deftest a-write-sends-the-bytes-it-was-given
  (let [{:keys [seen store]} (store-with [{:status 200 :headers {} :body nil}])]
    (object/-put-object store "obj-1" [7 8 200])
    (let [req (first @seen)]
      (is (= "PUT" (:method req)))
      (is (= [7 8 200] (b/->bytes (:body req)))
          "the vector drive hands down arrives as those bytes, unsigned"))))

(deftest exists?-does-not-fetch-the-object
  (let [{:keys [seen store]} (store-with [{:status 200 :headers {} :body nil}])]
    (is (true? (object/-object-exists? store "obj-1")))
    (is (= "HEAD" (:method (first @seen)))
        "a HEAD, not a GET whose body is thrown away"))
  (let [{:keys [store]} (store-with [{:status 404 :headers {} :body nil}])]
    (is (false? (object/-object-exists? store "gone")))))

;; ── the part that would talk to a gateway ───────────────────────────────────

(deftest a-signed-request-can-actually-be-built
  ;; The send cannot be tested without a bucket; the build can, and it is where
  ;; the first real request would most plausibly die. SigV4 signs `host`, and
  ;; java.net.http throws IllegalArgumentException rather than ignoring a
  ;; header it owns — so passing the signed header map straight through would
  ;; fail on the first call, with a credential present and nothing else wrong.
  (let [req (storj/build-request
             {:method "PUT" :url "https://gateway.storjshare.io/acme/obj-1"
              :headers {"host" "gateway.storjshare.io"
                        "content-length" "3"
                        "authorization" "AWS4-HMAC-SHA256 Credential=..."
                        "x-amz-content-sha256" "abc"}
              :body (byte-array [1 2 3])})]
    (is (= "PUT" (.method req)))
    (testing "the headers SigV4 signed and the client does not own are sent"
      (is (= ["AWS4-HMAC-SHA256 Credential=..."]
             (.allValues (.headers req) "authorization")))
      (is (= ["abc"] (.allValues (.headers req) "x-amz-content-sha256"))))
    (testing "and the ones it owns are left to it, rather than throwing"
      (is (empty? (.allValues (.headers req) "host")))
      (is (empty? (.allValues (.headers req) "content-length"))))
    (testing "the client derives the same host the signature used"
      (is (= "gateway.storjshare.io" (.getHost (.uri req))))))
  (testing "a HEAD has no body publisher content"
    (is (zero? (.contentLength (.get (.bodyPublisher
                                      (storj/build-request
                                       {:method "HEAD" :url "https://gateway.storjshare.io/acme/k"
                                        :headers {} :body nil})))))))
  (testing "and a vector body becomes the bytes it names"
    (is (= 3 (.contentLength (.get (.bodyPublisher
                                    (storj/build-request
                                     {:method "PUT" :url "https://gateway.storjshare.io/acme/k"
                                      :headers {} :body [7 8 200]}))))))))

;; ── what this does not prove ────────────────────────────────────────────────

(deftest the-untested-boundary-is-named-not-hidden
  ;; A green suite here means the request is well-formed. It does not mean a
  ;; gateway has ever answered one, and the docstring says so rather than
  ;; leaving that to be inferred from a passing build.
  (is (str/includes? (:doc (meta (find-ns 'cloud.itonami.app.storj)))
                     "Nothing here has made a request to Storj")))
