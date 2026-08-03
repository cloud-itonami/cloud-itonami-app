(ns cloud.itonami.app.repository-profile-ci-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.repository-profile-ci :as profile-ci])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(def valid-profile
  "{:profile/id :kotoba/local-agent-kagi-chunks-v1
    :repo/kind :actor
    :query/location :local
    :query/api :datomic-datascript-subset
    :query/remote-capability? false
    :working-edn/editable? true
    :working-edn/private-git-policy :deny
    :mutation/membrane :reconcile
    :persistence/shape :append-only-transactions
    :remote/payload :kagi-chunked-edn
    :remote/head :kotobase
    :remote/transport :datalad}")

(defn- write! [file text]
  (.mkdirs (.getParentFile file))
  (Files/write (.toPath file) (.getBytes text StandardCharsets/UTF_8)
               (make-array java.nio.file.OpenOption 0)))

(deftest inventory-drives-local-and-remote-profile-audit
  (let [root (.toFile (Files/createTempDirectory
                       "repository-profile-ci-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        config (java.io.File. root "app/config")
        inventory (java.io.File. config "inventory.edn")]
    (write! (java.io.File. root "app/storage-profile.edn") valid-profile)
    (write! inventory
            (pr-str [{:repository "cloud-itonami/cloud-itonami-app"
                      :path ".."}
                     {:repository "example/actor" :path "../../actor"}]))
    (binding [profile-ci/*fetch-profile*
              (fn [_ _ repository]
                (is (= "example/actor" repository))
                valid-profile)]
      (let [result (profile-ci/audit! (.getPath inventory) nil)]
        (is (:qualified? result) (pr-str result))
        (is (= 2 (:inventory-count result)))
        (is (empty? (:failed result)))))))
