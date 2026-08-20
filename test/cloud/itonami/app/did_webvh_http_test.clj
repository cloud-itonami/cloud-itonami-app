(ns cloud.itonami.app.did-webvh-http-test
  "The did:webvh log and witness proofs, fetched over HTTP and then RESOLVED.

  The other tests in this repository verify a log they were handed. This one
  fetches the bytes the server actually writes and runs the resolver over
  them, which is the only version of the claim a stranger can make. A
  serialisation that reorders a map, drops a key, or adds a BOM would leave
  every in-memory test green and this one red, because the entry hash is taken
  over the JSON.

  It also inverts the shipped decision core, the same way `did-web-http-test`
  does: a handler that kept its own two string equals and dropped the oracle
  call would stay green everywhere else."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.did-web :as did-web]
            [cloud.itonami.app.health :as health]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.kotoba-oracle-gen :as gen]
            [cloud.itonami.app.org-root-did :as root-did]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store]
            [didwebvh.entry :as entry]
            [didwebvh.signer :as signer]
            [didwebvh.time :as t]
            [kotoba.compiler.core :as compiler])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private log-path "/.well-known/did.jsonl")
(def ^:private witness-path "/.well-known/did-witness.json")
(def ^:private domain "acme.example")

(defn- seed [n] (byte-array (map unchecked-byte (repeat 32 n))))

(def ^:private version-time "2026-08-20T10:00:00Z")

(def ^:private minted
  (root-did/mint {:domain domain
                  :version-time version-time
                  :assertion-multikey (:multikey (signer/from-seed (seed 7)))
                  :also-known-as [(str "did:web:" domain)]
                  :update-signer (signer/from-seed (seed 1))
                  :next-multikey (:multikey (signer/from-seed (seed 2)))
                  :witness-signers (mapv #(signer/from-seed (seed %)) [11 12 13 14 15])}))

(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0
            :public-origin "http://localhost:1338"
            :webauthn-rp-id "localhost"}
   :privacy {:bind-loopback-only? true}
   :routing {:default-provider "ollama" :default-model "test"
             :cloud-enabled? false}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- request [method p]
  (let [builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:"
                                  (.getPort (.getAddress @server/server))
                                  p)))
        built (case method
                :get (.GET builder)
                :post (.POST builder (HttpRequest$BodyPublishers/ofString "{}")))
        response (.send client (.build built)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :content-type (.orElse (.firstValue (.headers response) "content-type") "")
     :body (.body response)}))

(defn- with-server
  "Serve with a REAL tenant in the store rather than a redefined lookup.

  `root-did-for-host` is part of what these routes have to get right — one
  Host must resolve to one tenant's log, and stubbing it would leave that
  untested while looking like coverage. Only `did-web-domain-for-host` is
  redefined, because Host->tenant resolution has its own tests (ADR-0025) and
  is not what this file is about."
  [tenant body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-did-webvh-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (when tenant
        (store/transact! (fn [c] (assoc-in c [:identity :organizations "org-1"] tenant))))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/configure! (fn [_] nil)
                    local-identity/did-web-domain-for-host (fn [_] (when tenant domain))]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(defn- published []
  {:id "org-1"
   :tenant/kind :organization
   :organization-id "acme"
   :domain domain
   :did (:did minted)
   :did-method :webvh
   :did-log (:log minted)
   :did-witness (:witness-file minted)
   :did-witness-threshold root-did/witness-threshold
   :did-custody :co-located})

(deftest what-the-server-publishes-resolves
  (with-server (published)
    (fn []
      (let [log-response (request :get log-path)
            witness-response (request :get witness-path)
            fetched-log (mapv json/read-str (str/split-lines (:body log-response)))
            fetched-witness (json/read-str (:body witness-response))
            result (root-did/verify {:log fetched-log
                                     :witness-file fetched-witness
                                     :did (:did minted)
                                     :now (+ (t/parse version-time) 60)})]
        (is (= 200 (:status log-response)))
        (is (= 200 (:status witness-response)))
        (is (str/includes? (:content-type log-response) "application/jsonl"))
        (is (:ok? result)
            (str "the BYTES this server wrote did not resolve: "
                 (pr-str (dissoc result :versions))))
        (is (= (:did minted) (:did result)))
        (testing "the witness file carries all five proofs and the threshold is met"
          (is (= 5 (count (get-in fetched-witness [0 "proof"]))))
          (is (= 5 (get-in result [:versions 0 :witness :weight])))
          (is (= 3 (get-in result [:versions 0 :witness :threshold]))))))))

(deftest a-host-with-no-log-is-a-404-not-an-empty-log
  (with-server nil
    (fn []
      (is (= 404 (:status (request :get log-path))))
      (is (= 404 (:status (request :get witness-path))))))
  (with-server (published)
    (fn []
      (is (= 200 (:status (request :get log-path)))
          "the same route answers once a tenant has one"))))

(deftest only-get-reaches-the-log
  (with-server (published)
    (fn []
      (is (not= 200 (:status (request :post log-path))))
      (is (not= 200 (:status (request :post witness-path)))))))

(def ^:private inverted-kir
  (:kir (compiler/compile-source
         (str "(ns cloud.itonami.app.did-web"
              "  (:export [did-web-route? did-log-route? did-witness-route?]))"
              "(defn did-web-route? [method :string path :string] :bool"
              "  (and (string=? method \"GET\")"
              "       (string=? path \"/.well-known/did.json\")))"
              "(defn did-log-route? [method :string path :string] :bool"
              "  (if (and (string=? method \"GET\")"
              "           (string=? path \"/.well-known/did.jsonl\"))"
              "    false true))"
              "(defn did-witness-route? [method :string path :string] :bool"
              "  (and (string=? method \"GET\")"
              "       (string=? path \"/.well-known/did-witness.json\")))")
         gen/target {})))

(deftest the-handler-follows-the-artifact
  (with-server (published)
    (fn []
      (is (= 200 (:status (request :get log-path))) "the shipped answer")
      (try
        (oracle/register-kir! :did-web inverted-kir)
        (is (false? (did-web/did-log-route? "GET" log-path))
            "the host followed the artifact")
        (is (not= 200 (:status (request :get log-path)))
            "the handler followed it too -- a copy of the two equals in
             server.clj would still 200")
        (is (= 200 (:status (request :get witness-path)))
            "only the log export was inverted; the witness route is its own decision")
        (is (= 200 (:status (request :get "/health")))
            "inverting DID discovery must not stop liveness")
        (finally (oracle/deregister-kir! :did-web)))
      (is (= 200 (:status (request :get log-path))) "restored")
      (is (true? (health/health-route? "GET" "/health"))))))

;; ── witness intake over HTTP ─────────────────────────────────────────────────

(deftest an-external-witness-files-a-proof-over-http
  ;; Start from a tenant whose witness file is EMPTY, so what this measures is
  ;; the intake and not the five proofs genesis already carried.
  (let [empty-witness (assoc (published) :did-witness [])]
    (with-server empty-witness
      (fn []
        (let [version-id (get-in minted [:log 0 "versionId"])
              witness (first (mapv #(signer/from-seed (seed %)) [11 12 13 14 15]))
              outsider (signer/from-seed (seed 99))
              post (fn [payload]
                     (let [request (-> (HttpRequest/newBuilder
                                        (URI/create (str "http://127.0.0.1:"
                                                         (.getPort (.getAddress @server/server))
                                                         witness-path)))
                                       (.POST (HttpRequest$BodyPublishers/ofString
                                               (json/write-str payload)))
                                       .build)
                           response (.send client request
                                           (HttpResponse$BodyHandlers/ofString))]
                       {:status (.statusCode response)
                        :body (json/read-str (.body response) :key-fn keyword)}))
              accepted (post {"versionId" version-id
                              "proof" (entry/witness-proof version-id witness)})]
          (is (= 200 (:status accepted)) (pr-str accepted))
          (is (true? (get-in accepted [:body :ok])))
          (testing "and it is in the file the resolver reads"
            (let [served (json/read-str (:body (request :get witness-path)))]
              (is (= 1 (count (get-in served [0 "proof"]))))))
          (testing "a stranger's proof is refused with a reason, not stored"
            (let [refused (post {"versionId" version-id
                                 "proof" (entry/witness-proof version-id outsider)})]
              (is (= 422 (:status refused)))
              (is (= "didwebvh/not-a-declared-witness" (get-in refused [:body :error])))
              (let [served (json/read-str (:body (request :get witness-path)))]
                (is (= 1 (count (get-in served [0 "proof"])))
                    "still one -- a refusal that stores anyway is not a refusal"))))
          (testing "a malformed body is a 400, not a 500"
            (is (= 400 (:status (post {"versionId" version-id}))))))))))

(deftest the-did-web-document-points-at-the-log
  (with-server (published)
    (fn []
      (let [document (json/read-str (:body (request :get "/.well-known/did.json")))]
        (is (= (str "did:web:" domain) (get document "id")))
        (is (= [(:did minted)] (get document "alsoKnownAs"))
            "a verifier arriving at the old name learns the identity keeps a log")))))
