(ns cloud.itonami.app.repository-actor-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.repository-actor :as actor]
            [cloud.itonami.app.repository-storage :as repository]
            [langchain.edn-persist :as edn-persist]))

(def owner "owner-actor-test")

(defn- fixture []
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "repository-actor-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        owner-dir (io/file root owner)
        state-file (io/file owner-dir "state.edn")]
    (.mkdirs owner-dir)
    (spit state-file (pr-str {:datoms [] :agent/note "editable"}))
    {:workspace-root (.getPath root) :owner owner :state-file state-file}))

(deftest launcher-injects-one-user-state-and-actor-stream
  (let [{:keys [state-file] :as context} (fixture)
        seen (atom nil)
        result
        (actor/launch!
         (assoc context
                :actor-id :animeka
                :command ["ignored"] :working-directory "."
                :process-fn
                (fn [{:keys [environment]}]
                  (reset! seen environment)
                  (let [persist (edn-persist/configured-persist
                                 environment "wrong-default")]
                    ((:append persist) {:tx 1 :tx-data []}))
                  {:exit 0 :output-bytes 0})))]
    (is (= (.getCanonicalPath state-file)
           (get @seen "KOTOBA_REPOSITORY_STATE_FILE")))
    (is (= "actor/animeka" (get @seen "KOTOBA_REPOSITORY_STREAM")))
    (is (:state/changed? result))
    (is (= "editable"
           (:agent/note (:state (repository/workspace-snapshot
                                 (:workspace-root context) owner)))))))

(deftest launcher-fails-closed-on-unknown-owner-actor-and-process-failure
  (let [context (fixture)]
    (testing "path traversal cannot select another user's workspace"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"owner storage id is invalid"
                            (actor/launch-environment
                             (assoc context :owner "../other"
                                    :actor-id :animeka)))))
    (testing "only registered streams can be launched"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"actor is not registered"
                            (actor/launch-environment
                             (assoc context :actor-id :unknown)))))
    (testing "private actor output is absent from failure data"
      (let [error (try
                    (actor/launch!
                     (assoc context :actor-id :swachh :command ["ignored"]
                            :working-directory "."
                            :process-fn (fn [_]
                                          {:exit 9
                                           :output "private plaintext"})))
                    nil
                    (catch clojure.lang.ExceptionInfo value value))]
        (is (= :repository-actor/process-failed (:type (ex-data error))))
        (is (not (.contains (pr-str (ex-data error)) "private plaintext")))))))
