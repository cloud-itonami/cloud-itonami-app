(ns cloud.itonami.app.launcher-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- temporary-directory []
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "cloud-itonami-launcher-test"
    (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest resident-clone-resolves-shell-from-workspace-root
  (let [root (temporary-directory)
        shell (io/file root "orgs" "kotoba-lang" "shell")
        _ (.mkdirs shell)
        process (ProcessBuilder.
                 ^java.util.List
                 ["bash" "bin/cloud-itonami-app" "--print-shell-dir"])
        _ (.put (.environment process) "CLOUD_ITONAMI_WORKSPACE_ROOT"
                (.getCanonicalPath root))
        started (.start process)
        stdout (slurp (.getInputStream started))
        stderr (slurp (.getErrorStream started))
        status (.waitFor started)]
    (is (zero? status) stderr)
    (is (= (.getCanonicalPath shell) (str/trim stdout)))))

(deftest packaged-launcher-does-not-request-unneeded-computer-use-permissions
  (let [launcher (slurp "packaging/macos/CloudItonami")]
    (is (not (str/includes? launcher "--permissions")))
    (is (not (str/includes? launcher "accessibility,screen-recording")))))
