(ns cloud.itonami.app.store-journal-test
  "The journal, and the one question it has to answer before replaying:
  does this journal belong to the snapshot beside it?

  Measured 2026-08-27 on the resident store. A build with journalling was
  replaced by an auto-update built from a branch without it. The new server
  read the snapshot, could not see the journal, and rewrote the snapshot from
  its own memory for an hour. The journal survived on disk holding 2,057
  operations and every single one of them disagreed with the newer snapshot --
  turn counts, token totals, whole message vectors, all older. The next
  journalling process to start would have replayed them: an hour-long silent
  rollback, presented as a normal start.

  Nothing in the old code could have noticed. A journal was replayed because it
  was there."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.host :as host]
            [cloud.itonami.app.store :as store]))

(defn- tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "store-journal-" (System/nanoTime)))
    (.mkdirs)))

(defn- record [base-bytes ops]
  (str (pr-str {:schema "cloud.itonami.app.state-journal.v1"
                :at "2026-08-27T10:50:13.944805Z"
                :base-bytes base-bytes
                :ops ops})
       "\n"))

;; ── the delta ──────────────────────────────────────────────────────────────

(deftest a-delta-touches-only-what-changed
  (let [before {:bots {:a {:turns 5 :usage {:total 100}} :b {:turns 1}}}
        after  (assoc-in before [:bots :a :turns] 7)]
    (is (= [{:op :assoc :path [:bots :a :turns] :value 7}]
           (store/state-delta before after))
        "an untouched branch keeps its identity, so it is not walked")))

(deftest a-delta-records-a-removal-as-a-removal
  (is (= [{:op :dissoc :path [:bots :b]}]
         (store/state-delta {:bots {:a 1 :b 2}} {:bots {:a 1}}))))

(deftest replaying-a-record-twice-is-replaying-it-once
  ;; Why a crash between the snapshot write and the journal truncation is
  ;; harmless: leaves are replaced whole rather than accumulated.
  (let [before {:runs {:x {:messages [1 2]}}}
        after  {:runs {:x {:messages [1 2 3]}}}
        ops    (store/state-delta before after)
        once   (reduce #(assoc-in %1 (:path %2) (:value %2)) before ops)
        twice  (reduce #(assoc-in %1 (:path %2) (:value %2)) once ops)]
    (is (= after once))
    (is (= once twice))))

;; ── the guard ──────────────────────────────────────────────────────────────

(deftest a-journal-that-belongs-to-the-snapshot-is-replayed
  ;; The positive control. Without it the test below passes for a version of
  ;; this code that never replays anything at all.
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")
        base {:bots {:runs {"r" {:turn-count 5}}}}]
    (spit file (record 4096 [{:op :assoc :path [:bots :runs "r" :turn-count] :value 7}]))
    (is (= 7 (get-in (store/replay-journal base file 4096)
                     [:bots :runs "r" :turn-count])))
    (is (.exists file) "a journal that belongs is left where it is")))

(deftest a-journal-from-another-snapshot-is-not-replayed
  ;; The defect, in the shape it actually had: the journal's ops are OLDER
  ;; than the snapshot, so replaying them is a rollback.
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")
        ;; what the snapshot on disk now says -- newer, written by the build
        ;; that could not see this journal
        base {:bots {:runs {"r" {:turn-count 7 :usage {:total_tokens 69968}}}}}]
    (spit file (record 4096 [{:op :assoc :path [:bots :runs "r" :turn-count] :value 5}
                             {:op :assoc :path [:bots :runs "r" :usage :total_tokens]
                              :value 47590}]))
    (let [result (store/replay-journal base file 31830280)]
      (testing "the newer values survive"
        (is (= 7 (get-in result [:bots :runs "r" :turn-count])))
        (is (= 69968 (get-in result [:bots :runs "r" :usage :total_tokens]))))
      (testing "and the orphan is moved aside rather than deleted"
        (is (not (.exists file)))
        (is (seq (filter #(.startsWith (.getName %) "state.journal.edn.orphan-")
                         (.listFiles dir)))
            "the only remaining copy of what the previous build recorded")))))

(deftest a-journal-with-no-base-recorded-does-not-belong
  ;; Written before this check existed. It cannot be vouched for.
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")
        base {:v 7}]
    (spit file (str (pr-str {:schema "cloud.itonami.app.state-journal.v1"
                             :at "2026-08-27T10:50:13.944805Z"
                             :ops [{:op :assoc :path [:v] :value 5}]}) "\n"))
    (is (= 7 (:v (store/replay-journal base file 4096))))))

(deftest the-guard-refuses-for-the-reason-it-names
  ;; A negative test that only asserts "not replayed" passes when replay fails
  ;; for some unrelated reason. Hold everything else equal and move ONE value:
  ;; the same journal, the same base, one byte count.
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")
        base {:v 7}
        write! #(spit file (record 4096 [{:op :assoc :path [:v] :value 5}]))]
    (write!)
    (is (= 5 (:v (store/replay-journal base file 4096))) "matching base: replayed")
    (write!)
    (is (= 7 (:v (store/replay-journal base file 4097))) "one byte apart: refused")))

;; ── the amplification the journal exists to remove ─────────────────────────

(deftest an-append-does-not-rewrite-the-file
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")]
    (host/append-durable! (.getPath file) "one\n" host/journal-max-bytes)
    (host/append-durable! (.getPath file) "two\n" host/journal-max-bytes)
    (is (= "one\ntwo\n" (slurp file)))
    (is (= 8 (host/file-size (.getPath file))))))

(deftest an-append-is-bounded
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")]
    (host/append-durable! (.getPath file) "12345678" 16)
    (testing "the second append would pass the bound, so it is refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (host/append-durable! (.getPath file) "123456789" 16))))
    (is (= 8 (host/file-size (.getPath file))) "and nothing was written")))

(deftest file-size-of-an-absent-file-is-zero-not-an-error
  (is (= 0 (host/file-size (.getPath (io/file (tmp-dir) "absent.edn"))))))
