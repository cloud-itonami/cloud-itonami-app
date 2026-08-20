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
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
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

(defn- probe
  "What `/health` says, as two separate facts.

  `:answering?` is 'something is serving this app here'. `:store` is the
  fingerprint of the data directory THAT process resolved, and it is nil both
  when nothing answered and when what answered is an older build that does not
  publish the field. Those are different, so the caller gets them apart rather
  than folded into one boolean — see `store-agreement`."
  [configuration]
  (try
    (let [request (-> (HttpRequest/newBuilder
                       (URI/create (str (base-url configuration) "/health")))
                      (.timeout (Duration/ofSeconds 2))
                      .GET .build)
          response (.send probe-client request
                          (HttpResponse$BodyHandlers/ofString))
          body (.body response)]
      (if (and (= 200 (.statusCode response))
               (str/includes? body "cloud-itonami-app"))
        {:answering? true
         :store (try (some-> (json/read-str body :key-fn keyword) :store
                             str/trim not-empty)
                     (catch Exception _ nil))}
        {:answering? false :store nil}))
    (catch Exception _ {:answering? false :store nil})))

(defn healthy?
  "Whether something is serving this app at the configured bind.

  `/health` and not `/api/state`: it is the one route that takes no session, so a
  false answer means 'nothing is listening' rather than 'you are not logged in'.

  Liveness only. It does NOT answer whether the process that replied owns this
  data directory — `store-agreement` does, and callers that act on the store
  have to ask it."
  [configuration]
  (:answering? (probe configuration)))

(defn store-agreement
  "Whether the process answering the configured bind owns THIS data directory.

  Three outcomes, not two:

    {:answering? false}                      nothing is there
    {:answering? true :known? false}         it answered without saying whose
                                             store it serves (a build older
                                             than this field)
    {:answering? true :known? true :ours? _} it said, and it either matches or
                                             it does not

  `:known? false` is kept distinct from `:ours? true` on purpose. Folding 'could
  not tell' into 'yes' is the shape that produced this namespace's bug in the
  first place: `ensure-running!` said 'a server for this data directory' having
  checked only that something answered the port. Measured 2026-08-20 — a CLI in
  a second checkout adopted the resident server, then sent it an enrollment key
  from a store that server had never opened and was refused as `invalid-key`,
  while every read command kept working because the session token lives in one
  Keychain item that is not per-store."
  [configuration]
  (let [{:keys [answering? store]} (probe configuration)
        expected (config/store-fingerprint)]
    (cond-> {:answering? answering? :expected expected}
      answering? (assoc :served store
                        :known? (some? store)
                        :ours? (= store expected)))))

(defn- store-directory-named-by
  "A directory on this machine whose store is `fingerprint`, or nil.

  Only the documented resident layout is checked, and only by comparing
  fingerprints — this confirms a candidate, it never proposes one. The point is
  to turn 'the server serves store 4f2a…' into a path the operator can pass to
  `CLOUD_ITONAMI_DATA_DIR`, in the one layout where that answer is knowable."
  [fingerprint]
  (when fingerprint
    (when-let [home (some-> (System/getProperty "user.home") str/trim not-empty)]
      (let [candidate (io/file home ".cloud-itonami" "data")]
        (when (and (.isDirectory candidate)
                   (= fingerprint (config/store-fingerprint candidate)))
          (.getPath (.getCanonicalFile candidate)))))))

(defn foreign-server-refusal
  "The refusal for 'something else owns this port', or nil when it does not.

  Built here rather than at each call site so the CLI and the MCP adapter say
  the same thing, and so the message carries what was measured: this process's
  store, the other one's, and — when the resident layout accounts for it — the
  directory to point at.

  The two-argument form takes an agreement already in hand: `ensure-running!`
  asks once and then both decides and reports from that one answer, rather than
  probing the port twice for one fact."
  ([configuration]
   (foreign-server-refusal configuration (store-agreement configuration)))
  ([configuration {:keys [answering? known? ours? served expected]}]
   (when (and answering? known? (not ours?))
     (let [directory (store-directory-named-by served)
           lines (cond-> [(str (base-url configuration)
                               " に応答している server は、この process の"
                               " store を開いていません。")
                          (str "  この process: " (.getPath (config/data-dir))
                               " (store " expected ")")
                          (str "  応答した server: store " served)]
                   directory
                   (conj (str "  その store の data dir: " directory)
                         (str "  CLOUD_ITONAMI_DATA_DIR=" directory
                              " を指定するか、その install の bin/itonami から"
                              "実行してください"))

                   (not directory)
                   (conj (str "  その server を起動した data dir を"
                              " CLOUD_ITONAMI_DATA_DIR に指定するか、"
                              "その install の bin/itonami から実行して"
                              "ください")))]
       (ex-info (str/join "\n" lines)
                {:type :server-process/foreign-server
                 :expected expected
                 :served served
                 :data-dir (.getPath (config/data-dir))
                 :server-data-dir directory})))))

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

(defn- ensure-local!
  "`ensure-running!` once the port has been asked and answered.

  Split out so the probe happens once: the caller needs the same answer both to
  decide whether to refuse and to decide whether to spawn, and asking twice per
  command was two loopback round trips for one fact."
  [configuration agreement]
  (if (:answering? agreement)
    {:schema schema :running? true :started? false :pid (read-pid)}
    (try
      (if (claim-lock!)
        (merge {:schema schema :running? true} (spawn! configuration))
        (if (await-health configuration startup-budget)
          {:schema schema :running? true :started? false :pid (read-pid)}
          (throw (ex-info "別のプロセスが server を起動中ですが応答しません"
                          {:type :server-process/start-timeout}))))
      (finally (release-lock!)))))

(defn ensure-running!
  "A server for this data directory, started if there is not one already.

  A DIFFERENT install answering the port is refused rather than adopted. It
  cannot be started around — the port is taken — and acting through it would
  read and write a store this process did not resolve. Silence there is what
  made a wrong enrollment key look like a broken login."
  [configuration]
  (if (remote?)
    {:schema schema :running? true :started? false :remote? true
     :address (base-url configuration)}
    (let [agreement (store-agreement configuration)]
      (when-let [refusal (foreign-server-refusal configuration agreement)]
        (throw refusal))
      (ensure-local! configuration agreement))))

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

(defn status
  "Where the server is, and whether it is serving THIS data directory.

  `:store` travels with `:data-dir` because the two used to be reported as one
  fact and were not. Before 2026-08-20 this printed `data-dir` from local
  configuration next to sessions read from whatever answered the port, so a
  terminal in the wrong checkout got a listing that named a store it was not
  reading."
  [configuration]
  (let [pid (read-pid)
        agreement (when-not (remote?) (store-agreement configuration))]
    (cond-> {:schema schema
             :running? (if agreement (:answering? agreement)
                           (healthy? configuration))
             :address (base-url configuration)
             :data-dir (.getPath (config/data-dir))
             :store (config/store-fingerprint)}
      (remote?) (assoc :remote? true)
      (not (remote?)) (assoc :pid (when (alive? pid) pid)
                             :log (.getPath (data-file "server.log")))
      (:answering? agreement)
      (assoc :server-store (or (:served agreement) "unpublished")
             :serves-this-store? (if (:known? agreement)
                                   (:ours? agreement)
                                   "unknown")))))
