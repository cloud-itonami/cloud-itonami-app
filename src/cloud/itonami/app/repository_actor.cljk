(ns cloud.itonami.app.repository-actor
  "Launch repository-driven actors against the authenticated user's one local
  plaintext projection. The actor receives coordinates, never Kagi VMKs,
  DataLad remotes, or Kotobase credentials."
  (:require [clojure.java.io :as io]
            [cloud.itonami.app.repository-storage :as repository])
  (:import [java.util.concurrent TimeUnit]))

(def actor-streams
  {:animeka "actor/animeka"
   :dougaka "actor/dougaka"
   :shinshi-growth "actor/shinshi-growth"
   :swachh "actor/swachh"
   :organisms "actor/organisms"
   :etzhayyim-kaiyaku "actor/etzhayyim/kaiyaku"})

(defn launch-environment
  "Resolve the exact state file and stable stream for ACTOR-ID. The workspace
  must already have been initialized or hydrated."
  [{:keys [workspace-root owner actor-id]}]
  (let [stream (get actor-streams actor-id)
        _ (when-not stream
            (throw (ex-info "repository actor is not registered"
                            {:type :repository-actor/unknown-actor
                             :actor-id actor-id})))
        file (repository/workspace-state-file workspace-root owner)]
    (when-not (.isFile file)
      (throw (ex-info "repository actor workspace is not initialized"
                      {:type :repository-actor/workspace-missing
                       :actor-id actor-id})))
    {"KOTOBA_REPOSITORY_STATE_FILE" (.getPath (.getCanonicalFile file))
     "KOTOBA_REPOSITORY_STREAM" stream}))

(defn- drain! [input]
  (with-open [input input]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (let [read (.read input buffer)]
          (if (neg? read) total (recur (+ total read))))))))

(defn run-process!
  "Default no-shell process host. Output is drained but deliberately not
  returned or logged because an actor may print private projection values."
  [{:keys [command working-directory environment timeout-seconds]
    :or {timeout-seconds 600}}]
  (when-not (and (vector? command) (seq command)
                 (every? #(and (string? %) (seq %)) command))
    (throw (ex-info "repository actor command must be a non-empty argv vector"
                    {:type :repository-actor/invalid-command})))
  (let [directory (.getCanonicalFile (io/file working-directory))
        _ (when-not (.isDirectory directory)
            (throw (ex-info "repository actor working directory is missing"
                            {:type :repository-actor/working-directory-missing})))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory directory)
                  (.redirectErrorStream true))
        process-environment (.environment builder)
        _ (doseq [[key value] environment]
            (.put process-environment key value))
        process (.start builder)
        output (future (drain! (.getInputStream process)))
        completed? (.waitFor process (long timeout-seconds) TimeUnit/SECONDS)]
    (when-not completed?
      (.destroyForcibly process)
      (deref output 1000 0)
      (throw (ex-info "repository actor timed out"
                      {:type :repository-actor/timeout})))
    {:exit (.exitValue process)
     :output-bytes (deref output 1000 0)}))

(defn launch!
  "Run one registered actor with user-scoped repository coordinates.

  PROCESS-FN is an injectable host for tests/other runtimes. A non-zero exit
  fails without copying actor output into logs. On success the resulting EDN
  is parsed and validated before the caller sees completion."
  [{:keys [workspace-root owner actor-id command working-directory
           timeout-seconds process-fn]
    :or {process-fn run-process!}
    :as request}]
  (let [environment (launch-environment request)
        before (repository/workspace-snapshot workspace-root owner)
        before-cid (repository/semantic-cid (:state before))
        result (process-fn {:command command
                            :working-directory working-directory
                            :environment environment
                            :timeout-seconds timeout-seconds})]
    (when-not (zero? (:exit result))
      (throw (ex-info "repository actor failed"
                      {:type :repository-actor/process-failed
                       :actor-id actor-id :exit (:exit result)})))
    (let [after (repository/workspace-snapshot workspace-root owner)
          after-cid (repository/semantic-cid (:state after))]
      {:actor/id actor-id
       :state/before-cid before-cid
       :state/after-cid after-cid
       :state/changed? (not= before-cid after-cid)
       :output/bytes (:output-bytes result)})))
