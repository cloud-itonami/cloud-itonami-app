(ns cloud.itonami.app.bot-dispatcher-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.bot-dispatcher :as dispatcher])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "bot-dispatcher-" (System/nanoTime)))
    (.mkdirs)))

(deftest three-requests-never-place-more-than-two-in-the-provider
  (let [dir (tmp-dir)
        first-two (CountDownLatch. 2)
        release (CountDownLatch. 1)
        entered (atom 0)
        peak (atom 0)
        run (fn []
              (dispatcher/dispatch!
               (fn []
                 (let [n (swap! entered inc)]
                   (swap! peak max n)
                   (.countDown first-two)
                   (.await release 2 TimeUnit/SECONDS)
                   (swap! entered dec)
                   :done))))]
    (binding [dispatcher/*slot-directory* dir]
      (let [runs (mapv (fn [_] (future (run))) (range 3))]
        (is (.await first-two 2 TimeUnit/SECONDS)
            "two published Murakumo slots are admitted")
        (Thread/sleep 100)
        (is (= 2 @entered) "the third request remains queued")
        (is (= 2 (:max-parallel (dispatcher/snapshot))))
        (.countDown release)
        (is (= [:done :done :done] (mapv #(deref % 3000 :timeout) runs)))
        (is (= 2 @peak))))))

(deftest slot-locks-coordinate-with-another-process
  ;; Different FileChannels stand in for two slots held by another JVM. If the
  ;; dispatcher were only the local Semaphore, this request would enter at
  ;; once and the negative assertion would fail.
  (let [dir (tmp-dir)
        open-channel
        (fn [slot]
          (FileChannel/open
           (.toPath (io/file dir (str ".bot-provider-slot-" slot ".lock")))
           (into-array OpenOption [StandardOpenOption/CREATE
                                   StandardOpenOption/WRITE])))
        channel-0 (open-channel 0)
        channel-1 (open-channel 1)
        lock-0 (.lock channel-0)
        lock-1 (.lock channel-1)]
    (try
      (binding [dispatcher/*slot-directory* dir]
        (let [entered (promise)
              run (future
                    (dispatcher/dispatch!
                     #(do (deliver entered :entered) :entered)))]
          (is (= :waiting (deref entered 150 :waiting)))
          (.release lock-0)
          (is (= :entered (deref entered 2000 :timeout)))
          (is (= :entered (deref run 2000 :timeout)))))
      (finally
        (when (.isValid lock-0) (.release lock-0))
        (when (.isValid lock-1) (.release lock-1))
        (.close channel-0)
        (.close channel-1)))))
