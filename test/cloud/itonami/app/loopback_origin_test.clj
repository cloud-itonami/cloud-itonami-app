(ns cloud.itonami.app.loopback-origin-test
  "GET / on the loopback IP must become localhost, or hosted sign-in cannot
  complete. Measured 2026-08-14: Origin 127.0.0.1:1338 -> 403 invalid-origin,
  Origin localhost:1338 -> 200 and auth.itonami.cloud/authorize."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.loopback-origin :as loopback]))

(def public "http://localhost:1338")

(deftest the-document-moves-to-the-webauthn-name
  (testing "the IP is not an origin this app can sign in from"
    (is (= "http://localhost:1338/"
           (loopback/document-redirect
            {:method "GET" :host "127.0.0.1:1338" :path "/"
             :public-origin public})))
    (is (= "http://localhost:1348/"
           (loopback/document-redirect
            {:method "GET" :host "127.0.0.1:1348" :path "/"
             :public-origin public}))))
  (testing "localhost is already the agreed name"
    (is (nil? (loopback/document-redirect
               {:method "GET" :host "localhost:1338" :path "/"
                :public-origin public}))))
  (testing "API probes against the bind address stay put"
    (is (nil? (loopback/document-redirect
               {:method "GET" :host "127.0.0.1:1338" :path "/health"
                :public-origin public})))
    (is (nil? (loopback/document-redirect
               {:method "POST" :host "127.0.0.1:1338" :path "/"
                :public-origin public}))))
  (testing "a deployment whose public origin is not localhost is left alone"
    (is (nil? (loopback/document-redirect
               {:method "GET" :host "127.0.0.1:1338" :path "/"
                :public-origin "https://itonami.cloud"})))))
