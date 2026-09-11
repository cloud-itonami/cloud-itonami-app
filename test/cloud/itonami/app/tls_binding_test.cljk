(ns cloud.itonami.app.tls-binding-test
  "The certificate decisions that are not TLS, on both runtimes."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [cloud.itonami.app.tls-binding :as tls-binding]))

(deftest a-challenge-path-yields-its-token-and-nothing-else-does
  (is (= "abcdefghijklmnop"
         (tls-binding/challenge-token "/.well-known/acme-challenge/abcdefghijklmnop")))
  (is (nil? (tls-binding/challenge-token "/.well-known/acme-challenge/short")))
  (testing "a token is a token, not a path"
    (is (nil? (tls-binding/challenge-token
               "/.well-known/acme-challenge/../../etc/passwd")))
    (is (nil? (tls-binding/challenge-token
               "/.well-known/acme-challenge/aaaaaaaaaaaaaaaa/b"))))
  (is (nil? (tls-binding/challenge-token "/.well-known/did.json")))
  (is (nil? (tls-binding/challenge-token "/")))
  (is (nil? (tls-binding/challenge-token nil))))

(deftest an-expiry-this-process-cannot-read-counts-as-due
  (let [now 1000000
        window 500]
    (is (false? (tls-binding/renewal-due? "1970-01-01T00:16:41.000Z" now window))
        "beyond the window — nothing to do")
    (is (true? (tls-binding/renewal-due? "1970-01-01T00:16:40.400Z" now window))
        "inside the window")
    (testing "no readable expiry is one fact, and it is due"
      ;; This was two branches reaching the same answer by different routes, so
      ;; inverting either changed nothing a test could see. A break test found
      ;; that; collapsing them is what made it findable.
      (is (true? (tls-binding/renewal-due? nil now window)))
      (is (true? (tls-binding/renewal-due? "not a timestamp" now window))))))
