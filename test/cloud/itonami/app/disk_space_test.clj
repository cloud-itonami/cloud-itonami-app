(ns cloud.itonami.app.disk-space-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.disk-space :as disk-space]))

(defn- temp-root []
  (.toFile (java.nio.file.Files/createTempDirectory
            "cloud-itonami-disk-candidates"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- error-type [f]
  (:type (ex-data (try (f) (catch Exception error error)))))

(defn- fixture-file! [root relative content]
  (let [file (io/file root relative)]
    (io/make-parents file)
    (spit file content)
    file))

(deftest maintenance-is-a-fixed-threshold-operation
  (testing "healthy disks are observed without invoking deletion"
    (let [calls (atom [])]
      (with-redefs [disk-space/usable-bytes (constantly (+ disk-space/threshold-bytes 1))
                    disk-space/run-helper! (fn [mode] (swap! calls conj mode))]
        (let [result (disk-space/maintain!)]
          (is (= "none" (:action result)))
          (is (empty? @calls))))))

  (testing "pressure invokes only the reviewed extended cleanup mode"
    (let [measurements (atom [(- disk-space/threshold-bytes 1024)
                              (+ disk-space/threshold-bytes 2048)])
          calls (atom [])]
      (with-redefs [disk-space/usable-bytes #(let [n (first @measurements)]
                                                (swap! measurements rest)
                                                n)
                    disk-space/run-helper! (fn [mode]
                                              (swap! calls conj mode)
                                              {:exit 0 :output "ok"})]
        (let [result (disk-space/maintain!)]
          (is (= "cleanup" (:action result)))
          (is (= ["apply-extended"] @calls))
          (is (pos? (:reclaimed-bytes result)))
          (is (false? (get-in result [:after :pressure?])))))))

  (testing "a scheduler-supplied status is not measured a second time"
    (let [usable-calls (atom 0)
          before {:schema "cloud.itonami.app.disk-space.v1"
                  :usable-bytes (- disk-space/threshold-bytes 1024)
                  :threshold-bytes disk-space/threshold-bytes
                  :pressure? true}]
      (with-redefs [disk-space/usable-bytes
                    (fn [] (swap! usable-calls inc)
                      (+ disk-space/threshold-bytes 2048))
                    disk-space/run-helper! (constantly {:exit 0 :output "ok"})]
        (let [result (disk-space/maintain! before)]
          (is (= 1 @usable-calls) "only the post-cleanup measurement remains")
          (is (= before (:before result)))
          (is (= "cleanup" (:action result))))))))

(deftest helper-modes-are-not-an-arbitrary-process-surface
  (is (= :disk-space/invalid-mode
         (:type (ex-data
                 (try (disk-space/run-helper! "../../anything")
                      (catch Exception error error)))))))

(deftest candidate-inventory-mints-no-path-authority
  (let [root (temp-root)]
    (fixture-file! root "standalone/package.json" "{}")
    (fixture-file! root "standalone/pnpm-lock.yaml" "lockfileVersion: 9")
    (fixture-file! root "standalone/node_modules/pkg/index.js" "regenerable")
    (fixture-file! root "missing-lock/package.json" "{}")
    (fixture-file! root "missing-lock/node_modules/pkg/index.js" "preserve")
    (fixture-file! root "model/weights.gguf" "review me")
    (fixture-file! root "pnpm-real-store/v10/files/aa/content" "cache")
    (fixture-file! root "pnpm-real-store/v10/index/aa/metadata" "cache")
    (fixture-file! root "pnpm-fake-store/private.txt" "not a pnpm store")
    (with-redefs [disk-space/candidate-roots (constantly [{:kind :temporary :path root}])
                  disk-space/open-file-state (constantly :clear)]
      (let [result (disk-space/inventory)
            candidates (:candidates result)
            module (some #(when (= :temporary-node-modules (:kind %)) %) candidates)
            model (some #(when (= :model-artifact (:kind %)) %) candidates)]
        (is (= 3 (:candidate-count result))
            "missing lockfiles and name-only cache lookalikes are not candidates")
        (is (= :reclaimable (:decision module)))
        (is (re-matches #"[0-9a-f]{64}" (:candidate-id module)))
        (is (nil? (:path module)) "the receipt conveys identity, not path authority")
        (is (= {:root "temporary" :relative "standalone/node_modules"}
               (:locator module))
            "the audit locator is readable but is not accepted by the write tool")
        (is (= :review-required (:decision model))
            "model artifacts are surfaced but never admitted for automatic deletion")
        (is (= 1 (count (filter #(= :pnpm-temporary-store (:kind %)) candidates)))
            "the pnpm name needs the version/files/index store structure")))))

(deftest git-and-open-file-evidence-fail-closed
  (let [root (temp-root)]
    (fixture-file! root "repo/.git/HEAD" "ref: refs/heads/main")
    (fixture-file! root "repo/package.json" "{}")
    (fixture-file! root "repo/package-lock.json" "{}")
    (fixture-file! root "repo/node_modules/pkg/index.js" "preserve")
    (fixture-file! root "open/package.json" "{}")
    (fixture-file! root "open/yarn.lock" "")
    (fixture-file! root "open/node_modules/pkg/index.js" "preserve")
    (with-redefs [disk-space/candidate-roots (constantly [{:kind :temporary :path root}])
                  disk-space/open-file-state
                  (fn [path] (if (.contains (str path) "open/node_modules") :open :clear))]
      (let [candidates (:candidates (disk-space/inventory))]
        (is (every? #(= :review-required (:decision %)) candidates))
        (is (some #(true? (get-in % [:evidence :git-owned?])) candidates))
        (is (some #(= :open (get-in % [:evidence :open-file-state])) candidates))))))

(deftest an-explicitly-ignored-untracked-dependency-tree-is-not-the-worktree
  (let [root (temp-root)
        repo (io/file root "repo")]
    (fixture-file! repo ".gitignore" "node_modules/\n")
    (fixture-file! repo "package.json" "{}")
    (fixture-file! repo "package-lock.json" "{}")
    (fixture-file! repo "node_modules/pkg/index.js" "regenerable")
    (is (zero? (:exit (shell/sh "/usr/bin/git" "-C" (.getPath repo) "init" "-q"))))
    (is (zero? (:exit (shell/sh "/usr/bin/git" "-C" (.getPath repo) "add"
                               ".gitignore" "package.json" "package-lock.json"))))
    (with-redefs [disk-space/candidate-roots (constantly [{:kind :temporary :path root}])
                  disk-space/open-file-state (constantly :clear)]
      (let [candidate (-> (disk-space/inventory) :candidates first)]
        (is (= :reclaimable (:decision candidate)))
        (is (true? (get-in candidate [:evidence :git-owned?])))
        (is (true? (get-in candidate [:evidence :git-ignored?])))
        (is (false? (get-in candidate [:evidence :git-tracked?])))
        (is (= "repo/node_modules" (get-in candidate [:locator :relative])))))))

(deftest cmake-build-needs-a-distinct-existing-source-tree
  (let [root (temp-root)
        source (io/file root "source")
        build (io/file root "build")
        inline (io/file root "inline")]
    (.mkdirs source)
    (fixture-file! build "CMakeCache.txt"
                   (str "CMAKE_HOME_DIRECTORY:INTERNAL=" (.getPath source)))
    (fixture-file! build "object.o" "regenerable")
    (fixture-file! inline "CMakeCache.txt"
                   (str "CMAKE_HOME_DIRECTORY:INTERNAL=" (.getPath inline)))
    (with-redefs [disk-space/candidate-roots (constantly [{:kind :temporary :path root}])
                  disk-space/open-file-state (constantly :clear)]
      (let [candidates (:candidates (disk-space/inventory))]
        (is (= 1 (count (filter #(= :cmake-build (:kind %)) candidates))))
        (is (= "build" (get-in (first candidates) [:locator :relative]))
            "an in-source CMake tree is never classified as a disposable build")))))

(deftest reclaim-accepts-only-fresh-bounded-candidate-ids
  (let [root (temp-root)
        target (fixture-file! root "project/node_modules/pkg/index.js" "regenerable")]
    (fixture-file! root "project/package.json" "{}")
    (fixture-file! root "project/package-lock.json" "{}")
    (with-redefs [disk-space/candidate-roots (constantly [{:kind :temporary :path root}])
                  disk-space/open-file-state (constantly :clear)
                  disk-space/usable-bytes (constantly (dec disk-space/threshold-bytes))]
      (let [id (-> (disk-space/inventory) :candidates first :candidate-id)
            result (disk-space/reclaim! {:candidate_ids [id]})]
        (is (= "reclaim" (:action result)))
        (is (= [id] (:reclaimed-candidate-ids result)))
        (is (not (.exists target))))
      (is (= :disk-space/invalid-input
             (error-type #(disk-space/reclaim! {:path (str root)})))
          "raw paths cannot be turned into deletion authority")
      (is (= :disk-space/invalid-input
             (error-type #(disk-space/reclaim!
                           {:locator {:root "temporary"
                                      :relative "project/node_modules"}}))))
      (is (= :disk-space/stale-candidate
             (error-type #(disk-space/reclaim!
                           {:candidate_ids [(apply str (repeat 64 "a"))]})))))))

(deftest deterministic-cycle-escalates-only-while-pressure-remains
  (let [before {:schema "cloud.itonami.app.disk-space.v1"
                :usable-bytes 100 :threshold-bytes 1000 :pressure? true}
        after-fixed (assoc before :usable-bytes 200)
        candidate-id (apply str (repeat 64 "b"))
        calls (atom [])
        readings (atom [300 300])]
    (with-redefs [disk-space/maintain!
                  (fn [supplied]
                    (swap! calls conj :fixed)
                    {:schema "cloud.itonami.app.disk-space-maintenance.v1"
                     :action "cleanup" :before supplied :after after-fixed
                     :reclaimed-bytes 100})
                  disk-space/inventory
                  (fn []
                    (swap! calls conj :inventory)
                    {:candidate-count 1 :truncated? false :reclaimable-bytes 200
                     :candidates [{:candidate-id candidate-id :kind :cmake-build
                                   :bytes 200 :decision :reclaimable}]})
                  disk-space/reclaim!
                  (fn [input]
                    (swap! calls conj [:reclaim input])
                    {:action "reclaim" :reclaimed-candidate-ids (:candidate_ids input)})
                  disk-space/settle! (constantly nil)
                  disk-space/usable-bytes
                  #(let [n (first @readings)] (swap! readings rest) n)]
      (let [result (disk-space/reconcile! before)]
        (is (= "cleanup-and-reclaim" (:action result)))
        (is (= [:fixed :inventory [:reclaim {:candidate_ids [candidate-id]}]] @calls))
        (is (true? (get-in result [:stable-observation :stable?])))
        (is (true? (get-in result [:after :pressure?]))
            "a completed cycle does not falsely claim that pressure was relieved")))))
