(ns cloud.itonami.app.workforce-classpath-test
  "The projection's classpath, and the two ways it used to fail silently.

  On 2026-09-08 loop-yakuwari's sources moved from `clojure.string` to
  `kotoba.lang.text`. The registry declared the new sibling in its own
  `nbb.edn`; this app built the projection classpath from a list written here
  by hand, which did not learn it. Every `workforce_provision` after that
  ended in `:workforce/command-failed`, whose wire body carried nine words
  while the reason -- \"Could not find namespace: kotoba.lang.text\" -- sat in
  `:detail`. Registry edits could not reach running Bots for sixteen hours and
  nothing said so until somebody tried to add a business.

  So both halves are asserted: the classpath follows what the registry
  declares, and an absent checkout is refused BY NAME rather than by
  subprocess dump."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.workforce :as subject]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "itonami-workforce-cp-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write! [root path text]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file text)
    file))

(deftest classpath-follows-what-the-registry-declares
  (testing "a sibling added to the registry's nbb.edn reaches the classpath
            without an edit here -- the drift that broke provisioning"
    (let [root (temp-dir)]
      (write! root "nbb.edn"
              (str "{:paths [\"src\" \"bin\"]\n"
                   " :deps {io.github.kotoba-lang/yakuwari {:local/root \"../../kotoba-lang/yakuwari\"}\n"
                   "        io.github.kotoba-lang/new-sibling {:local/root \"../../kotoba-lang/new-sibling\"}}}\n"))
      (is (some #{"orgs/kotoba-lang/new-sibling/src"}
                (subject/registry-dependencies root)))
      (is (every? (set (subject/registry-dependencies root))
                  subject/dependency-floor)
          "the floor survives whatever the registry declares")))

  (testing "a declaration that is PRESENT BUT INCOMPLETE still gets the floor --
            the exact shape of the 2026-09-08 outage, where nbb.edn named two
            siblings and the sources needed three"
    (let [root (temp-dir)]
      (write! root "nbb.edn"
              (str "{:deps {io.github.kotoba-lang/yakuwari {:local/root \"../../kotoba-lang/yakuwari\"}\n"
                   "        io.github.kotoba-lang/yakuwari-view {:local/root \"../../kotoba-lang/yakuwari-view\"}}}\n"))
      (is (some #{"orgs/kotoba-lang/text/src"} (subject/registry-dependencies root)))))

  (testing "coordinates that are not sibling checkouts are left alone rather
            than guessed at"
    (let [root (temp-dir)]
      (write! root "nbb.edn"
              (str "{:deps {io.github.kotoba-lang/yakuwari {:local/root \"../../kotoba-lang/yakuwari\"}\n"
                   "        io.github.other/remote {:git/sha \"abc123\"}\n"
                   "        io.github.other/deep {:local/root \"../../../elsewhere/x\"}}}\n"))
      (is (not-any? #(re-find #"elsewhere|remote" %)
                    (subject/registry-dependencies root)))
      (is (= (set subject/dependency-floor)
             (set (subject/registry-dependencies root)))
          "nothing but the floor: neither coordinate names a sibling checkout")))

  (testing "no nbb.edn, unreadable EDN, and no sibling at all each fall back to
            the floor -- none of them turns provisioning into a parse error"
    (let [absent (temp-dir)
          broken (doto (temp-dir) (write! "nbb.edn" "{:deps {"))
          empty (doto (temp-dir) (write! "nbb.edn" "{:deps {io.github.other/remote {:git/sha \"abc\"}}}"))]
      (is (= subject/dependency-floor (subject/registry-dependencies absent)))
      (is (= subject/dependency-floor (subject/registry-dependencies broken)))
      (is (= subject/dependency-floor (subject/registry-dependencies empty)))))

  (testing "the floor still carries the sibling whose absence caused the
            outage, so a registry without nbb.edn is not the old bug again"
    (is (some #{"orgs/kotoba-lang/text/src"} subject/dependency-floor))))

(deftest an-absent-dependency-is-named-rather-than-dumped
  (let [workspace (temp-dir)
        dependencies ["orgs/kotoba-lang/yakuwari/src" "orgs/kotoba-lang/text/src"]]
    (testing "the one that is not on disk is the one reported"
      (.mkdirs (io/file workspace "orgs/kotoba-lang/yakuwari/src"))
      (is (= ["orgs/kotoba-lang/text/src"]
             (subject/missing-dependencies workspace dependencies))))
    (testing "and nothing is reported once it is"
      (.mkdirs (io/file workspace "orgs/kotoba-lang/text/src"))
      (is (= [] (subject/missing-dependencies workspace dependencies))))
    (testing "a file where a source root belongs is missing, not present"
      (write! workspace "orgs/kotoba-lang/decoy/src" "not a directory")
      (is (= ["orgs/kotoba-lang/decoy/src"]
             (subject/missing-dependencies workspace ["orgs/kotoba-lang/decoy/src"]))))))
