(ns cloud.itonami.app.cli-runner
  "Explicit, shell-free adapters for local coding-agent CLIs.

  The profile/argv/result boundary is intentionally compatible with Tamaki's
  runner profiles and is the extraction point for kotoba-lang/provider."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]
           [java.util.concurrent TimeUnit]))

(def ^:private max-output-chars (* 2 1024 1024))
(def ^:private default-timeout-seconds 600)

(def ^:private runner-definitions
  {:codex
   {:id "codex-cli"
    :name "Codex CLI"
    :model "codex:gpt-5.6-sol"
    :env "CLOUD_ITONAMI_CODEX_BIN"
    :candidates ["/opt/homebrew/bin/codex" "/usr/local/bin/codex"
                 ".local/bin/codex"]}
  :claude
   {:id "claude-cli"
    :name "Claude Code"
    :model "claude:opus"
    :env "CLOUD_ITONAMI_CLAUDE_BIN"
    :candidates ["/opt/homebrew/bin/claude" "/usr/local/bin/claude"
                 ".local/bin/claude"]}
   :claude-zai
   {:id "claude-zai"
    :name "Claude ZAI"
    :model "claude-zai:default"
    :env "CLOUD_ITONAMI_CLAUDE_ZAI_BIN"
    :unset-env ["ANTHROPIC_API_KEY" "ANTHROPIC_AUTH_TOKEN"]
    :candidates [".local/bin/claude-zai" "/opt/homebrew/bin/claude-zai"
                 "/usr/local/bin/claude-zai"]}})

(defn- absolute-candidate [candidate]
  (if (str/starts-with? candidate "/")
    candidate
    (.getPath (io/file (System/getProperty "user.home") candidate))))

(defn- executable-file? [candidate]
  (let [file (io/file candidate)]
    (and (.isAbsolute file) (.isFile file) (.canExecute file))))

(defn profile
  "Resolve only configured or fixed absolute paths; never search ambient PATH."
  [runner]
  (let [{:keys [env candidates] :as definition}
        (get runner-definitions (keyword runner))
        configured (some-> env System/getenv)
        candidates (cond->> (map absolute-candidate candidates)
                     (seq configured) (cons configured))
        binary (some #(when (executable-file? %) %) candidates)]
    (when definition
      (assoc definition :runner (keyword runner) :binary binary
             :available? (boolean binary)))))

(defn profiles []
  (mapv profile (keys runner-definitions)))

(defn- bounded-read [stream]
  (with-open [reader (BufferedReader. (InputStreamReader. stream))]
    (let [result (StringBuilder.)
          buffer (char-array 8192)]
      (loop []
        (let [read (.read reader buffer 0 (alength buffer))]
          (when (and (pos? read) (< (.length result) max-output-chars))
            (.append result buffer 0
                     (min read (- max-output-chars (.length result))))
            (recur))))
      (.toString result))))

(defn- stop-process! [^Process process]
  (doseq [descendant (reverse (vec (iterator-seq
                                    (.iterator (.descendants (.toHandle process))))))]
    (try (.destroyForcibly descendant) (catch Exception _)))
  (try (.destroyForcibly process) (catch Exception _)))

(defn execute!
  "Run an argv vector without a shell, with bounded output and descendant kill."
  [{:keys [argv cwd unset-env timeout-seconds]}]
  (let [builder (ProcessBuilder. ^java.util.List (vec argv))
        _ (when cwd (.directory builder (io/file cwd)))
        environment (.environment builder)
        _ (doseq [key unset-env] (.remove environment key))
        process (.start builder)
        ;; Both Codex and Claude accept an argv prompt but will also append
        ;; piped stdin. ProcessBuilder creates a pipe by default, so leaving it
        ;; open makes the CLI wait forever for more prompt bytes.
        _ (.close (.getOutputStream process))
        stdout (future (bounded-read (.getInputStream process)))
        stderr (future (bounded-read (.getErrorStream process)))
        completed? (.waitFor process (long (or timeout-seconds
                                               default-timeout-seconds))
                             TimeUnit/SECONDS)]
    (when-not completed?
      (stop-process! process)
      (throw (ex-info "CLI agent が制限時間内に完了しませんでした。"
                      {:type :cli-agent/timeout})))
    (let [result {:exit (.exitValue process)
                  :stdout (deref stdout 5000 "")
                  :stderr (deref stderr 5000 "")}]
      (when-not (zero? (:exit result))
        (throw (ex-info "CLI agent の実行に失敗しました。"
                        {:type :cli-agent/failed
                         :exit (:exit result)
                         :stderr (subs (:stderr result) 0
                                       (min 2000 (count (:stderr result))))})))
      result)))

(defn- agent-prompt [mode messages resumed?]
  (let [latest (or (:content (last (filter #(= "user" (:role %)) messages))) "")
        context (str/join "\n\n"
                          (map (fn [{:keys [role content]}]
                                 (str (str/upper-case (or role "user"))
                                      ":\n" content))
                               messages))]
    (str
     (if (= mode :plan)
       (str "Work in PLAN mode. Inspect the workspace as needed, but do not "
            "modify files or external state. Return a concrete, verifiable plan.")
       (str "Operate as an autonomous agent loop: inspect the workspace, plan, "
            "make the smallest scoped changes needed, run relevant verification, "
            "and report the resulting artifacts. Stay inside the workspace and "
            "stop if human authority is required."))
     "\n\nUSER OBJECTIVE:\n"
     (if resumed? latest context))))

(defn- workspace-root []
  (or (some-> (System/getenv "CLOUD_ITONAMI_WORKSPACE_ROOT") str/trim not-empty)
      (System/getProperty "user.dir")))

(defn- read-only? [mode guardrail access]
  (or (= mode :plan) (= guardrail :plan) (= access :read-only)))

(defn- codex-config-args [read-only effort]
  (cond-> ["-c" (str "sandbox_mode=\""
                     (if read-only "read-only" "workspace-write") "\"")
           "-c" "ask_for_approval=\"never\""]
    (seq effort) (into ["-c" (str "model_reasoning_effort=\"" effort "\"")])))

(defn argv
  "Build a safe argv for interactive :plan or :agent mode."
  [{:keys [runner binary]}
   {:keys [mode prompt cwd model access guardrail effort persistent?
           runner-session-id new-session-id]}]
  (let [read-only (read-only? mode guardrail access)]
    (case runner
    :codex
    (if runner-session-id
      (cond-> (into [binary "exec" "resume" "--json" "--skip-git-repo-check"]
                    (codex-config-args read-only effort))
        (not persistent?) (conj "--ephemeral")
        (and model (not (str/ends-with? model ":default")))
        (into ["--model" (last (str/split model #":" 2))])
        true (into [runner-session-id prompt]))
      (cond-> [binary "exec" "--json" "--color" "never"
               "--skip-git-repo-check" "-C" cwd]
        true (into (codex-config-args read-only effort))
        (not persistent?) (conj "--ephemeral")
        (and model (not (str/ends-with? model ":default")))
        (into ["--model" (last (str/split model #":" 2))])
        true (conj prompt)))

    (:claude :claude-zai)
    (cond-> [binary "-p" "--output-format" "json"
             "--permission-mode" (if read-only "plan" "auto")
             "--tools" (if read-only
                         "Read,Glob,Grep"
                         "Read,Glob,Grep,Edit,Write,Bash")]
      (seq effort) (into ["--effort" effort])
      (not persistent?) (conj "--no-session-persistence")
      runner-session-id (into ["--resume" runner-session-id])
      (and (not runner-session-id) new-session-id)
      (into ["--session-id" new-session-id])
      (and model (not (str/ends-with? model ":default")))
      (into ["--model" (last (str/split model #":" 2))])
      true (conj prompt)))))

(defn- parse-codex [output]
  (let [events (keep #(try (json/read-str % :key-fn keyword)
                           (catch Exception _ nil))
                     (str/split-lines output))
        messages (keep (fn [event]
                         (when (and (= "item.completed" (:type event))
                                    (= "agent_message"
                                       (get-in event [:item :type])))
                           (get-in event [:item :text])))
                       events)
        usage (some #(when (= "turn.completed" (:type %)) (:usage %))
                    (reverse events))
        runner-session-id
        (some #(when (= "thread.started" (:type %))
                 (or (:thread_id %) (:thread-id %)))
              events)]
    {:content (or (last messages)
                  (throw (ex-info "Codex CLI から応答本文を取得できませんでした。"
                                  {:type :cli-agent/invalid-output})))
     :usage usage
     :runner-session-id runner-session-id}))

(defn- parse-claude [output fallback-session-id]
  (let [result (json/read-str output :key-fn keyword)]
    {:content (or (:result result)
                  (throw (ex-info "Claude CLI から応答本文を取得できませんでした。"
                                  {:type :cli-agent/invalid-output})))
     :usage (cond-> (:usage result)
              (:total_cost_usd result)
              (assoc :total_cost_usd (:total_cost_usd result)))
     :runner-session-id (or (:session_id result)
                            (:session-id result)
                            fallback-session-id)}))

(defn parse-result
  ([runner output] (parse-result runner output nil))
  ([runner output fallback-session-id]
   (case runner
     :codex (parse-codex output)
     (:claude :claude-zai) (parse-claude output fallback-session-id))))

(defn- ensure-profile! [provider]
  (let [resolved (profile (:runner provider))]
    (when-not (:available? resolved)
      (throw (ex-info (str (:name resolved) " がこの端末で見つかりません。")
                      {:type :cli-agent/unavailable
                       :runner (:runner provider)})))
    resolved))

(defn list-models [provider]
  (let [resolved (profile (:runner provider))]
    (if (:available? resolved)
      (mapv (fn [model]
              (merge {:object "model"
                      :owned_by (:id provider)
                      :provider (:id provider)
                      :provider_kind "cli"
                      :runner (name (:runner resolved))}
                     (if (map? model) model {:id model})))
            (or (seq (:models provider))
                [{:id (or (:model provider) (:model resolved))}]))
      [])))

(defn run-cli!
  [provider {:keys [mode messages prompt model cwd access guardrail effort
                    persistent? timeout-seconds runner-session-id]}]
  (let [resolved (ensure-profile! provider)
        mode (if (= mode :plan) :plan :agent)
        guardrail (if (= guardrail :plan) :plan :auto)
        cwd (.getCanonicalPath (io/file (or cwd (workspace-root))))
        prompt (or prompt (agent-prompt mode messages (boolean runner-session-id)))
        new-session-id (when (and persistent?
                                  (#{:claude :claude-zai}
                                    (:runner resolved))
                                  (not runner-session-id))
                         (str (java.util.UUID/randomUUID)))
        command (argv resolved {:mode mode :prompt prompt :cwd cwd
                                :model model :access access
                                :guardrail guardrail :effort effort
                                :persistent? persistent?
                                :runner-session-id runner-session-id
                                :new-session-id new-session-id})
        result (execute! {:argv command :cwd cwd
                          :unset-env (:unset-env resolved)
                          :timeout-seconds timeout-seconds})]
    (assoc (parse-result (:runner resolved) (:stdout result) new-session-id)
           :runner (:runner resolved))))

(defn chat [provider request]
  (run-cli! provider
            (merge {:mode :agent :guardrail :auto
                    :access :workspace-write :persistent? true}
                   request)))

(defn run-agent!
  [provider request]
  (run-cli! provider (assoc request :mode :agent)))
