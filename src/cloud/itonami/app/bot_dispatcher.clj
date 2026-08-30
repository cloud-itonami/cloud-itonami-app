(ns cloud.itonami.app.bot-dispatcher
  "The one admission boundary for every Bot model request.

  `murakumo-main` publishes two serving slots. A JVM semaphore alone is not a
  bound when the resident app, CLI and MCP can be separate processes, so each
  admitted request also owns one of two OS file locks under the shared data
  directory. File locks are released by the OS when a process dies."
  (:require [clojure.java.io :as io]
            [cloud.itonami.app.config :as config])
  (:import [java.nio.channels FileChannel FileLock
            OverlappingFileLockException]
           [java.nio.file OpenOption StandardOpenOption]
           [java.util.concurrent Semaphore]))

(def max-parallel 2)

(defonce ^:private local-admission (Semaphore. max-parallel true))
(defonce ^:private waiting (atom 0))
(defonce ^:private active (atom 0))
(defonce ^:private next-slot (atom -1))

(def ^:dynamic *slot-directory*
  "Test seam. Production uses the canonical Cloud Itonami data directory."
  nil)

(defn- slot-directory []
  (or *slot-directory* (config/data-dir)))

(defn- slot-file [slot]
  (io/file (slot-directory) (str ".bot-provider-slot-" slot ".lock")))

(defn- try-slot [slot]
  (let [file (slot-file slot)
        _ (.mkdirs (.getParentFile file))
        channel (FileChannel/open
                 (.toPath file)
                 (into-array OpenOption [StandardOpenOption/CREATE
                                         StandardOpenOption/WRITE]))]
    (try
      (if-let [lock (try (.tryLock channel)
                         (catch OverlappingFileLockException _ nil))]
        {:slot slot :channel channel :lock lock}
        (do (.close channel) nil))
      (catch Throwable error
        (.close channel)
        (throw error)))))

(defn- acquire-slot! []
  (loop [start (mod (swap! next-slot inc) max-parallel)]
    (if-let [slot (some try-slot
                        (map #(mod (+ start %) max-parallel)
                             (range max-parallel)))]
      slot
      (do
        (Thread/sleep 20)
        (recur (mod (inc start) max-parallel))))))

(defn- release-slot! [{:keys [^FileLock lock ^FileChannel channel]}]
  (try
    (when (and lock (.isValid lock)) (.release lock))
    (finally
      (when (and channel (.isOpen channel)) (.close channel)))))

(defn snapshot []
  {:schema "cloud.itonami.app.bot-dispatcher.v1"
   :max-parallel max-parallel
   :waiting @waiting
   :active @active})

(defn dispatch!
  "Run F while owning one of the two Bot provider slots.

  Queue time is deliberately outside the provider timeout: waiting for known
  local capacity is not evidence that Murakumo timed out. Interruption remains
  interruption and never starts the provider request."
  [f]
  (swap! waiting inc)
  (let [local? (atom false)
        slot (atom nil)]
    (try
      (.acquire local-admission)
      (reset! local? true)
      (reset! slot (acquire-slot!))
      (swap! waiting dec)
      (swap! active inc)
      (try
        (f)
        (finally (swap! active dec)))
      (catch InterruptedException error
        (.interrupt (Thread/currentThread))
        (throw (ex-info "Bot provider dispatch was interrupted before admission"
                        {:type :bot/dispatcher-interrupted}
                        error)))
      (finally
        ;; A failure before admission still belongs to the waiting count.
        (when-not @slot (swap! waiting dec))
        (try
          (when @slot (release-slot! @slot))
          (finally
            ;; Never leak the local permit because closing an OS lock failed.
            (when @local? (.release local-admission))))))))
