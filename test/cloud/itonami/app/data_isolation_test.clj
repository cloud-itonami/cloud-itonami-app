(ns cloud.itonami.app.data-isolation-test
  "The suite must not be able to write the real store.

  This test exists because the suite DID. On 2026-07-30, `clojure -M:test` run
  from the repository root persisted through `cloud.itonami.app.store`, whose
  data directory defaulted to `./data` -- the developer's real one. A fixture in
  `payment_tools_test` replaces the entire `:identity` partition, so it
  overwrote a real provisional user with `{:id \"user-1\" :display-name \"Owner\"}`:
  no `:email`, no `:user-handle`. Passkey registration then failed with a
  NullPointerException from inside the WebAuthn `UserIdentity` builder, which
  reads as a bug in the registration code and is not one.

  Eighteen `store/transact!` calls across the suite write through that function
  and two of them replace `:identity` wholesale, so scoping each caller would
  have been a list to keep in step. Redirecting the directory once is the thing
  that cannot be forgotten."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]))

(deftest the-suite-writes-somewhere-disposable
  (let [dir (str (config/data-dir))]
    (testing "not the repository's own data/ -- that is a real store"
      (is (not (str/ends-with? dir "/cloud-itonami-app/data"))
          (str "the suite is pointed at " dir
               ", which looks like a real store. Check the :test alias's"
               " -Dcloud.itonami.data-dir.")))
    (testing "and the path says so, so a stray file is recognisable"
      (is (str/includes? dir "test-data")
          (str "expected a disposable directory, got " dir)))))

(deftest the-property-beats-the-environment-variable
  (testing "an exported CLOUD_ITONAMI_DATA_DIR must not be able to redirect the
            suite back onto a real store -- the :test alias's property is the
            more specific signal and has to win"
    (is (= (str (config/data-dir))
           (str (.getCanonicalFile
                 (io/file
                  (System/getProperty "cloud.itonami.data-dir")))))
        "when the property is set, it decides, whatever the environment says")))

(deftest the-store-file-lives-under-that-directory
  (testing "the check above is only worth anything if this is what store/ uses"
    (is (str/starts-with? (str (store/state-file)) (str (config/data-dir))))))
