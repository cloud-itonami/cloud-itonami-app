(ns cloud.itonami.app.bot-identity-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot-identity :as bot-identity])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "bot-identity-" (make-array FileAttribute 0))))

(defmacro with-seed-dir [dir & body]
  `(with-redefs [bot-identity/seed-file (fn [] (io/file ~dir "bot-identity.seed"))]
     ~@body))

(deftest a-bot-has-a-name-that-outlives-this-process
  (let [dir (temp-dir)]
    (with-seed-dir dir
      (testing "the did is a did:key and is derived, not random"
        (let [a (bot-identity/bot-did "bot-workforce-aaa")]
          (is (re-find #"^did:key:z6Mk" (str a)))
          (is (= a (bot-identity/bot-did "bot-workforce-aaa"))
              "the same Bot must keep its name across calls -- re-provisioning
               reproduces the id, so it must reproduce the did")))

      (testing "different Bots are different subjects"
        (is (not= (bot-identity/bot-did "bot-workforce-aaa")
                  (bot-identity/bot-did "bot-workforce-bbb"))))

      (testing "the seed is owner-only"
        (bot-identity/bot-did "bot-workforce-aaa")
        (is (= #{java.nio.file.attribute.PosixFilePermission/OWNER_READ
                 java.nio.file.attribute.PosixFilePermission/OWNER_WRITE}
               (set (Files/getPosixFilePermissions
                     (.toPath (io/file dir "bot-identity.seed"))
                     (make-array LinkOption 0))))))

      (testing "no id is no identity, not an exception"
        ;; A Bot without a name is a Bot missing a field. Throwing here would
        ;; trade a whole workforce for a value nothing has used yet.
        (is (nil? (bot-identity/bot-did nil)))
        (is (nil? (bot-identity/bot-did "")))))

    (testing "an unreadable seed degrades to no identity"
      (with-redefs [bot-identity/seed-file (fn [] (io/file "/proc/nonexistent/seed"))]
        (is (nil? (bot-identity/bot-did "bot-workforce-aaa")))))

    (testing "the subject lands in the shared identity shape"
      (with-seed-dir dir
        (let [s (bot-identity/subject {:bot/id "bot-workforce-aaa"
                                       :bot/name "山犬と硯"
                                       :bot/workforce-key "mangaka/work-yamainu"})]
          (is (= :agent (:identity.subject/type s)))
          (is (= (bot-identity/bot-did "bot-workforce-aaa") (:identity.subject/did s)))
          (is (contains? (:identity.subject/labels s) "mangaka/work-yamainu")))))))

(deftest the-seed-is-written-once-and-not-silently-replaced
  ;; Regenerating renames every Bot at once. That must not happen as a side
  ;; effect of reading.
  (let [dir (temp-dir)]
    (with-seed-dir dir
      (let [first-did (bot-identity/bot-did "bot-workforce-aaa")
            bytes (Files/readAllBytes (.toPath (io/file dir "bot-identity.seed")))]
        (dotimes [_ 3] (bot-identity/bot-did "bot-workforce-aaa"))
        (is (= (seq bytes)
               (seq (Files/readAllBytes (.toPath (io/file dir "bot-identity.seed")))))
            "reading an identity must not rewrite the seed")
        (is (= first-did (bot-identity/bot-did "bot-workforce-aaa")))))))
