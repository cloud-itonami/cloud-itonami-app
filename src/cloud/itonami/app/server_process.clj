(ns cloud.itonami.app.server-process
  "Starting, finding and stopping the app's server without the desktop shell.

  ## Why the CLI starts a server rather than doing the work itself

  `store/state` is `(defonce state (atom (load-state)))` — read once when a
  process starts. Two processes holding one data directory therefore each act on
  a snapshot frozen at their own start, and each drops what the other wrote, in
  both directions, silently. That is why `cli` and `mcp` are HTTP clients of the
  process that owns the store (see `app-client`).

  The consequence was that every command needed the app already running, and
  `bin/cloud-itonami-app` starts a server AND a native window. So an operator who
  wanted to read their inbox from a terminal had to open a desktop app, and an
  agent could do nothing on a machine where nobody had.

  This closes that without weakening the rule: the server is what owns the store,
  so the CLI makes sure one is running — headless, no window, no shell runtime —
  and then talks to it exactly as before. One writer, still.

  ## Why a lock file and not just 'probe, then spawn'

  Probe-then-spawn is a race with a window as wide as JVM startup, and the
  failure it produces is the one this namespace exists to prevent: two servers,
  one data directory. `CREATE_NEW` is atomic, so exactly one caller wins and the
  rest wait for the health check the winner is racing toward.

  A lock older than the startup budget is a crash, not a competitor, and is
  cleared. A pid file is written only after the server answers `/health`, so its
  presence means 'this was serving', never 'this was attempted'."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.app-client :as client]
            [cloud.itonami.app.config :as config])
  (:import [java.io File]
           ;; Neither is in Clojure's default java.lang imports: `ProcessHandle`
           ;; arrived in Java 9, and `Redirect` is nested.
           [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration Instant]
           [java.util.concurrent TimeUnit]))

(def schema "cloud.itonami.app.server-process.v1")

(def ^:private startup-budget
  "How long to wait for a cold server before reporting rather than blocking.

  Measured on this app, 2026-08-05: a first `clojure -M:server` — classpath
  resolution, then loading ninety namespaces and their git dependencies — takes a
  little over a minute before `/health` answers, and longer when the classpath
  cache is cold. A budget under that turns a normal first run into a failure."
  (Duration/ofSeconds 300))

(defonce ^:private probe-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofMillis 700))
      .build))

(defn- data-file ^File [name]
  (io/file (config/data-dir) name))

(defn- base-url [configuration]
  (client/base-url configuration))

(defn remote?
  "Whether this process was pointed at a hosted control plane.

  Nothing here starts, stops or reports on one: the local lifecycle is about the
  process that owns THIS data directory, and a remote plane has neither."
  []
  (boolean (client/remote-api-url)))

(defn healthy?
  "Whether something is serving this app at the configured bind.

  `/health` and not `/api/state`: it is the one route that takes no session, so a
  false answer means 'nothing is listening' rather than 'you are not logged in'."
  [configuration]
  (try
    (let [request (-> (HttpRequest/newBuilder
                       (URI/create (str (base-url configuration) "/health")))
                      (.timeout (Duration/ofSeconds 2))
                      .GET .build)
          response (.send probe-client request
                          (HttpResponse$BodyHandlers/ofString))]
      (and (= 200 (.statusCode response))
           (str/includes? (.body response) "cloud-itonami-app")))
    (catch Exception _ false)))

(defn app-directory
  "Where to run `clojure -M:server` from.

  The current directory when it holds this app's `deps.edn`, otherwise
  `CLOUD_ITONAMI_APP_DIR`. Refusing rather than guessing: a wrong answer starts a
  DIFFERENT install against this data directory, which is the two-writer failure
  in a form that looks like it worked."
  []
  (let [candidates (keep identity
                         [(some-> (System/getenv "CLOUD_ITONAMI_APP_DIR")
                                  str/trim not-empty io/file)
                          (io/file (System/getProperty "user.dir"))])]
    (or (first (filter #(.isFile (io/file % "deps.edn")) candidates))
        (throw (ex-info
                (str "cloud-itonami-app のディレクトリが見つかりません。"
                     "app のディレクトリで実行するか CLOUD_ITONAMI_APP_DIR を設定してください")
                {:type :server-process/no-app-directory})))))

(defn- read-pid []
  (let [file (data-file "server.pid")]
    (when (.isFile file)
      (some-> (slurp file) str/trim not-empty parse-long))))

(defn- alive? [pid]
  (boolean (and pid (some-> (ProcessHandle/of (long pid))
                            (.orElse nil)
                            .isAlive))))

(defn- write-pid! [pid]
  (spit (data-file "server.pid") (str pid "\n")))

(defn- clear-pid! []
  (.delete (data-file "server.pid")))

(defn- lock-path ^Path []
  (.toPath (data-file "server.starting.lock")))

(defn- try-create-lock
  "`:claimed`, `:held` by a live start, or `:stale` from a crashed one."
  []
  (let [path (lock-path)]
    (try
      (Files/createFile path (into-array FileAttribute []))
      :claimed
      (catch java.nio.file.FileAlreadyExistsException _
        (let [modified (try (.toMillis (Files/getLastModifiedTime
                                        path (into-array LinkOption [])))
                            (catch Exception _ 0))]
          (if (> (- (System/currentTimeMillis) modified)
                 (.toMillis startup-budget))
            :stale
            :held))))))

(defn- claim-lock!
  "True when this process is the one that should spawn.

  A lock left by a crashed start is cleared once the startup budget has passed;
  a fresh one means somebody else is already starting and this caller should wait
  for them rather than add a second server. One retry after clearing a stale
  lock, so two processes finding the same corpse cannot loop against each other."
  []
  (loop [attempts 2]
    (case (try-create-lock)
      :claimed true
      :held false
      :stale (if (pos? attempts)
               (do (try (Files/deleteIfExists (lock-path))
                        (catch Exception _ nil))
                   (recur (dec attempts)))
               false))))

(defn- release-lock! []
  (try (Files/deleteIfExists (lock-path)) (catch Exception _ nil)))

(defn- await-health
  "Poll until the server answers or the budget runs out."
  [configuration ^Duration budget]
  (let [deadline (.plus (Instant/now) budget)]
    (loop []
      (cond
        (healthy? configuration) true
        (.isAfter (Instant/now) deadline) false
        :else (do (Thread/sleep 250) (recur))))))

(defn- spawn!
  "A detached headless server, its output in the data directory.

  `-M:server` and not `bin/cloud-itonami-app`: that script also starts the native
  shell, which is the window this exists to avoid. The child outlives this
  process, which is the point — the next command finds it already up."
  [configuration]
  (let [directory (app-directory)
        log (data-file "server.log")
        builder (doto (ProcessBuilder. ^java.util.List ["clojure" "-M:server"])
                  (.directory directory)
                  (.redirectErrorStream true)
                  (.redirectOutput (ProcessBuilder$Redirect/appendTo log)))
        environment (.environment builder)]
    ;; The child must read the same store this process resolved, even when the
    ;; caller set it with a system property the child will not inherit.
    (.put environment "CLOUD_ITONAMI_DATA_DIR" (.getPath (config/data-dir)))
    (let [process (.start builder)]
      (cond
        (await-health configuration startup-budget)
        ;; Whose server answered is worth checking. `spawn!` runs only when
        ;; nothing was healthy, but the wait is long enough for an install
        ;; started by someone else to come up inside it — and recording a pid
        ;; this process does not own would let `down` kill a server it did not
        ;; start. A dead child means the answer came from elsewhere.
        (if (.isAlive process)
          (do (write-pid! (.pid process))
              {:started? true :pid (.pid process) :log (.getPath log)})
          {:started? false :adopted? true :log (.getPath log)})

        ;; Still loading. Killing it here would throw away the time it has
        ;; already spent and make the next invocation start over, which is how a
        ;; slow first run becomes a run that never succeeds. The pid is recorded
        ;; so `status` and `down` can see it.
        (.isAlive process)
        (do (write-pid! (.pid process))
            (throw (ex-info
                    (str "server は起動中です（" (.toSeconds startup-budget)
                         "秒では応答しませんでした）。そのまま起動を続けています。"
                         "`itonami status` で確認するか、少し待ってもう一度実行してください。"
                         "ログ: " (.getPath log))
                    {:type :server-process/still-starting
                     :pid (.pid process)
                     :log (.getPath log)})))

        :else
        (throw (ex-info
                (str "server が起動しませんでした。ログ: " (.getPath log))
                {:type :server-process/start-failed
                 :log (.getPath log)}))))))

(defn ensure-running!
  "A server for this data directory, started if there is not one already."
  [configuration]
  (cond
    (remote?)
    {:schema schema :running? true :started? false :remote? true
     :address (base-url configuration)}

    (healthy? configuration)
    {:schema schema :running? true :started? false :pid (read-pid)}

    :else
    (try
      (if (claim-lock!)
        (merge {:schema schema :running? true} (spawn! configuration))
        (if (await-health configuration startup-budget)
          {:schema schema :running? true :started? false :pid (read-pid)}
          (throw (ex-info "別のプロセスが server を起動中ですが応答しません"
                          {:type :server-process/start-timeout}))))
      (finally (release-lock!)))))

(defn stop!
  "Stop the server this data directory recorded.

  Only one it started: without a pid file there is nothing here to stop, and
  killing whatever answers the port could stop an install this CLI does not own."
  [configuration]
  (let [pid (read-pid)]
    (cond
      (remote?)
      {:schema schema :stopped? false :remote? true
       :reason "CLOUD_ITONAMI_API_URL が設定されています。停止できるのはローカルの server だけです"}

      (not (alive? pid))
      (do (clear-pid!)
          {:schema schema :running? (healthy? configuration) :stopped? false
           :reason (if pid "recorded pid is not running" "no recorded pid")})

      :else
      (let [handle (.orElse (ProcessHandle/of (long pid)) nil)]
        (.destroy handle)
        (.get (.onExit handle) 20 TimeUnit/SECONDS)
        (clear-pid!)
        {:schema schema :stopped? true :pid pid
         :running? (healthy? configuration)}))))

(defn status [configuration]
  (let [pid (read-pid)]
    (cond-> {:schema schema
             :running? (healthy? configuration)
             :address (base-url configuration)
             :data-dir (.getPath (config/data-dir))}
      (remote?) (assoc :remote? true)
      (not (remote?)) (assoc :pid (when (alive? pid) pid)
                             :log (.getPath (data-file "server.log"))))))
