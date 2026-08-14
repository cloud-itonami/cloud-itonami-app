(ns cloud.itonami.app.did-web-kotoba-parity-test
  "did:web discovery truth table, and that the core compiles everywhere.

  Since `did_web.cljc` delegates admission, there is no second implementation
  to be in parity with. What this file owns is the table itself, asserted
  over the host, and the four-target compiles that keep the core inside the
  native word-typed slice."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.did-web :as did-web]
            [kotoba.compiler.core :as compiler]))

(def ^:private core-path "src/cloud/itonami/app/did_web_core.kotoba")

(def ^:private core-source (delay (slurp core-path)))

(def ^:private path "/.well-known/did.json")

(deftest the-host-admits-only-get-did-web
  (is (true? (did-web/did-web-route? "GET" path)))
  (is (false? (did-web/did-web-route? "POST" path)))
  (is (false? (did-web/did-web-route? "GET" "/health"))
      "liveness is a different core")
  (is (false? (did-web/did-web-route? "GET"
                                     "/.well-known/oauth-protected-resource/mcp")))
  (is (false? (did-web/did-web-route? "GET" "/.well-known/did"))
      "the well-known suffix is part of the document's identity")
  (is (false? (did-web/did-web-route? "get" path))
      "the wire method is uppercase; a downcased spelling is a different request"))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (let [out (compiler/compile-source @core-source target {})]
        (is (some? (:kir out))
            (str "did-web core no longer compiles for " (name target)
                 " — it has probably grown a map, a set literal or a closure."))))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source @core-source target {})))))))
