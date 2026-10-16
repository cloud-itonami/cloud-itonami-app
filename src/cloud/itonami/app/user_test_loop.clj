(ns cloud.itonami.app.user-test-loop
  "Resident supervisor that leases unresolved studies to synthetic participants."
  (:require [cloud.itonami.app.store :as store]
            [cloud.itonami.app.user-test :as user-test])
  (:import [java.util.concurrent Executors ScheduledExecutorService ThreadFactory
                                 TimeUnit]))

(defonce ^:private runtime (atom nil))

(defn- active-dispatch? [organization-id business-id]
  (some #(and (= business-id (:dispatch/business %))
              (contains? #{:queued :leased :running :held :checkpointed}
                         (:dispatch/status %)))
        (vals (get-in (store/snapshot)
                      [:user-tests organization-id :dispatches] {}))))

(defn- record-failure! [business-id error]
  (store/transact!
   update :events
   #(vec (take-last 200
                    (conj (or % [])
                          {:type :user-test/dispatch-failed
                           :at (store/now)
                           :business business-id
                           :error-type (some-> error ex-data :type)
                           :message (.getMessage error)})))))

(defn tick! [configuration]
  (when (user-test/execution-enabled? configuration)
    (doseq [[session business-id] (distinct (user-test/dispatchable-sessions))
            :when (not (active-dispatch? (:organization-id session) business-id))]
      (try
        (user-test/dispatch-synthetic! configuration session business-id)
        (catch Exception error
          (record-failure! business-id error))))))

(defn start! [configuration]
  (when (and (user-test/execution-enabled? configuration) (nil? @runtime))
    (let [interval (max 10 (long (or (get-in configuration
                                            [:user-test :dispatch-interval-seconds])
                                     60)))
          executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable
                                       "cloud-itonami-user-test-supervisor")
                          (.setDaemon true)))))]
      (.scheduleWithFixedDelay ^ScheduledExecutorService executor
                               ^Runnable #(tick! configuration)
                               3 interval TimeUnit/SECONDS)
      (reset! runtime executor)
      true)))

(defn stop! []
  (when-let [^ScheduledExecutorService executor @runtime]
    (.shutdownNow executor)
    (reset! runtime nil)
    true))

(defn running? [] (some? @runtime))
