(ns cloud.itonami.app.host-grant-test
  "aiueos-shaped grant gate on the host seam (ADR-0067).

  Both directions: denied when the set is bound without the cap, and allowed
  when the cap is present. Unbound nil remains the legacy desktop host."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.host :as host]))

(deftest untendered-host-still-spawns
  (let [tmp (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "itonami-host-grant-" (System/nanoTime)))
              (.mkdirs))
        echo (or (some #(when (.canExecute (io/file %)) %)
                       ["/bin/echo" "/usr/bin/echo"])
                 (throw (ex-info "no echo binary" {})))
        proc (host/process {"echo" echo})]
    (try
      (binding [host/*granted-capabilities* nil]
        (is (zero? (:exit (host/spawn! proc ["echo" "untendered"]
                                       :timeout-ms 5000
                                       :max-stdout-bytes 256)))))
      (finally
        (doseq [f (reverse (file-seq tmp))]
          (.delete ^java.io.File f))))))

(deftest grant-set-denies-missing-spawn
  (let [echo (or (some #(when (.canExecute (io/file %)) %)
                       ["/bin/echo" "/usr/bin/echo"])
                 (throw (ex-info "no echo binary" {})))
        proc (host/process {"echo" echo})]
    (binding [host/*granted-capabilities* #{:fs/write}]
      (is (thrown-with-msg? Exception #"process/spawn"
            (host/spawn! proc ["echo" "no"]
                         :timeout-ms 5000
                         :max-stdout-bytes 256))))))

(deftest grant-set-allows-spawn-with-process-spawn
  (let [echo (or (some #(when (.canExecute (io/file %)) %)
                       ["/bin/echo" "/usr/bin/echo"])
                 (throw (ex-info "no echo binary" {})))
        proc (host/process {"echo" echo})]
    (binding [host/*granted-capabilities* #{:process/spawn}]
      (let [{:keys [exit output]}
            (host/spawn! proc ["echo" "granted"]
                         :timeout-ms 5000
                         :max-stdout-bytes 256)]
        (is (zero? exit))
        (is (re-find #"granted" (str output)))))))

(deftest grant-set-denies-fs-write-without-cap
  (testing "filesystem-at"
    (binding [host/*granted-capabilities* #{:process/spawn}]
      (is (thrown-with-msg? Exception #"fs/write"
            (host/filesystem-at (System/getProperty "java.io.tmpdir")))))))
