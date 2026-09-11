(ns cloud.itonami.app.file-provider-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.file-provider :as provider]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store]
            [drive.store.memory :as memory])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)))

(def configuration
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin "http://localhost:1338"
            :webauthn-rp-id "localhost"}
   :privacy {:bind-loopback-only? true}
   :routing {:default-provider "ollama" :default-model "test"}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers []})

(def session {:id "finder" :kind :agent :user-id "finder-user"})
(def client (HttpClient/newHttpClient))

(defn- request [method path body]
  (let [port (.getPort (.getAddress @server/server))
        builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" port path)))
                    (.header "Authorization" "Bearer finder-token"))
        publisher (HttpRequest$BodyPublishers/ofByteArray
                   (or body (byte-array 0)))
        built (case method
                :get (.GET builder)
                :post (.POST builder publisher)
                :put (.PUT builder publisher)
                :patch (.method builder "PATCH" publisher)
                :delete (.DELETE builder))]
    (.send client (.build built) (HttpResponse$BodyHandlers/ofByteArray))))

(defn- json-bytes [value]
  (.getBytes (json/write-str value) StandardCharsets/UTF_8))

(defn- response-json [response]
  (json/read-str (String. ^bytes (.body response) StandardCharsets/UTF_8)
                 :key-fn keyword))

(deftest swift-wire-reaches-encrypted-drive-over-loopback
  (let [previous @store/state
        data-dir (.toFile (Files/createTempDirectory
                           "file-provider-http"
                           (make-array java.nio.file.attribute.FileAttribute 0)))
        object-store (memory/store)]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config/data-dir (constantly data-dir)
                    documents/store-instance (constantly object-store)
                    identity/session (fn [token]
                                       (when (= token "finder-token") session))
                    identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! configuration)
        (let [folder-response (request :post "/v1/file-provider/items"
                                       (json-bytes {:parentID provider/root-id
                                                    :name "Finder検証"
                                                    :directory true}))
              folder (response-json folder-response)
              file-response (request :post "/v1/file-provider/items"
                                     (json-bytes {:parentID (:id folder)
                                                  :name "proof.txt"
                                                  :directory false}))
              file (response-json file-response)
              uploaded (request :put (str "/v1/file-provider/items/" (:id file)
                                          "/content")
                                (.getBytes "Finder round trip" StandardCharsets/UTF_8))
              downloaded (request :get (str "/v1/file-provider/items/" (:id file)
                                            "/content") nil)
              mode (request :patch (str "/v1/file-provider/items/" (:id file) "/mode")
                            (json-bytes {:schedule "manual" :residency "pinned"}))]
          (is (= 200 (.statusCode folder-response)))
          (is (:directory folder))
          (is (= 200 (.statusCode file-response)))
          (is (= "proof.txt" (:name (response-json uploaded))))
          (is (= "Finder round trip"
                 (String. ^bytes (.body downloaded) StandardCharsets/UTF_8)))
          (is (.isPresent (.firstValue (.headers downloaded) "X-Kotoba-Item")))
          (is (= "pinned" (:residency (response-json mode))))))
      (finally
        (server/stop!)
        (reset! store/state previous)))))
