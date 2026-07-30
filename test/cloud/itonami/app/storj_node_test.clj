(ns cloud.itonami.app.storj-node-test
  "The node service: configuration, the identity, and the disk.

  No satellite and no network. What is protocol lives in `io-storj-node` and
  is tested there against Go; what is here is the parts this app supplies —
  and the store is the one that could quietly lose a piece."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.storj-node :as node]
            [storj.node.protocols :as p]))

;; ── configuration ───────────────────────────────────────────────────────────

(deftest an-unconfigured-node-is-an-ordinary-state
  ;; supplied rather than read from the environment: asserting the env is
  ;; empty makes the suite fail on a machine that happens to have STORJ_NODE_*
  ;; set, which is a red build caused by a developer's shell
  (with-redefs [node/config (constantly nil)]
    (is (false? (node/configured?)))
    (testing "and status says what is missing rather than just no"
      (let [s (node/status)]
        (is (false? (:configured? s)))
        (is (some #{"STORJ_NODE_IDENTITY_DIR"} (:needs s)))
        (is (some #{"STORJ_NODE_SATELLITE_ID"} (:needs s)))))))

(deftest status-reports-the-gates-rather-than-leaving-them-to-be-found
  ;; a node can be fully configured here and still be refused by a public
  ;; satellite, for two reasons that are not defects and are not visible from
  ;; any single field
  (with-redefs [node/config (constantly {:identity-dir "/nope"
                                         :satellite "s:7777"
                                         :satellite-id "aa"
                                         :address "h:28967" :port 28967})]
    (let [s (node/status)]
      (is (true? (:configured? s)))
      (is (false? (:listening? s)))
      (is (some #(str/includes? % "difficulty 36") (:notes s)))
      (testing "and that no check-in has happened, so nothing can be admitted"
        (is (false? (:satellite-key-known? s)))
        (is (some #(str/includes? % "no check-in yet") (:notes s))))
      (testing "and an unreadable identity is reported, not thrown"
        (is (map? (:node-id s)))
        (is (:error (:node-id s)))))))

(deftest the-satellite-key-is-learned-rather-than-configured
  ;; it is not in `config` and there is no env var for it: the satellite
  ;; presents its chain on the check-in handshake, and `check-in!` dials with
  ;; :expected-node-id so a chain that is not the satellite we meant is
  ;; refused before anything is remembered
  (is (not-any? #(str/includes? (str/lower-case (name %)) "key")
                (keys (or (with-redefs [node/config
                                        (constantly {:identity-dir "d" :satellite "s"
                                                     :satellite-id "aa" :address "a"})]
                            (node/config))
                          {}))))
  (testing "and until one is learned, status says so rather than looking ready"
    (with-redefs [node/config (constantly {:identity-dir "/nope" :satellite "s:1"
                                           :satellite-id "aa" :address "a:1" :port 1})]
      (is (false? (:satellite-key-known? (node/status)))))))

;; ── the store ───────────────────────────────────────────────────────────────

(defn- with-pieces-dir
  "Run `f` against a store rooted in a fresh temporary directory."
  [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "storj-pieces-test"
                      (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (with-redefs [node/pieces-dir (constantly dir)]
        (f (node/file-blobs) dir))
      (finally
        (doseq [x (reverse (file-seq dir))] (.delete x))))))

(deftest a-piece-survives-being-written-and-read
  (with-pieces-dir
    (fn [store _]
      (let [path "aa/bb/cc.sj1"
            body [1 2 250 0 255]]
        (is (not (p/-exists? store path)))
        (p/-put store path body)
        (is (p/-exists? store path))
        (is (= body (p/-get store path))
            "including the bytes above 127, which a signed byte reports negative")))))

(deftest a-piece-that-is-not-there-is-nil-and-not-an-error
  (with-pieces-dir
    (fn [store _]
      (is (nil? (p/-get store "aa/bb/missing.sj1"))))))

(deftest deleting-something-absent-is-refused
  ;; DeletePieces counts what it could not do, and succeeding here would
  ;; report a clean sweep over pieces this node never had
  (with-pieces-dir
    (fn [store _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such piece"
                            (p/-delete store "aa/bb/never.sj1")))
      (p/-put store "aa/bb/real.sj1" [1])
      (p/-delete store "aa/bb/real.sj1")
      (is (not (p/-exists? store "aa/bb/real.sj1"))))))

(deftest the-two-storage-formats-are-different-pieces
  ;; .sj1 and the bare name are how a node tells a verified piece from an
  ;; unverified one after a restart. A store that conflated them would hand
  ;; back a body where a header was expected.
  (with-pieces-dir
    (fn [store _]
      (p/-put store "aa/bb/cc.sj1" [1 1 1])
      (p/-put store "aa/bb/cc" [2 2])
      (is (= [1 1 1] (p/-get store "aa/bb/cc.sj1")))
      (is (= [2 2] (p/-get store "aa/bb/cc"))))))

(deftest a-path-that-escapes-the-pieces-directory-is-refused
  (with-pieces-dir
    (fn [store dir]
      (doseq [bad ["../escaped" "aa/../../escaped" "aa/bb/../../../escaped"]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"escapes the pieces directory"
                              (p/-put store bad [1]))
            bad))
      (testing "and nothing was written outside it"
        (is (nil? (.list (io/file (.getParent dir) "escaped"))))))))

(deftest a-write-leaves-no-temporary-file-behind
  ;; the move is what makes a crash mid-write leave no half piece for a later
  ;; audit to read as corruption; a leftover .tmp would be that half piece
  (with-pieces-dir
    (fn [store dir]
      (p/-put store "aa/bb/cc.sj1" (vec (range 100)))
      (is (empty? (filter #(str/ends-with? (.getName %) ".tmp") (file-seq dir)))))))

(deftest overwriting-a-piece-replaces-it-whole
  (with-pieces-dir
    (fn [store _]
      (p/-put store "aa/bb/cc.sj1" (vec (range 50)))
      (p/-put store "aa/bb/cc.sj1" [9 9])
      (is (= [9 9] (p/-get store "aa/bb/cc.sj1"))
          "not the new bytes over the tail of the old ones"))))
