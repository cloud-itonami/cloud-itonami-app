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

(deftest a-store-larger-than-the-document-bound-still-persists
  (let [dir (tmp-dir)
        file (io/file dir "state.edn")
        ;; Just over the document bound; far under the store bound.
        content (apply str (repeat (+ (* 16 1024 1024) 1024) "x"))]
    (testing "the document bound refuses it -- that bound is not wrong, it is for something else"
      (is (thrown? clojure.lang.ExceptionInfo (host/write-atomic! file content))))
    (testing "and the store's own bound accepts it"
      (host/write-atomic! file content host/store-max-bytes)
      (is (.exists file))
      (is (= (count content) (.length file))))
    (testing "no .tmp is left behind"
      (is (not (.exists (io/file dir "state.edn.tmp")))))))

(deftest the-store-bound-is-a-bound-not-an-absence
  (testing "unbounded is not the alternative to wrong"
    (let [dir (tmp-dir)
          file (io/file dir "state.edn")
          over (apply str (repeat (+ host/store-max-bytes 8) "x"))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (host/write-atomic! file over host/store-max-bytes)))))
  (testing "and it is larger than the document bound, which is the whole point"
    (is (> host/store-max-bytes (* 16 1024 1024)))))
