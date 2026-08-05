(ns cloud.itonami.app.work-reconciler
  "Supervised wake-up loop for the finite durable work reconciler."
  (:require [clojure.java.io :as io]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.work-runtime :as runtime])
  (:import [java.nio.channels FileChannel FileLock OverlappingFileLockException]
           [java.nio.file StandardOpenOption]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory
            TimeUnit]))

(defonce ^:private scheduler (atom nil))
(defonce ^:private leadership (atom nil))
(defonce ^:private runtime-config (atom nil))

(defn running? [] (boolean @scheduler))

(defn leader? [] (boolean @leadership))

(defn- mark-supervisor! [status]
  (store/transact! assoc-in [:work-governance :runtime :supervisor]
                   {:status status :at (System/currentTimeMillis)}))

(defn- acquire-leadership! []
  (let [file (io/file (config/data-dir) "work-reconciler.lock")]
    (.mkdirs (.getParentFile file))
    (let [channel (FileChannel/open
                   (.toPath file)
                   (into-array StandardOpenOption
                               [StandardOpenOption/CREATE
                                StandardOpenOption/WRITE]))
          lock (try (.tryLock channel)
                    (catch OverlappingFileLockException _ nil))]
      (if lock
        {:channel channel :lock lock :file (.getPath file)}
        (do (.close channel) nil)))))

(defn- record-failure! [error]
  (store/transact!
   (fn [s]
     (-> s
         (update-in [:work-governance :runtime :failures] (fnil inc 0))
         (assoc-in [:work-governance :runtime :last-error]
                   {:at (System/currentTimeMillis)
                    :type (or (:type (ex-data error)) :reconcile/error)
                    :message (.getMessage error)})))))

(defn start! [configuration]
  (when (and (get-in configuration [:work-governance :enabled?])
             (nil? @scheduler))
    (if-let [leader (acquire-leadership!)]
      (let [interval (long (or (get-in configuration
                                      [:work-governance :interval-seconds]) 15))
          executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "cloud-itonami-work-reconciler")
                          (.setDaemon true)))))]
        (reset! leadership leader)
        (reset! runtime-config configuration)
        (.scheduleWithFixedDelay
         ^ScheduledExecutorService executor
         ^Runnable #(try
                      (runtime/reconcile-once! configuration)
                      (catch Exception error (record-failure! error)))
         0 (max 1 interval) TimeUnit/SECONDS)
        (reset! scheduler executor)
        (mark-supervisor! :leader))
      (mark-supervisor! :standby)))
  (running?))

(defn wake!
  "Wake the elected reconciler after a source event without waiting for the
  fixed-delay tick."
  []
  (when-let [^ScheduledExecutorService executor @scheduler]
    (.submit executor
             ^Runnable #(try
                          (runtime/reconcile-once! @runtime-config)
                          (catch Exception error (record-failure! error))))
    true))

(defn stop! []
  (when-let [^ScheduledExecutorService executor @scheduler]
    (.shutdownNow executor)
    (reset! scheduler nil))
  (when-let [{:keys [^FileLock lock ^FileChannel channel]} @leadership]
    (.release lock)
    (.close channel)
    (reset! leadership nil))
  (reset! runtime-config nil)
  true)
