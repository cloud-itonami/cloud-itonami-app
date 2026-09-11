(ns cloud.itonami.app.oauth-resource-kotoba-parity-test
  "RFC 9728 discovery truth table, and that the core compiles everywhere.

  Since `oauth_resource.clj` delegates admission, there is no second
  implementation to be in parity with. What this file owns is the table
  itself, asserted over the host, and the four-target compiles that keep
  the core inside the native word-typed slice."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.oauth-resource :as oauth-resource]
            [kotoba.compiler.core :as compiler]))

(def ^:private core-path "src/cloud/itonami/app/oauth_resource_core.kotoba")

(def ^:private core-source (delay (slurp core-path)))

(def ^:private path "/.well-known/oauth-protected-resource/mcp")

(deftest the-host-admits-only-get-rfc-9728-discovery
  (is (true? (oauth-resource/oauth-resource-route? "GET" path)))
  (is (false? (oauth-resource/oauth-resource-route? "POST" path)))
  (is (false? (oauth-resource/oauth-resource-route? "GET" "/health"))
      "liveness is a different core")
  (is (false? (oauth-resource/oauth-resource-route? "GET" "/.well-known/did.json")))
  (is (false? (oauth-resource/oauth-resource-route? "GET"
                                                   "/.well-known/oauth-protected-resource")))
  (is (false? (oauth-resource/oauth-resource-route? "get" path))
      "the wire method is uppercase; a downcased spelling is a different request"))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (let [out (compiler/compile-source @core-source target {})]
        (is (some? (:kir out))
            (str "oauth-resource core no longer compiles for " (name target)
                 " — it has probably grown a map, a set literal or a closure."))))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source @core-source target {})))))))
