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
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.host :as host]
            [cloud.itonami.app.host-bounds :as host-bounds]
            [cloud.itonami.app.store :as store]))

(defn- tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "store-journal-" (System/nanoTime)))
    (.mkdirs)))

(defn- record
  ([base-bytes ops] (record base-bytes nil ops))
  ([base-bytes base-sha ops]
   (str (pr-str (cond-> {:schema "cloud.itonami.app.state-journal.v1"
                         :at "2026-08-27T10:50:13.944805Z"
                         :base-bytes base-bytes
                         :ops ops}
                  base-sha (assoc :base-sha256 base-sha)))
        "\n")))

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
  ;; harmless: leaves are replaced whole, and an append refuses to run twice
  ;; through its length-and-content guard.
  (let [apply-op (deref (ns-resolve 'cloud.itonami.app.store 'apply-op))
        before {:runs {:x {:messages [1 2] :turn-count 1}}}
        after  {:runs {:x {:messages [1 2 3] :turn-count 2}}}
        ops    (store/state-delta before after)
        once   (reduce apply-op before ops)
        twice  (reduce apply-op once ops)]
    (is (= after once))
    (is (= once twice))))

;; ── the append op (ADR-2608291500 Phase 1) ─────────────────────────────────

(deftest a-vector-that-only-grew-journals-only-its-tail
  ;; The measured waste this exists to remove: 95% of journal bytes were
  ;; whole-vector rewrites of :messages and :job/events vectors that had only
  ;; grown at the end (2026-08-29, live resident store).
  (let [before {:runs {:x {:messages [:a :b]}}}
        after  {:runs {:x {:messages [:a :b :c :d]}}}]
    (is (= [{:op :append :path [:runs :x :messages] :from 2 :value [:c :d]}]
           (store/state-delta before after))))
  (testing "any change that is not a pure tail growth is replaced whole"
    (is (= [{:op :assoc :path [:runs :x :messages] :value [:z :b :c]}]
           (store/state-delta {:runs {:x {:messages [:a :b]}}}
                              {:runs {:x {:messages [:z :b :c]}}}))
        "a mutated element disqualifies the append")
    (is (= [{:op :assoc :path [:runs :x :messages] :value [:a]}]
           (store/state-delta {:runs {:x {:messages [:a :b]}}}
                              {:runs {:x {:messages [:a]}}}))
        "so does shrinking")))

(deftest an-append-that-does-not-fit-refuses-for-the-reason-it-names
  ;; A blind `into` would silently duplicate the tail after a crash-window
  ;; refold, and a silent skip would hide a journal that belongs to some other
  ;; state. Both wrong states must be told apart from the two right ones.
  (let [apply-op (deref (ns-resolve 'cloud.itonami.app.store 'apply-op))
        op {:op :append :path [:runs :x :messages] :from 2 :value [:c :d]}]
    (testing "exactly at :from -- applies"
      (is (= {:runs {:x {:messages [:a :b :c :d]}}}
             (apply-op {:runs {:x {:messages [:a :b]}}} op))))
    (testing "already folded -- the very tail is present -- no-op"
      (let [folded {:runs {:x {:messages [:a :b :c :d]}}}]
        (is (= folded (apply-op folded op)))))
    (testing "shorter than :from -- refused by name"
      (is (= :store/invalid-journal-append
             (try (apply-op {:runs {:x {:messages [:a]}}} op)
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
    (testing "long enough but carrying a DIFFERENT tail -- refused by name"
      (is (= :store/invalid-journal-append
             (try (apply-op {:runs {:x {:messages [:a :b :x :y]}}} op)
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))

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

;; ── digest identity (ADR-2608291500 Phase 0) ───────────────────────────────

(deftest equal-length-is-not-identity-when-both-sides-carry-a-digest
  ;; The one case the byte check cannot see: two different snapshots of
  ;; coincidentally equal LENGTH. Hold the byte count equal and move only the
  ;; digest -- if this replays, size is being read as identity again.
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")
        base {:v 7}
        ops [{:op :assoc :path [:v] :value 5}]
        sha-a (apply str (repeat 64 "a"))
        sha-b (apply str (repeat 64 "b"))]
    (spit file (record 4096 sha-a ops))
    (is (= 5 (:v (store/replay-journal base file 4096 sha-a)))
        "matching digest: replayed")
    (spit file (record 4096 sha-a ops))
    (is (= 7 (:v (store/replay-journal base file 4096 sha-b)))
        "same byte count, different digest: refused")))

(deftest a-journal-without-a-digest-still-replays-on-matching-bytes
  ;; Written by a build before the field existed. The byte comparison remains
  ;; the answer for it -- the upgrade must not orphan every existing journal.
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")
        base {:v 7}]
    (spit file (record 4096 [{:op :assoc :path [:v] :value 5}]))
    (is (= 5 (:v (store/replay-journal base file 4096
                                       (apply str (repeat 64 "a"))))))))

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

;; ── the bound has to be a trigger, not a wall ──────────────────────────────

(deftest writing-past-the-journal-budget-keeps-working
  ;; The regression, reproduced. Deployed 2026-08-27 23:34 and measured at
  ;; 08:28 the next morning: the journal had stopped 218 bytes below its 4 MiB
  ;; bound and stayed there for eight and a half hours. Every record after that
  ;; was bigger than the gap, so the append was refused -- and the refusal came
  ;; BEFORE the line that would have checkpointed and made room. The store
  ;; recorded nothing, five runs sat in :running that could never finish, and
  ;; the only outward sign was `content-too-large` among the turn outcomes.
  ;;
  ;; So: write more than the whole journal budget, in pieces, and require that
  ;; every one of them lands AND that the result is still recoverable.
  (let [dir (tmp-dir)
        property "cloud.itonami.data-dir"
        previous (System/getProperty property)
        persist (ns-resolve 'cloud.itonami.app.store 'persist-delta!)
        counter (ns-resolve 'cloud.itonami.app.store 'journal-entry-count)]
    (try
      (System/setProperty property (.getPath dir))
      (reset! (deref counter) 0)
      ;; ~400 KB per record against a 4 MiB budget: the bound is crossed
      ;; several times over, so a wall would be hit and a trigger would not.
      (let [blob (apply str (repeat 400000 "x"))
            states (mapv (fn [i] {:schema "s" :n i :blob (str blob i)}) (range 20))]
        (doseq [[before after] (partition 2 1 (cons {:schema "s"} states))]
          ((deref persist) before after))
        (testing "every write landed -- none of them threw"
          (is (= 19 (dec (count states)))))
        (testing "and the store still reconstructs the final state"
          (let [snapshot (edn/read-string (slurp (store/state-file)))
                recovered (store/replay-journal
                           snapshot
                           (store/journal-file)
                           (host/file-size (.getPath (store/state-file))))]
            (is (= (:n (last states)) (:n recovered)))
            (is (= (:blob (last states)) (:blob recovered))))))
      (finally
        (if previous
          (System/setProperty property previous)
          (System/clearProperty property))))))

(deftest a-record-larger-than-the-whole-budget-becomes-a-snapshot
  ;; Checkpointing cannot make room for this one, so the journal is the wrong
  ;; shape for it. Measured: one mail-sync transaction produced 2,041 ops and
  ;; 393 KB, so a single record CAN approach the budget.
  (is (host-bounds/record-needs-its-own-snapshot? (* 4 1024 1024) (* 4 1024 1024)))
  (is (host-bounds/record-needs-its-own-snapshot? (inc (* 4 1024 1024)) (* 4 1024 1024)))
  (is (not (host-bounds/record-needs-its-own-snapshot? 1024 (* 4 1024 1024)))))

(deftest the-room-check-is-asked-before-the-append-not-after
  ;; The shape of the defect in one assertion: a journal 218 bytes short of its
  ;; bound has no room for a record of 400, and that has to be knowable WITHOUT
  ;; attempting the append that would throw.
  (is (host-bounds/append-exceeds-bound? (- (* 4 1024 1024) 218) 400 (* 4 1024 1024)))
  (is (not (host-bounds/append-exceeds-bound? (- (* 4 1024 1024) 218) 200 (* 4 1024 1024)))))
