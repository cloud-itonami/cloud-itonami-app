(ns cloud.itonami.app.store-agreement-test
  "Finding a server on the port is not finding YOUR server.

  `server-process/ensure-running!` promised 'a server for this data directory'
  and checked only that something answered `/health`. On 2026-08-20 that was
  measured on this machine: a CLI in a repository checkout adopted the resident
  server, every read command worked — the session token lives in one Keychain
  item that is not per-store — and only `auth login` failed, with `invalid-key`
  and no mention of either directory. Three stores existed; one server; the
  probe could not tell them apart and answered as though it had.

  So the interesting case here is not 'no server'. It is a server that IS
  healthy and IS someone else's, and a server that cannot say which it is."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.server-process :as server-process])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(defn- stub
  "A server answering `/health` with `body`, and the configuration naming it.

  Port 0: this suite must not need 1338, which on a developer's machine is the
  resident install — the very process this namespace is about."
  [body]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/health"
     (reify HttpHandler
       (handle [_ exchange]
         (let [bytes (.getBytes ^String body "UTF-8")]
           (.sendResponseHeaders exchange 200 (alength bytes))
           (with-open [out (.getResponseBody exchange)]
             (.write out bytes))))))
    (.start server)
    {:server server
     :configuration {:server {:host "127.0.0.1"
                              :port (.getPort (.getAddress server))}}}))

(defn- with-stub [body f]
  (let [{:keys [server configuration]} (stub body)]
    (try (f configuration)
         (finally (.stop server 0)))))

(defn- health-body [store]
  (str "{\"ok\":true,\"service\":\"cloud-itonami-app\","
       "\"schema\":\"cloud.itonami.app.health.v1\""
       (if store (str ",\"store\":\"" store "\"}") "}")))

(deftest a-server-serving-this-store-is-adopted
  (with-stub (health-body (config/store-fingerprint))
    (fn [configuration]
      (let [agreement (server-process/store-agreement configuration)]
        (is (true? (:answering? agreement)))
        (is (true? (:known? agreement)))
        (is (true? (:ours? agreement))))
      (is (nil? (server-process/foreign-server-refusal configuration)))
      (is (false? (:started? (server-process/ensure-running! configuration)))
          "nothing is spawned when the server that is there is ours"))))

(deftest a-healthy-server-serving-another-store-is-refused
  (testing "the discriminating case: liveness says yes and the answer is still no"
    (with-stub (health-body "000000000000")
      (fn [configuration]
        (is (true? (server-process/healthy? configuration))
            "a probe that only asks 'is something there' says yes — which is
             exactly what made this adoptable before")
        (let [agreement (server-process/store-agreement configuration)]
          (is (true? (:known? agreement)))
          (is (false? (:ours? agreement)))
          (is (= "000000000000" (:served agreement)))
          (is (= (config/store-fingerprint) (:expected agreement))))
        (let [refusal (server-process/foreign-server-refusal configuration)]
          (is (some? refusal))
          (is (= :server-process/foreign-server (:type (ex-data refusal))))
          (is (re-find #"000000000000" (ex-message refusal))
              "the message carries what was measured, not just a verdict"))
        (is (thrown? clojure.lang.ExceptionInfo
                     (server-process/ensure-running! configuration))
            "ensure-running! refuses rather than adopting it")))))

(deftest a-server-that-cannot-say-is-not-treated-as-agreement-or-as-refusal
  (testing "a build older than the field answers /health without :store. That is
            'could not tell', and it must be neither of the two answers — folding
            it into 'yes' is the original bug, folding it into 'no' would strand
            every command against a server that is still running"
    (with-stub (health-body nil)
      (fn [configuration]
        (let [agreement (server-process/store-agreement configuration)]
          (is (true? (:answering? agreement)))
          (is (false? (:known? agreement)))
          (is (nil? (:served agreement))))
        (is (nil? (server-process/foreign-server-refusal configuration))
            "not refused")
        (is (false? (:started? (server-process/ensure-running! configuration)))
            "and not spawned around either")))))

(deftest nothing-listening-is-its-own-answer
  (let [{:keys [server configuration]} (stub (health-body nil))]
    (.stop server 0)
    (let [agreement (server-process/store-agreement configuration)]
      (is (false? (:answering? agreement)))
      (is (nil? (:known? agreement))
          "absent, not false — there was nobody to ask")
      (is (= (config/store-fingerprint) (:expected agreement))))
    (is (nil? (server-process/foreign-server-refusal configuration))
        "an empty port is for ensure-running! to fill, not to refuse")))
