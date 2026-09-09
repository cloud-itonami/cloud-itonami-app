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
      ;; `audit!` returns a promise now. Deref with a BOUND wait rather than
      ;; `@`: an async call site that never resolves would otherwise hang the
      ;; suite instead of failing it, and a suite that hangs reports nothing at
      ;; all. The stub above still returns a plain string -- promesa's `p/let`
      ;; takes a value or a promise, so moving the call site did not force
      ;; every test double to become asynchronous too.
      (let [result (deref (profile-ci/audit! (.getPath inventory) nil)
                          10000 ::timed-out)]
        (is (not= ::timed-out result) "audit! resolved")
        (is (:qualified? result) (pr-str result))
        (is (= 2 (:inventory-count result)))
        (is (empty? (:failed result)))))))

(deftest a-repository-that-cannot-be-read-is-reported-not-thrown
  ;; The catch had to move from `try` to `p/catch` when the fetch became
  ;; asynchronous, and a rejection arriving after its `try` form returned is
  ;; exactly the failure that move exists to prevent: one unreachable
  ;; repository taking the whole audit down instead of being listed as
  ;; unreadable. Asserted on the REASON, not just on "it did not throw".
  (let [root (.toFile (Files/createTempDirectory
                       "repository-profile-ci-fail-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        config (java.io.File. root "app/config")
        inventory (java.io.File. config "inventory.edn")]
    (write! (java.io.File. root "app/storage-profile.edn") valid-profile)
    (write! inventory
            (pr-str [{:repository "cloud-itonami/cloud-itonami-app" :path ".."}
                     {:repository "example/actor" :path "../../actor"}]))
    (binding [profile-ci/*fetch-profile*
              (fn [_ _ _]
                (throw (ex-info "GitHub repository profile request failed"
                                {:type :repository-storage/profile-fetch-failed
                                 :status 403})))]
      (let [result (deref (profile-ci/audit! (.getPath inventory) nil)
                          10000 ::timed-out)]
        (is (not= ::timed-out result) "a rejected fetch still resolves the audit")
        (is (= 2 (:inventory-count result))
            "the unreadable repository is still counted, not dropped")
        (is (seq (:failed result)) "and it is reported as failed")))))
