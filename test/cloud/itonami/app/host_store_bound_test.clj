(ns cloud.itonami.app.host-store-bound-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cloud.itonami.app.host :as host]))

;; The resident could not start on 2026-08-20. Every host write was routed
;; through a filesystem confined to a 16 MiB bound meant for DOCUMENTS, and
;; the store's own state file had grown to 28 MB, so `fs-host/refuse!` threw
;; `content exceeds :max-bytes` on state.edn.tmp and the process died before
;; it listened.
;;
;; Nothing untrusted has ever gone through that path -- `write-atomic!` has
;; exactly one caller and it is the store. A bound against content we did not
;; author was applied to content we author. The two bounds are now distinct,
;; and this asserts the distinction rather than the number.

(defn- tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "store-bound-" (System/nanoTime)))
    (.mkdirs)))

(defn- delete-tree! [file]
  (when (.exists file)
    (doseq [entry (reverse (file-seq file))]
      (when (and (.exists entry) (not (.delete entry)))
        (throw (ex-info "test temp entry could not be deleted"
                        {:path (.getPath entry)}))))))

(defn- with-tmp-dir [f]
  (let [dir (tmp-dir)]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

(deftest a-store-larger-than-the-document-bound-still-persists
  (with-tmp-dir
    (fn [dir]
      (let [file (io/file dir "state.edn")
            ;; Just over the document bound; far under the store bound.
            content (apply str (repeat (+ (* 16 1024 1024) 1024) "x"))]
        (testing "the document bound refuses it -- that bound is not wrong, it is for something else"
          (is (thrown? clojure.lang.ExceptionInfo (host/write-atomic! file content))))
        (testing "and the store's own bound accepts it"
          (host/write-atomic! file content host/store-max-bytes)
          (is (.exists file))
          (is (= (count content) (.length file))))
        (testing "no .tmp is left behind"
          (is (not (.exists (io/file dir "state.edn.tmp")))))))))

(deftest the-store-bound-is-a-bound-not-an-absence
  (testing "unbounded is not the alternative to wrong"
    (with-tmp-dir
      (fn [dir]
        (let [file (io/file dir "state.edn")
              over (apply str (repeat (+ host/store-max-bytes 8) "x"))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (host/write-atomic! file over host/store-max-bytes)))))))
  (testing "and it is larger than the document bound, which is the whole point"
    (is (> host/store-max-bytes (* 16 1024 1024)))))

(deftest durable-appends-extend-without-rewriting-and-respect-the-bound
  (let [dir (tmp-dir)
        file (io/file dir "state.journal.edn")]
    (host/append-durable! file "abc" 8)
    (host/append-durable! file "def" 8)
    (is (= "abcdef" (slurp file)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (host/append-durable! file "ghi" 8)))
    (is (= "abcdef" (slurp file)) "a refused append leaves durable bytes intact")))

(deftest it-says-so-before-the-write-that-cannot-happen
  ;; The store crossed its bound on 2026-08-20 with no prior signal: writes
  ;; succeeded at 99% and the process failed to START at 101%. The first thing
  ;; anyone learned was that the fleet was down. A bound with no approach
  ;; warning is a cliff.
  ;;
  ;; Sizes are exact byte counts, not fractions of the bound: the first version
  ;; used (int (* 0.8 bound)) and asserted "80%", which the truncation made 79
  ;; and put in a different decile. That tested my arithmetic, not the warning.
  (with-tmp-dir
    (fn [dir]
      (let [file (io/file dir "state.edn")
            bound (* 4 1024 1024)                 ;; 4194304
            say (fn [n] (let [out (java.io.StringWriter.)]
                          (binding [*err* out]
                            (host/write-atomic! file (apply str (repeat n "x")) bound))
                          (str out)))]
        (testing "well under the bound, nothing is said"
          (is (= "" (say 1024))))
        (testing "past the fraction it warns, with a number and the consequence"
          (let [msg (say 3500000)]                ;; 83% -> decile 8
            (is (re-find #"WARNING" msg))
            (is (re-find #"\d+% of its" msg))
            (is (re-find #"will not start" msg)
                "the warning must say what happens at the bound, not just report a number")))
        (testing "and does not repeat within the same decile"
          (is (= "" (say 3540000))))              ;; 84% -> still decile 8
        (testing "but speaks again when it moves closer"
          (is (re-find #"WARNING" (say 3900000)))) ;; 92% -> decile 9
        (testing "the bound itself still refuses -- the warning does not replace it"
          (is (thrown? clojure.lang.ExceptionInfo
                       (host/write-atomic! file (apply str (repeat (+ bound 8) "x")) bound))))))))

(deftest the-test-temp-directory-is-reclaimed-on-failure
  (let [created (atom nil)]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"probe"
          (with-tmp-dir
            (fn [dir]
              (reset! created dir)
              (spit (io/file dir "probe") "temporary")
              (throw (ex-info "probe" {}))))))
    (is (not (.exists @created)))))
