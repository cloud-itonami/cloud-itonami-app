(ns cloud.itonami.app.virtual-shell
  "Per-Bot OCI virtual computers for approved general shell work.

  A command string is interpreted only by /bin/bash inside the container. The
  host launches fixed argv vectors, never a host shell. Each Bot has one
  container identity, one explicitly admitted Git root mounted at /workspace,
  and no network, host credentials, Docker socket, or Linux capabilities.
  A host-wide semaphore and a per-workspace lock let several Bots share one PC
  without running two mutating shells in the same repository at once."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.workspace-tools :as workspace-tools]
            [cloud.itonami.app.host :as host])
  (:import [java.security MessageDigest]
           [java.util.concurrent ConcurrentHashMap Semaphore TimeUnit]
           [java.util.concurrent.locks ReentrantLock]))

(def default-image "cloud-itonami-shell:1")
(def ^:private max-output-chars 32000)
(def ^:private max-command-chars 8000)
(def ^:private max-timeout-seconds 600)
(def ^:private docker-candidates
  ["/usr/local/bin/docker" "/opt/homebrew/bin/docker" "/usr/bin/docker"])

(def tool-definitions
  [{:name "virtual_shell_status"
    :description "Inspect this Bot's isolated local virtual computer without changing it."
    :parameters {:type "object" :properties {}}}
   {:name "virtual_shell"
    :description (str "Run one bounded command in this Bot's network-disabled virtual computer. "
                      "Always requires human approval. (write)")
    :parameters {:type "object"
                 :properties {:command {:type "string"}
                              :timeout_seconds {:type "integer"}}
                 :required ["command"]}}])

(def ^:private names (into #{} (map :name) tool-definitions))
(def ^:private writes #{"virtual_shell"})
(defonce ^:private host-slots (Semaphore. 2 true))
(defonce ^:private workspace-locks (ConcurrentHashMap.))
(defonce ^:private active (atom {}))

(defn tool? [name] (contains? names (str name)))
(defn write-tool? [name] (contains? writes (str name)))

(defn bounded-output [value]
  (let [value (str value)]
    (if (> (count value) max-output-chars)
      (str (subs value 0 max-output-chars) "…")
      value)))

(defn- sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn container-name [bot-id]
  (str "cloud-itonami-bot-" (subs (sha256 bot-id) 0 24)))

(defn- workspace-sha [workspace]
  (subs (sha256 workspace) 0 32))

(defn docker-bin []
  (or (some #(when (.canExecute (io/file %)) %) docker-candidates)
      (first docker-candidates)))

(defn admit-workspace
  "Admit a standalone Git root. Linked worktrees point outside the one granted
  root; mounting their common metadata would silently widen authority."
  [value]
  (let [root (workspace-tools/admit-root value)
        git-dir (io/file root ".git")]
    (when-not (.isDirectory git-dir)
      (throw (ex-info (str "Virtual shell requires a standalone Git clone; "
                           "linked worktree metadata is outside this root.")
                      {:type :virtual-shell/standalone-clone-required
                       :workspace root})))
    root))

(defn- host-process []
  (host/process {"id" "/usr/bin/id"
                 "docker" (docker-bin)}))

(defn- host-number [flag fallback]
  (try
    (let [{:keys [exit output]} (host/spawn! (host-process) ["id" flag]
                                             :timeout-ms 5000
                                             :max-stdout-bytes 4096)
          value (str/trim output)]
      (if (and (zero? exit) (re-matches #"[0-9]+" value))
        value fallback))
    (catch Exception _ fallback)))

(defn create-argv
  [{:keys [bot-id workspace image uid gid]
    :or {image default-image}}]
  (let [name (container-name bot-id)
        uid (or uid (host-number "-u" "1000"))
        gid (or gid (host-number "-g" "1000"))]
    ["docker" "create"
     "--name" name
     "--hostname" name
     "--label" (str "cloud.itonami.bot=" (sha256 bot-id))
     "--label" (str "cloud.itonami.workspace=" (workspace-sha workspace))
     "--network" "none"
     "--cap-drop" "ALL"
     "--security-opt" "no-new-privileges:true"
     "--read-only"
     "--cpus" "1"
     "--memory" "1g"
     "--memory-swap" "1g"
     "--pids-limit" "256"
     "--user" (str uid ":" gid)
     "--env" "HOME=/workspace"
     "--workdir" "/workspace"
     "--tmpfs" "/tmp:rw,nosuid,nodev,size=67108864"
     "--mount" (str "type=bind,src=" workspace ",dst=/workspace")
     image "sleep" "infinity"]))

(defn exec-argv [container command timeout-seconds]
  ["docker" "exec" "-i" container
   "/usr/bin/timeout" "-s" "TERM" (str timeout-seconds "s")
   "/bin/bash" "-lc" command])

(defn- run-capture [argv]
  (try
    (let [argv (vec argv)
          cmd (first argv)
          ;; Accept legacy absolute argv0 from call sites still being migrated.
          basename (if (and (string? cmd) (str/includes? cmd "/"))
                     (last (str/split cmd #"/"))
                     cmd)
          argv' (into [basename] (rest argv))
          {:keys [exit output]} (host/spawn! (host-process) argv'
                                             :timeout-ms (* max-timeout-seconds 1000)
                                             :max-stdout-bytes 65536)]
      {:exit exit :output (bounded-output output)})
    (catch Exception error
      {:exit -1 :output (bounded-output (.getMessage error))})))

(defn- successful! [argv operation]
  (let [{:keys [exit output] :as result} (run-capture argv)]
    (when-not (zero? exit)
      (throw (ex-info (str "Virtual computer " operation " failed.")
                      {:type :virtual-shell/docker-failed
                       :operation operation :exit exit :output output})))
    result))

(defn image-ready? []
  (zero? (:exit (run-capture [(docker-bin) "image" "inspect" default-image]))))

(defn available? []
  (and (.canExecute (io/file (docker-bin)))
       (zero? (:exit (run-capture [(docker-bin) "info" "--format" "{{.ServerVersion}}"])))
       (image-ready?)))

(defn- inspect [container format]
  (run-capture [(docker-bin) "inspect" "--format" format container]))

(defn- exists? [container]
  (zero? (:exit (inspect container "{{.Id}}"))))

(defn- ensure-container! [{:keys [bot-id workspace] :as context}]
  (when-not (image-ready?)
    (throw (ex-info "Bot shell image is not installed. Run scripts/build-bot-shell-image."
                    {:type :virtual-shell/image-required :image default-image})))
  (let [container (container-name bot-id)
        expected (workspace-sha workspace)]
    (when (exists? container)
      (let [actual (-> (inspect container
                                "{{ index .Config.Labels \"cloud.itonami.workspace\" }}")
                       :output str/trim)]
        (when-not (= expected actual)
          ;; The old environment has no private disk: /workspace is the old
          ;; explicit bind mount and the root is read-only. Replacing it is the
          ;; only honest way to keep "one Bot, one current workspace" true.
          (successful! [(docker-bin) "rm" "-f" container] "replacement"))))
    (when-not (exists? container)
      (successful! (create-argv (assoc context :image default-image)) "creation"))
    (successful! [(docker-bin) "start" container] "start")
    container))

(defn- workspace-lock [workspace]
  (.computeIfAbsent workspace-locks workspace
                    (reify java.util.function.Function
                      (apply [_ _] (ReentrantLock. true)))))

(defn- validated-input [{:keys [command timeout_seconds]}]
  (let [command (str command)
        timeout (long (or timeout_seconds 120))]
    (when (or (str/blank? command) (> (count command) max-command-chars))
      (throw (ex-info "Shell command is empty or too long."
                      {:type :virtual-shell/invalid-command})))
    (when (or (< timeout 1) (> timeout max-timeout-seconds))
      (throw (ex-info "Shell timeout must be between 1 and 600 seconds."
                      {:type :virtual-shell/invalid-timeout})))
    {:command command :timeout timeout}))

(defn cancel!
  "Stop only the active process and container belonging to bot-id."
  [bot-id]
  (if-let [{:keys [^Process process container]} (get @active bot-id)]
    (do
      (try (.destroyForcibly process) (catch Exception _))
      (run-capture [(docker-bin) "kill" container])
      {:cancelled true :container container})
    {:cancelled false}))

(defn- run-command! [{:keys [bot-id workspace] :as context} input]
  (let [{:keys [command timeout]} (validated-input input)
        ^ReentrantLock lock (workspace-lock workspace)
        started (System/nanoTime)]
    (.acquire host-slots)
    (try
      (.lockInterruptibly lock)
      (try
        (let [container (ensure-container! context)
              process (.start (doto (ProcessBuilder.
                                     ^java.util.List
                                     (exec-argv container command timeout))
                                (.redirectErrorStream true)))
              output (future (slurp (.getInputStream process)))]
          (swap! active assoc bot-id {:process process :container container
                                      :thread (Thread/currentThread)})
          (try
            (when-not (.waitFor process (+ timeout 5) TimeUnit/SECONDS)
              (cancel! bot-id)
              (throw (ex-info "Virtual shell timed out."
                              {:type :virtual-shell/timeout :timeout timeout})))
            (let [exit (.exitValue process)
                  elapsed (long (/ (- (System/nanoTime) started) 1000000))
                  captured (bounded-output (deref output 2000 ""))]
              (str "$ " command "\n"
                   captured
                   (when-not (str/ends-with? captured "\n") "\n")
                   "[container=" container " exit=" exit
                   " duration_ms=" elapsed "]"))
            (catch InterruptedException error
              (cancel! bot-id)
              (.interrupt (Thread/currentThread))
              (throw (ex-info "Virtual shell was cancelled."
                              {:type :virtual-shell/cancelled} error)))
            (finally
              (swap! active dissoc bot-id))))
        (finally (.unlock lock)))
      (finally (.release host-slots)))))

(defn- status [bot-id]
  (let [container (container-name bot-id)]
    (cond
      (not (.canExecute (io/file (docker-bin)))) "Docker runtime is unavailable."
      (not (image-ready?)) (str "Image " default-image " is not installed.")
      (not (exists? container)) (str "ready; container " container " is not created yet")
      :else (str "container " container " status: "
                 (str/trim (:output (inspect container "{{.State.Status}}")))))))

(defn describe [name input]
  (case (str name)
    "virtual_shell"
    (str "隔離VM内で次のcommandを実行します（networkなし、最大"
         (min max-timeout-seconds (max 1 (long (or (:timeout_seconds input) 120))))
         "秒）: " (:command input))
    "virtual_shell_status" "この Bot の隔離VM状態を読み取ります。"
    (str name)))

(defn call! [context name input]
  (case (str name)
    "virtual_shell" (run-command! context (or input {}))
    "virtual_shell_status" (status (:bot-id context))
    (throw (ex-info "Unknown virtual shell tool."
                    {:type :virtual-shell/unknown-tool :tool name}))))
