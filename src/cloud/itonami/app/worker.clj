(ns cloud.itonami.app.worker
  "Background worker runs: prompts executed off the request thread.

  A run is queued, admitted by a fair semaphore so the local machine keeps a
  bounded number of concurrent model calls, then streamed to completion. The
  provider is still chosen by `service`, so the local-first fail-closed policy
  applies to worker runs exactly as it does to interactive chat.

  Runs are held in memory only. `store/transact!` rewrites the whole persisted
  state file on every change, so streaming deltas through it would rewrite
  `state.edn` once per token; the durable store keeps a bounded completion
  event per run instead. Runs therefore do not survive a restart."
  (:require [clojure.string :as str]
            [cloud.itonami.app.executor :as executor]
            [cloud.itonami.app.service :as service]
            [cloud.itonami.app.store :as store])
  (:import [java.time Instant]
           [java.util.concurrent ExecutorService Semaphore]))

(def schema "cloud.itonami.app.worker.v1")
(def default-max-concurrency 2)
(def default-max-runs 50)
(def max-output-characters 16000)
(def max-title-characters 80)
(def max-prompt-characters 8000)
(def terminal-statuses #{:done :failed :cancelled})

(defonce runs (atom []))
(defonce ^:private cancelled (atom #{}))
(defonce ^:private permits (atom nil))
(defonce ^:private executor
  (delay (executor/task-executor)))

(defn- now [] (str (Instant/now)))

(defn- clamp [value limit]
  (let [value (str value)]
    (if (<= (count value) limit) value (subs value 0 limit))))

(defn- max-concurrency [config]
  (max 1 (int (get-in config [:worker :max-concurrency] default-max-concurrency))))

(defn- retention-limit [config]
  (max 1 (int (get-in config [:worker :max-runs] default-max-runs))))

(defn- admission
  "The shared admission gate, sized once from configuration and kept for the
  process lifetime. Fair, so the queue the workspace shows is the order runs
  actually start in."
  [config]
  (or @permits
      (swap! permits
             (fn [current]
               (or current
                   (let [size (max-concurrency config)]
                     {:semaphore (Semaphore. size true) :size size}))))))

(defn- concurrency-in-effect [config]
  (or (:size @permits) (max-concurrency config)))

(defn- derive-title [prompt]
  (-> (or (first (str/split-lines prompt)) prompt)
      str/trim
      (clamp 48)))

(defn- find-run [id]
  (some #(when (= id (:id %)) %) @runs))

(defn- update-run! [id f]
  (swap! runs (fn [current] (mapv #(if (= id (:id %)) (f %) %) current)))
  nil)

(defn- retain [current limit]
  (let [finished (filterv (comp terminal-statuses :status) current)
        dropped (set (map :id (drop-last limit finished)))]
    (if (seq dropped)
      (filterv #(not (dropped (:id %))) current)
      current)))

(defn- append-output! [id chunk]
  (update-run!
   id
   (fn [run]
     (if (:truncated? run)
       run
       (let [combined (str (:output run) chunk)]
         (if (<= (count combined) max-output-characters)
           (assoc run :output combined)
           (assoc run
                  :output (subs combined 0 max-output-characters)
                  :truncated? true)))))))

(defn- record-event! [run]
  (store/transact!
   (fn [state]
     (update state :events
             #(vec (take-last 100
                              (conj (or % [])
                                    {:type :worker/finished
                                     :at (now)
                                     :run-id (:id run)
                                     :title (:title run)
                                     :status (:status run)})))))))

(defn- finish! [config id status extra]
  ;; Before the status becomes terminal, not after. `await-idle!` returns as
  ;; soon as every run has a terminal status, so anything torn down afterwards
  ;; is observable by a caller that has already been told the worker is idle —
  ;; the per-run session was cleared in a `finally` below, and a test asserting
  ;; it was gone failed intermittently in exactly that window (CI, 2026-07-30).
  ;; The `finally` still clears, for the paths that never reach here.
  (store/clear-session! (str "worker:" id))
  (when-let [current (find-run id)]
    (let [finished (-> (merge current extra)
                       (assoc :status status :finished-at (now)))]
      ;; Persist every observable consequence before publishing the terminal
      ;; in-memory status. Cross-process state locking makes this write
      ;; intentionally slower, so the old reverse order exposed a real race:
      ;; await-idle! returned while the finished event was still absent.
      (record-event! finished)
      (update-run! id (constantly finished))
      (swap! runs retain (retention-limit config)))))

(defn- public-run [run]
  {:id (:id run)
   :title (:title run)
   :prompt (:prompt run)
   :agent (:agent-id run)
   :model (:model run)
   :provider (:provider run)
   :status (name (:status run))
   :output (:output run)
   :truncated? (boolean (:truncated? run))
   :error (:error run)
   :usage (:usage run)
   :created-at (:created-at run)
   :started-at (:started-at run)
   :finished-at (:finished-at run)})

(defn- run-job! [config id]
  (let [^Semaphore permit (:semaphore (admission config))
        acquired? (try (.acquire permit) true
                       (catch InterruptedException _ false))
        session-id (str "worker:" id)]
    (try
      (cond
        (not acquired?)
        (finish! config id :cancelled
                 {:error "worker run was interrupted before it started."})

        (contains? @cancelled id)
        (finish! config id :cancelled nil)

        :else
        (do
          (update-run! id #(assoc % :status :running :started-at (now)))
          (try
            (let [run (find-run id)
                  response (service/run-chat-stream!
                            config
                            {:messages [{:role "user" :content (:prompt run)}]
                             :model (:model run)
                             :session-id session-id
                             :agent-id (:agent-id run)}
                            (fn [delta]
                              (when (contains? @cancelled id)
                                (throw (ex-info "worker run cancelled"
                                                {:type :worker/cancelled})))
                              (append-output! id delta)))
                  streamed (:output (find-run id))
                  content (get-in response [:message :content])]
              (finish! config id :done
                       (cond-> {:provider (:provider response)
                                :model (:model response)
                                :usage (:usage response)}
                         (str/blank? streamed)
                         (assoc :output (clamp content max-output-characters)))))
            (catch Exception error
              (if (contains? @cancelled id)
                (finish! config id :cancelled nil)
                (finish! config id :failed
                         {:error (or (not-empty (str (.getMessage error)))
                                     (.getName (class error)))}))))))
      (finally
        ;; The run record carries the prompt and output, so the per-run chat
        ;; session would only grow `state.edn` without being read again.
        (store/clear-session! session-id)
        (swap! cancelled disj id)
        (when acquired? (.release permit))))))

(defn enqueue!
  "Register a background run and return its public view."
  [config {:keys [title prompt model agent]}]
  (when (str/blank? prompt)
    (throw (ex-info "worker には実行する指示が必要です。"
                    {:type :worker/invalid-request})))
  (let [id (store/new-id "wrk")
        prompt (clamp (str/trim prompt) max-prompt-characters)
        run {:id id
             :title (or (not-empty (clamp (str/trim (or title "")) max-title-characters))
                        (derive-title prompt))
             :prompt prompt
             :agent-id (or (not-empty (str/trim (str (or agent "")))) "local")
             :model (not-empty (str/trim (str (or model ""))))
             :status :queued
             :output ""
             :truncated? false
             :error nil
             :provider nil
             :usage nil
             :created-at (now)
             :started-at nil
             :finished-at nil}]
    (swap! runs conj run)
    (.execute ^ExecutorService @executor ^Runnable #(run-job! config id))
    (public-run run)))

(defn cancel!
  "Ask a queued or running worker run to stop."
  [id]
  (let [run (or (find-run id)
                (throw (ex-info "worker run が見つかりません。"
                                {:type :worker/not-found :id id})))]
    (when (terminal-statuses (:status run))
      (throw (ex-info "この worker run は既に終了しています。"
                      {:type :worker/not-cancellable
                       :id id :status (name (:status run))})))
    (swap! cancelled conj id)
    (when (= :queued (:status run))
      (update-run! id #(assoc % :status :cancelled :finished-at (now))))
    nil))

(defn clear-finished! []
  (swap! runs (fn [current]
                (filterv #(not (terminal-statuses (:status %))) current)))
  nil)

(defn snapshot [config]
  (let [current (vec (reverse @runs))
        counts (frequencies (map :status current))]
    {:schema schema
     :source "cloud-itonami-app / worker"
     :mode "in-memory"
     :max-concurrency (concurrency-in-effect config)
     :max-runs (retention-limit config)
     :counts {:queued (get counts :queued 0)
              :running (get counts :running 0)
              :done (get counts :done 0)
              :failed (get counts :failed 0)
              :cancelled (get counts :cancelled 0)}
     :active (+ (get counts :queued 0) (get counts :running 0))
     :items (mapv public-run current)}))

(defn await-idle!
  "Block until no run is queued or running, or `timeout-ms` elapses.
  Returns true when the worker became idle. Supports tests and shutdown."
  [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (cond
        (every? (comp terminal-statuses :status) @runs) true
        (< deadline (System/currentTimeMillis)) false
        :else (do (Thread/sleep 10) (recur))))))
