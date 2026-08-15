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

(deftest every-window-launcher-opens-the-phone-shaped-surface
  (let [local (slurp "bin/cloud-itonami-app")
        macos (slurp "packaging/macos/CloudItonami")
        windows (slurp "packaging/windows/launcher/main.go")]
    (is (str/includes? local "--window-size=430,860"))
    (is (str/includes? macos "--width 430 --height 860 --min-width 360 --min-height 640"))
    (is (str/includes? windows "--window-size=430,860"))))

(deftest stable-desktop-release-is-fail-closed
  (let [process (ProcessBuilder.
                 ^java.util.List
                 ["bash" "scripts/build-desktop-release"])
        environment (.environment process)
        _ (.put environment "CLOUD_ITONAMI_SOURCE_COMMIT"
                "0000000000000000000000000000000000000000")
        _ (.put environment "CLOUD_ITONAMI_RELEASE_CHANNEL" "stable")
        started (.start process)
        stderr (slurp (.getErrorStream started))
        status (.waitFor started)]
    (is (not (zero? status)))
    (is (str/includes? stderr "Developer ID Application"))))

(deftest platform-updaters-pin-native-publisher-authority
  (let [macos (slurp "packaging/macos/ApplyUpdateMacos")
        windows (slurp "packaging/windows/ApplyUpdateWindows.ps1")
        signer (slurp "scripts/sign-windows-release")]
    (is (str/includes? macos "TeamIdentifier=3A5CBTEBFP"))
    (is (str/includes? macos "spctl --assess"))
    (is (str/includes? windows "Get-AuthenticodeSignature"))
    (is (str/includes? windows "windows-publisher-sha256.txt"))
    (is (str/includes? signer "602a51c3545a6dc4fb99bd2ea7152b26d1345916d0c93ddfbd5936cb735af91c"))
    (is (str/includes? signer "--storepass \"file:$password_file\""))))
