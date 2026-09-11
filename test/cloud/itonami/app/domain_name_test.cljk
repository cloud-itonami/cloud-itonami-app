(ns cloud.itonami.app.domain-name-test
  "Runs on the JVM and on ClojureScript, which is the point of the namespace
  being `.cljc` at all. `bin/test-portable-cljs` is the second half; a
  `.cljc` file only one runtime ever executes is a `.clj` file with a longer
  extension."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [cloud.itonami.app.domain-name :as domain-name]))

(deftest a-name-is-refused-for-its-shape-before-anything-asks-dns
  (is (true? (domain-name/valid-ascii-name? "example.com")))
  (is (true? (domain-name/valid-ascii-name? "a.b.example.co.jp")))
  (is (true? (domain-name/valid-ascii-name? "xn--r8jz45g.jp")))
  (testing "a single label is a TLD, and a tenant does not get to claim one"
    (is (false? (domain-name/valid-ascii-name? "com"))))
  (testing "URL syntax, ports and wildcards are not names"
    (is (false? (domain-name/valid-ascii-name? "https://example.com/path")))
    (is (false? (domain-name/valid-ascii-name? "example.com:443")))
    (is (false? (domain-name/valid-ascii-name? "*.example.com"))))
  (testing "a label may not begin or end with a hyphen, or be empty"
    (is (false? (domain-name/valid-ascii-name? "-bad.example")))
    (is (false? (domain-name/valid-ascii-name? "bad-.example")))
    (is (false? (domain-name/valid-ascii-name? "a..example"))))
  (testing "and upper case is not ASCII-normalized here — the caller does that"
    (is (false? (domain-name/valid-ascii-name? "Example.COM"))))
  (is (false? (domain-name/valid-ascii-name? nil)))
  (is (false? (domain-name/valid-ascii-name?
               (str (apply str (repeat 250 "a")) ".example")))))

(deftest a-subdomain-of-a-name-this-deployment-owns-counts-as-owned
  (let [own ["cloud-itonami.app" "itonami.cloud"]]
    (is (true? (domain-name/service-owned? own "cloud-itonami.app")))
    (is (true? (domain-name/service-owned? own "team.cloud-itonami.app"))
        "proving this would be proving control of the operator's zone")
    (is (true? (domain-name/service-owned? own "ITONAMI.CLOUD"))
        "asked case-insensitively, because DNS is")
    (is (false? (domain-name/service-owned? own "example.co.jp")))
    (testing "a suffix match is not a label match"
      (is (false? (domain-name/service-owned? own "notitonami.cloud"))
          "`.` matters — otherwise every name ending in the same letters is ours"))
    (is (false? (domain-name/service-owned? [] "example.co.jp")))
    (is (false? (domain-name/service-owned? [nil ""] "example.co.jp")))))

(deftest a-measurement-nobody-can-date-is-not-fresh
  (let [now 1000000
        window 500]
    (is (true? (domain-name/fresh? "1970-01-01T00:16:39.800Z" now window)))
    (is (false? (domain-name/fresh? "1970-01-01T00:16:39.000Z" now window))
        "older than the window")
    (testing "and neither missing nor unreadable counts as recent"
      ;; The safe direction. Answering true here would let a binding stay live
      ;; on evidence nobody can place in time.
      (is (false? (domain-name/fresh? nil now window)))
      (is (false? (domain-name/fresh? "not a timestamp" now window)))
      (is (false? (domain-name/fresh? "" now window))))))

(deftest an-instant-that-will-not-parse-is-nil-and-not-a-throw
  (is (= 0 (domain-name/epoch-ms "1970-01-01T00:00:00Z")))
  (is (= 1000 (domain-name/epoch-ms "1970-01-01T00:00:01Z")))
  (is (nil? (domain-name/epoch-ms "not a timestamp")))
  (is (nil? (domain-name/epoch-ms nil)))
  (is (nil? (domain-name/epoch-ms 12345))))

(deftest a-name-one-record-holds-is-not-available-to-another
  (let [records [{:id "a" :domain "example.com" :status :claimed}
                 {:id "b" :domain "other.example" :status :live}
                 {:id "c" :domain "example.com" :status :pending}]]
    (is (false? (domain-name/exclusive? records "z" "example.com" #{:claimed :live}))
        "somebody else holds it")
    (is (true? (domain-name/exclusive? records "a" "example.com" #{:claimed :live}))
        "the holder is not blocked by its own claim")
    (is (true? (domain-name/exclusive? records "z" "free.example" #{:claimed :live})))
    (testing "which states reserve is the caller's, and it differs by authority"
      ;; Naming reserves at `:claimed`; mail has no intermediate state and
      ;; reserves only at `:authorized`.
      (is (true? (domain-name/exclusive? records "z" "example.com" #{:authorized}))))))
