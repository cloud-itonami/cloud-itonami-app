(ns cloud.itonami.app.west-kotoba-refactor-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.west-kotoba-refactor :as subject]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "itonami-west-refactor-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write! [root path text]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file text)
    file))

(defn- fixture []
  (let [root (temp-dir)
        repo (io/file root "orgs/kotoba-lang/example")]
    (write! root "manifest/west.yml"
            "manifest:\n  projects:\n    - name: example\n      remote: kotoba-lang\n      revision: abc123\n      path: orgs/kotoba-lang/example\n")
    (.mkdirs (io/file repo ".git"))
    (write! repo "deps.edn" "{}")
    (write! repo "src/example/core.clj" "(ns example.core)\n(defn answer [] 42)\n")
    (write! repo "src/example/policy.cljc" "(ns example.policy)\n")
    (write! repo "src/example/existing.kotoba" "(ns example.existing)\n")
    (write! repo "target/generated.clj" "(ns ignored)\n")
    [root repo]))

(deftest west-project-inspection-is-bounded-and-ignores-build-output
  (let [[root repo] (fixture)
        result (subject/inspect-project root "example" {:limit 1})]
    (is (= "abc123" (get-in result [:project :revision])))
    (is (= (.getCanonicalPath repo) (get-in result [:project :checkout])))
    (is (= {:clj 1 :cljc 1 :cljs 0 :kotoba 1} (:counts result)))
    (is (= 2 (:candidate-count result)))
    (is (= 1 (count (:candidates result))))
    (is (= ["clojure -M:test"] (:verification result)))))

(deftest task-contract-demands-a-real-parity-checked-slice
  (let [[root _] (fixture)
        task (subject/task-text (subject/inspect-project root "example" {:limit 2}))]
    (is (re-find #"最小の1スライス" task))
    (is (re-find #"parity test" task))
    (is (re-find #"push、west pin変更、rebaseはしない" task))
    (is (re-find #"src/example/core.clj" task))))

(deftest missing-checkout-and-unknown-project-fail-closed
  (let [[root _] (fixture)]
    (testing "unknown manifest name"
      (is (= :west-refactor/project-missing
             (:type (ex-data (try (subject/inspect-project root "nope" {})
                                  (catch clojure.lang.ExceptionInfo e e)))))))
    (testing "manifest entry without checkout"
      (write! root "manifest/west.yml"
              "manifest:\n  projects:\n    - name: absent\n      revision: abc\n      path: orgs/x/absent\n")
      (is (= :west-refactor/checkout-missing
             (:type (ex-data (try (subject/inspect-project root "absent" {})
                                  (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest scan-ranks-only-checked-out-projects-with-clj-source
  (let [[root _] (fixture)
        result (subject/scan root {:limit 10})]
    (is (= 1 (:shown result)))
    (is (= "example" (get-in result [:projects 0 :name])))
    (is (= 2 (get-in result [:projects 0 :clj-files])))))
