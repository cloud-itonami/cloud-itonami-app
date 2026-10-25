(ns cloud.itonami.app.executor
  "Executor selection that compiles on the CI baseline and uses virtual
  threads when the running JDK provides them."
  (:import [java.lang ReflectiveOperationException]
           [java.util.concurrent ExecutorService Executors]))

(defn task-executor
  "Return a virtual-thread-per-task executor on Java 21+, otherwise use an
  unbounded cached pool. Admission control remains the worker's responsibility."
  ^ExecutorService []
  (try
    (let [method (.getMethod Executors
                             "newVirtualThreadPerTaskExecutor"
                             (make-array Class 0))]
      (.invoke method nil (object-array 0)))
    (catch ReflectiveOperationException _
      (Executors/newCachedThreadPool))))
