(ns cloud.itonami.app.organism-messenger-transport-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.organism-gateway :as gateway]
            [cloud.itonami.app.organism-messenger-transport :as transport]
            [cloud.itonami.app.organism-worker :as organism-worker]
            [cloud.itonami.app.store :as store]))

(def temporary (atom nil))

(use-fixtures
  :each
  (fn [test-fn]
    (let [before @store/state
          directory (.toFile
                     (java.nio.file.Files/createTempDirectory
                      "cloud-itonami-ao-messenger"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
          organisms (io/file directory "organisms")]
      (.mkdirs organisms)
      (spit (io/file organisms "reviewer.edn")
            (pr-str {:ao.worker/schema organism-worker/schema
                     :ao.worker/id "ao:acme:reviewer"
                     :ao.worker/kind :artificial-organism
                     :ao.worker/organization "acme"
                     :ao.worker/subject "did:key:reviewer"
                     :ao.worker/repository "rad:reviewer"
                     :ao.worker/runtime :external-supervisor
                     :ao.worker/status :active
                     :ao.worker/capabilities #{:activity/read :intent/submit}
                     :ao.worker/authority {:memory :organism-local
                                           :lifecycle :organism-local
                                           :source :repository-local
                                           :issue :radicle-first}}))
      (reset! temporary directory)
      (try
        (reset! store/state (store/initial-state))
        (with-redefs [config/data-dir (constantly directory)
                      gateway/tamaki-root (constantly directory)]
          (test-fn))
        (finally (reset! store/state before))))))

(deftest credential-is-file-delivered-hash-stored-and-worker-bound
  (let [issued (transport/issue! "ao:acme:reviewer" "acme")
        credential (transport/read-credential "ao:acme:reviewer")
        token (:token credential)
        record (get-in (store/snapshot)
                       [:organism-messenger-transports "ao:acme:reviewer"])]
    (is (.isFile (io/file (:credential-file issued))))
    (is (string? token))
    (is (nil? (:token record)) "clear token is not in app state")
    (is (= "organism:ao:acme:reviewer"
           (:principal (transport/authenticate token))))
    (is (nil? (transport/authenticate (str token "wrong"))))
    (transport/issue! "ao:acme:reviewer" "acme")
    (is (nil? (transport/authenticate token)) "rotation invalidates the old token")))

(deftest organization-boundary-fails-closed
  (is (= :ao.worker/not-found
         (:type (ex-data
                 (try (transport/issue! "ao:acme:reviewer" "other")
                      nil
                      (catch clojure.lang.ExceptionInfo error error)))))))
