(ns cloud.itonami.app.cli
  "A command line client for the app — every operation, without opening it.

  ## Why it no longer needs the app open

  It used to. `bin/cloud-itonami-app` starts a server AND a native window, and
  this could only talk to a server somebody had already started that way. So
  reading your own inbox from a terminal meant opening a desktop app, and on a
  machine where nobody had, an agent could do nothing at all.

  `server-process` closes that without weakening the single-writer rule below: a
  command that needs the server starts a headless one if there is not one
  already, then talks to it exactly as before. `up`, `down` and `status` drive
  that lifecycle explicitly when an operator would rather it not happen as a side
  effect.

  ## Why most commands are generated

  `cloud.itonami.app.commands` reads a registry produced from `server.clj`'s own
  routes. Hand-writing them made this a second list of what the app can do, and
  two lists drift one way: measured 2026-08-05, seventeen commands against 222
  routes, with nothing anywhere reporting the gap. Now `itonami commands` prints
  the coverage it actually has, and a route added without regenerating the
  registry breaks the suite.

  Seventeen commands stay hand-written, and each is here for the same reason: the
  generated form would send a string where the route expects a list, a file's
  contents, or a nested object. Teaching the generator those shapes would mean
  writing down a schema for every route from the outside — so the few that need
  one keep theirs, and the rest are derived.

  ## Why it is a client and not a second writer

  `store/state` is `(defonce state (atom (load-state)))` — read once when a
  process starts and never re-read. A CLI that wrote `state.edn` beside a
  running server would have its write silently reverted by the server's next
  `transact!`. So every command here is an HTTP call to the server that owns the
  store, and `auth login` is the one that gets a token to make the rest with.

  ## Where the token goes

  Into the login Keychain under service `cloud-itonami-app.mcp`, account
  `session-token` — the item `payment-tools` already reads. That is not a
  coincidence to tidy up later: one `auth login` is meant to be what makes the
  CLI *and* the MCP server able to act, and inventing a second location would
  mean enrolling twice for one decision. `CLOUD_ITONAMI_MCP_SESSION` overrides
  it, same as there.

  ## What it is not

  It cannot approve anything. `approve/finish` needs a WebAuthn user-verifying
  assertion; no agent and no CLI can produce one (ADR-0006). An agent session
  may ask, record, and carry out what a human already approved.

  Usage:

    itonami up | down | status
    itonami commands [term …]
    itonami <command words> [--flag value …] [--json '{…}']

    clojure -M:cli auth login --label \"claude-code\" [--ttl-days 30]
    clojure -M:cli auth status
    clojure -M:cli auth revoke --id session-…
    clojure -M:cli tenant list
    clojure -M:cli tenant connect --tenant acme --cap workspace.read,actor.invoke
    clojure -M:cli tenant status --connection tc-…
    clojure -M:cli tenant renew --connection tc-… [--ttl-seconds 3600]
    clojure -M:cli tenant revoke --connection tc-…
    clojure -M:cli tenant repository-read --connection tc-…
    clojure -M:cli tenant repository-write --connection tc-… --file state.edn
    clojure -M:cli tenant repository-publish --connection tc-…
    clojure -M:cli business list
    clojure -M:cli business create --slug cloud-itonami-vc --name \"…\"
    clojure -M:cli business bind --id business-… --repos a,b,c [--canvas …]

  The CLI resolves the server's address and the data directory from the same
  config the server does, so it must run with the same `CLOUD_ITONAMI_DATA_DIR`
  as the server it is talking to. Mismatched, `auth login` reads the wrong key
  file and is refused by the server rather than acting on the wrong store.
  `CLOUD_ITONAMI_API_URL=https://itonami.cloud` switches CLI and MCP adapters
  to the hosted control plane; non-loopback plain HTTP is refused."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.app-client :as client]
            [cloud.itonami.app.commands :as commands]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.server-process :as server-process])
  (:import [java.nio.file Files LinkOption]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; arguments
;; ---------------------------------------------------------------------------

(defn parse-flags
  "`--key value` pairs into a map. A flag with no value is `true`, so `--json`
  works without inventing a second syntax."
  [args]
  (loop [args args acc {}]
    (if-let [head (first args)]
      (if (str/starts-with? head "--")
        (let [k (keyword (subs head 2))
              v (second args)]
          (if (and v (not (str/starts-with? v "--")))
            (recur (drop 2 args) (assoc acc k v))
            (recur (rest args) (assoc acc k true))))
        (recur (rest args) acc))
      acc)))

(defn words
  "The arguments that name a command: not flags, and not a flag's value.

  Walked rather than filtered. `--label claude-code` puts a bare token after a
  flag, and a filter would read `claude-code` as a command word — which matters
  now that the words are looked up in a registry rather than matched against a
  fixed list, because an unexpected word turns a valid call into 'no such
  command'."
  [args]
  (loop [args args acc []]
    (if-let [head (first args)]
      (if (str/starts-with? head "--")
        (let [v (second args)]
          (if (and v (not (str/starts-with? v "--")))
            (recur (drop 2 args) acc)
            (recur (rest args) acc)))
        (recur (rest args) (conj acc head)))
      acc)))

(defn- comma-list [v]
  (when (string? v)
    (->> (str/split v #",") (map str/trim) (remove str/blank?) vec)))

;; ---------------------------------------------------------------------------
;; transport — `app-client` owns it; `mcp` is the other caller
;; ---------------------------------------------------------------------------

(defn- call [configuration method path {:keys [body token]}]
  (client/call configuration method path {:body body :token token}))

(def ^:private unwrap client/unwrap)

;; ---------------------------------------------------------------------------
;; token storage
;; ---------------------------------------------------------------------------

(defn- keychain-put!
  "Store the token as one named Keychain item, replacing any previous one.

  `-U` updates in place instead of erroring on a duplicate, so re-running
  `auth login` after a revoke does not leave the operator with two items and no
  way to tell which one is read."
  [token]
  (let [process (-> (ProcessBuilder.
                     ^java.util.List
                     ["security" "add-generic-password"
                      "-U"
                      "-s" agent-session/keychain-service
                      "-a" agent-session/keychain-account
                      "-w" token
                      "-D" "cloud-itonami agent session"])
                    (.redirectErrorStream true)
                    .start)
        output (future (slurp (.getInputStream process)))
        completed? (.waitFor process 15 TimeUnit/SECONDS)]
    (if (and completed? (zero? (.exitValue process)))
      :stored
      {:failed (str/trim (deref output 500 ""))})))

(defn- stored-token [configuration]
  (agent-session/session-token configuration))

(defn- read-enrollment-key []
  (let [path (agent-session/key-file)]
    (when-not (Files/isRegularFile path (into-array LinkOption []))
      (throw (ex-info (str "enrollment key がありません: " path
                           "\nサーバーを一度起動すると作成されます"
                           "（CLOUD_ITONAMI_DATA_DIR が同じか確認してください）")
                      {:type :cli/no-enrollment-key})))
    (str/trim (String. (Files/readAllBytes path) "UTF-8"))))

;; ---------------------------------------------------------------------------
;; server lifecycle
;; ---------------------------------------------------------------------------

(defn- ensure-server!
  "A server to talk to, started headless if there is not one.

  Every command that makes a request goes through here first. That is what makes
  'works without opening the app' true rather than aspirational — and it is one
  call site, so a command added later cannot forget it."
  [configuration]
  (server-process/ensure-running! configuration))

(defn up [configuration]
  (let [started (ensure-server! configuration)]
    (merge (server-process/status configuration)
           (select-keys started [:started? :remote? :adopted?]))))

(defn down [configuration]
  (server-process/stop! configuration))

(defn status
  "Where the server is and whether this terminal can act.

  Both, because 'nothing works' has two causes that look identical from here — no
  server, or no session — and an operator told only one of them fixes the wrong
  thing."
  [configuration]
  (let [server (server-process/status configuration)]
    (assoc server
           :session
           (cond
             (not (stored-token configuration))
             {:token? false :next "itonami auth login --label <name>"}

             (not (:running? server))
             {:token? true :note "server が起動していないため確認できません"}

             :else
             (try {:token? true
                   :sessions (:sessions
                              (unwrap (call configuration :get "/api/agent-session"
                                            {:token (stored-token configuration)})))}
                  (catch Exception error
                    {:token? true :error (ex-message error)}))))))

;; ---------------------------------------------------------------------------
;; commands
;; ---------------------------------------------------------------------------

(defn- require-token [configuration]
  (or (stored-token configuration)
      (throw (ex-info "session がありません。先に `itonami auth login` を実行してください"
                      {:type :cli/no-session}))))

(defn list-commands
  "Every command, or the ones whose name contains all of `terms`.

  The coverage counts travel with the list, because a list of names does not say
  whether it is all of them. It reports what is NOT here too — the routes only a
  browser Passkey may reach — so that boundary is read rather than discovered by
  looking for a command that was never going to exist."
  [terms]
  (let [{:keys [routes commands human-only unauthenticated]} (commands/counts)
        found (commands/matching terms)]
    {:schema "cloud.itonami.app.cli.commands.v1"
     :coverage {:routes routes
                :commands commands
                :human-passkey-only human-only
                :unauthenticated unauthenticated}
     :note (str "資金・決済・承認の " human-only
                " ルートは WebAuthn user-verifying assertion が必要なため、"
                "CLI からも agent からも実行できません（ADR-0006）")
     :matched (count found)
     :usage (mapv commands/usage found)}))

(defn- path-arguments
  "Path parameters, from `--name value` or from what followed the command words.

  Both, because `itonami esign envelopes show env-1` is what an operator types and
  `--envelope env-1` is what a script generates. Refusing either would make one of
  them wrong for no reason."
  [{:keys [params]} flags rest-words]
  (let [named (into {} (keep (fn [{:keys [name]}]
                               (when-let [value (get flags (keyword name))]
                                 [name value]))
                             params))
        unnamed (remove #(contains? named (:name %)) params)]
    (merge named
           (into {} (map (fn [{:keys [name]} value] [name value])
                         unnamed rest-words)))))

(defn- payload-arguments
  "Flags that are not path parameters and not the CLI's own.

  `--json` is excluded because it carries the whole body; passing it through as a
  key as well would put a JSON string inside the object it describes."
  [{:keys [params]} flags]
  (let [parameter-names (set (map (comp keyword :name) params))]
    (into {} (keep (fn [[k v]]
                     (when-not (or (contains? parameter-names k) (= :json k))
                       [(name k) v]))
                   flags))))

(defn run-command
  "One generated command: fill the route, make the request, return the body."
  [configuration {:keys [command rest]} flags]
  (let [override (when-let [raw (:json flags)]
                   (if (string? raw)
                     (json/read-str raw :key-fn keyword)
                     (throw (ex-info "--json には JSON 文字列を渡してください"
                                     {:type :cli/invalid-json}))))
        arguments (merge (path-arguments command flags rest)
                         (payload-arguments command flags))
        {:keys [method path body]} (commands/request command arguments override)]
    (unwrap (call configuration method path
                  {:body body :token (require-token configuration)}))))

(defn auth-login [configuration flags]
  (let [issued (unwrap
                (call configuration :post "/api/agent-session"
                      {:body {:enrollment-key (read-enrollment-key)
                              :label (or (:label flags) "cli")
                              :user-id (:user-id flags)
                              :ttl-days (some-> (:ttl-days flags) str parse-long)}}))
        stored (keychain-put! (:token issued))]
    {:session-id (:session-id issued)
     :label (:label issued)
     :expires-at (:expires-at issued)
     :keychain (if (= :stored stored) "stored" stored)
     ;; Printed once. The Keychain item is the copy that lasts; echoing it here
     ;; is what makes CLOUD_ITONAMI_MCP_SESSION usable for a client that would
     ;; rather carry the token in its own environment than read the Keychain.
     :token (:token issued)}))

(defn auth-status [configuration]
  (unwrap (call configuration :get "/api/agent-session"
                {:token (require-token configuration)})))

(defn auth-revoke [configuration flags]
  (let [id (or (:id flags)
               (throw (ex-info "--id が必要です（auth status で確認できます）"
                               {:type :cli/missing-id})))]
    (unwrap (call configuration :post (str "/api/agent-session/" id "/revoke")
                  {:token (require-token configuration)}))))

(defn tenant-list [configuration]
  (unwrap (call configuration :get "/v1/tenants"
                {:token (require-token configuration)})))

(defn tenant-connections [configuration]
  (unwrap (call configuration :get "/v1/tenant-connections"
                {:token (require-token configuration)})))

(defn tenant-connect [configuration flags]
  (unwrap
   (call configuration :post "/v1/tenant-connections"
         {:token (require-token configuration)
          :body {:tenant_id (:tenant flags)
                 :agent_id (:agent-id flags)
                 :capabilities (comma-list (:cap flags))
                 :ttl_seconds (some-> (:ttl-seconds flags) parse-long)
                 :budget {:max_operations (some-> (:max-operations flags) parse-long)
                          :max_storage_bytes
                          (some-> (:max-storage-bytes flags) parse-long)}
                 :idempotency_key (:idempotency-key flags)}})))

(defn- required-connection [flags]
  (or (:connection flags)
      (throw (ex-info "--connection が必要です"
                      {:type :cli/missing-connection}))))

(defn tenant-status [configuration flags]
  (unwrap (call configuration :get
                (str "/v1/tenant-connections/" (required-connection flags))
                {:token (require-token configuration)})))

(defn tenant-renew [configuration flags]
  (unwrap (call configuration :post
                (str "/v1/tenant-connections/" (required-connection flags)
                     "/renew")
                {:token (require-token configuration)
                 :body {:ttl_seconds (some-> (:ttl-seconds flags) parse-long)}})))

(defn tenant-revoke [configuration flags]
  (unwrap (call configuration :post
                (str "/v1/tenant-connections/" (required-connection flags)
                     "/revoke")
                {:token (require-token configuration) :body {}})))

(defn tenant-context [configuration flags]
  (unwrap (call configuration :post
                (str "/v1/tenant-connections/" (required-connection flags)
                     "/context")
                {:token (require-token configuration)
                 :body {:capability (:capability flags)}})))

(defn tenant-repository-read [configuration flags]
  (unwrap (call configuration :get
                (str "/v1/tenant-connections/" (required-connection flags)
                     "/repository")
                {:token (require-token configuration)})))

(defn tenant-repository-write [configuration flags]
  (let [file (or (:file flags)
                 (throw (ex-info "--file が必要です"
                                 {:type :cli/missing-file})))]
    (unwrap (call configuration :post
                  (str "/v1/tenant-connections/" (required-connection flags)
                       "/repository")
                  {:token (require-token configuration)
                   :body {:state_edn (slurp file)
                          :expected_cid (:expected-cid flags)}}))))

(defn tenant-repository-publish [configuration flags]
  (unwrap (call configuration :post
                (str "/v1/tenant-connections/" (required-connection flags)
                     "/repository/publish")
                {:token (require-token configuration) :body {}})))

(defn business-list [configuration]
  (unwrap (call configuration :get "/api/business"
                {:token (require-token configuration)})))

(defn business-create [configuration flags]
  (unwrap (call configuration :post "/api/business"
                {:token (require-token configuration)
                 :body {:slug (:slug flags)
                        :name (:name flags)
                        :note (:note flags)}})))

(defn business-bind [configuration flags]
  (let [id (or (:id flags)
               (throw (ex-info "--id が必要です" {:type :cli/missing-id})))
        ;; Only keys the caller actually passed are sent. `bind!` treats a
        ;; present-but-empty key as "clear this face", so sending every key on
        ;; every call would silently unbind whatever this invocation did not
        ;; mention.
        body (cond-> {}
               (contains? flags :repos) (assoc :repos (comma-list (:repos flags)))
               (contains? flags :adoptions) (assoc :adoptions (comma-list (:adoptions flags)))
               (contains? flags :canvas) (assoc :canvas (:canvas flags))
               (contains? flags :model) (assoc :model (:model flags))
               (contains? flags :leverage) (assoc :leverage (:leverage flags))
               (contains? flags :lei) (assoc :lei (:lei flags)))]
    (when (empty? body)
      (throw (ex-info "bind する面を 1 つ以上指定してください（--repos / --canvas / --model / --leverage / --adoptions / --lei）"
                      {:type :cli/nothing-to-bind})))
    (unwrap (call configuration :post (str "/api/business/" id "/bind")
                  {:token (require-token configuration) :body body}))))

(def usage
  (str "itonami — cloud-itonami-app CLI\n\n"
       "  up                     headless server を起動（動いていれば何もしない）\n"
       "  down                   このデータディレクトリの server を停止\n"
       "  status                 server とセッションの状態\n"
       "  commands [語 …]        実行できるコマンド一覧（語で絞り込み）\n\n"
       "  <command> [--flag value …] [--json '{…}']\n"
       "                         `itonami commands` が出す任意のコマンド。\n"
       "                         server が動いていなければ自動で起動します。\n\n"
       "  auth login    --label <name> [--ttl-days N] [--user-id U]\n"
       "  auth status\n"
       "  auth revoke   --id <session-id>\n"
       "  tenant list\n"
       "  tenant connections\n"
       "  tenant connect --tenant <id> --cap a,b [--ttl-seconds N]\n"
       "  tenant status --connection <tc-id>\n"
       "  tenant renew --connection <tc-id> [--ttl-seconds N]\n"
       "  tenant revoke --connection <tc-id>\n"
       "  tenant context --connection <tc-id> --capability <name>\n"
       "  tenant repository-read --connection <tc-id>\n"
       "  tenant repository-write --connection <tc-id> --file state.edn [--expected-cid C]\n"
       "  tenant repository-publish --connection <tc-id>\n"
       "  business list\n"
       "  business create --slug <slug> [--name N] [--note X]\n"
       "  business bind --id <business-id> [--repos a,b] [--canvas c]\n"
       "                [--model path] [--leverage path] [--adoptions a,b] [--lei L]\n\n"
       "資金・決済・承認の操作はブラウザの Passkey が必要で、CLI からは実行できません。\n"))

(defn- run-server-command
  "Anything that needs the server. Reached only after `ensure-server!`, so the
  hand-written commands and the generated ones get that guarantee from one place
  rather than each remembering to ask for it."
  [configuration args flags]
  (let [named (words args)
        [group command] named]
    (case [group command]
      ["auth" "login"] (auth-login configuration flags)
      ["auth" "status"] (auth-status configuration)
      ["auth" "revoke"] (auth-revoke configuration flags)
      ["tenant" "list"] (tenant-list configuration)
      ["tenant" "connections"] (tenant-connections configuration)
      ["tenant" "connect"] (tenant-connect configuration flags)
      ["tenant" "status"] (tenant-status configuration flags)
      ["tenant" "renew"] (tenant-renew configuration flags)
      ["tenant" "revoke"] (tenant-revoke configuration flags)
      ["tenant" "context"] (tenant-context configuration flags)
      ["tenant" "repository-read"] (tenant-repository-read configuration flags)
      ["tenant" "repository-write"] (tenant-repository-write configuration flags)
      ["tenant" "repository-publish"] (tenant-repository-publish configuration flags)
      ["business" "list"] (business-list configuration)
      ["business" "create"] (business-create configuration flags)
      ["business" "bind"] (business-bind configuration flags)
      (if-let [resolved (commands/resolve-command named)]
        (run-command configuration resolved flags)
        (throw (ex-info
                (str "そのコマンドはありません: " (str/join " " named)
                     "\n`itonami commands " (first named) "` で近いものを探せます")
                {:type :cli/usage}))))))

(defn run
  "Dispatch. Returns the value to print, or throws with a message to show.

  The lifecycle commands come first and do NOT start a server: `down` would
  otherwise start one in order to stop it, and `commands` reads a resource on the
  classpath and needs nothing running at all."
  [configuration args]
  (let [flags (parse-flags args)
        named (words args)]
    (when (empty? named)
      (throw (ex-info usage {:type :cli/usage})))
    (case (first named)
      "up" (up configuration)
      "down" (down configuration)
      "status" (status configuration)
      "commands" (list-commands (rest named))
      (do (ensure-server! configuration)
          (run-server-command configuration args flags)))))

(defn -main [& args]
  (try
    (println (json/write-str (run (config/load-config) (vec args))
                             :escape-unicode false))
    (System/exit 0)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println (ex-message e)))
      (System/exit 1))
    (catch Exception e
      (binding [*out* *err*] (println (str "error: " (ex-message e))))
      (System/exit 1))))
