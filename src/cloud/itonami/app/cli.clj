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
  two lists drift one way: measured 2026-08-05, the original seventeen commands against 222
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

  It cannot create or widen a Bot. It may submit work and, only for a Bot whose
  owner enabled omakase in the app, approve its held shell/mail/Git write.

  Usage:

    itonami up | down | status
    itonami kaiyu [--days 7]        ; このマシンの回遊（送信しない）
    itonami commands [term …]
    itonami <command words> [--flag value …] [--json '{…}']

    clojure -M:cli auth login --label \"claude-code\" [--ttl-days 30] [--organization <slug>]
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
  as the server it is talking to. Mismatched, the command is refused before it
  is sent — `/health` publishes the store the answering process opened, and
  `server-process/ensure-running!` will not adopt a server serving a different
  one. A server too old to publish it cannot be checked, and is still used; that
  is the one case `enrollment-refused` explains after the fact.

  Until 2026-08-20 nothing checked at all. The reads went through on a Keychain
  token that is not per-store, so only `auth login` failed — with `invalid-key`
  and no mention of either directory involved.
  `CLOUD_ITONAMI_API_URL=https://itonami.cloud` switches CLI and MCP adapters
  to the hosted control plane; non-loopback plain HTTP is refused."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.app-client :as client]
            [cloud.itonami.app.commands :as commands]
            [cloud.itonami.app.git-hygiene :as git-hygiene]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.kaiyu-local :as kaiyu-local]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.server-process :as server-process]
            [cloud.itonami.app.west-kotoba-refactor :as west-refactor])
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

(defn ensure-server!
  "A server to talk to, started headless if there is not one.

  Called by `-main`, not by `run`. Starting a process is something the PROGRAM
  does, not something dispatching a command does — and the difference is not
  bookkeeping: `cli-test` drives `run` directly with a stub transport and a
  configuration that names no server, so an `ensure-server!` inside `run` made
  the suite spawn `clojure -M:server` and block for the full startup budget.
  Measured, on the first run after this landed."
  [configuration]
  (server-process/ensure-running! configuration))

(def lifecycle-commands
  "The commands that must NOT start a server to answer.

  `down` would otherwise start one in order to stop it, `status` would report a
  server it had just created rather than the one that was there, and `commands`
  reads a resource off the classpath and needs nothing running at all. `up`
  starts one itself."
  #{"up" "down" "status" "commands" "kaiyu"})

(defn needs-server?
  "Whether these arguments describe a command that will make a request."
  [args]
  (let [named (words args)]
    (not (or (contains? lifecycle-commands (first named))
             (contains? #{["bots" "refactor" "scan"]
                          ["bots" "refactor" "inspect"]}
                        (vec (take 3 named)))))))

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

(defn- enrollment-refused
  "`invalid-key` again, with the fact that would have explained it.

  A server built before `/health` published its store cannot be asked whose
  store it serves, so `ensure-running!` lets the command through and the key
  goes to whatever is there. When that key is refused, the reading the operator
  needs is not 'wrong key' — it is 'this may be a different install'. Saying so
  only when the store is genuinely unknown keeps the message honest: a server
  that DID publish its store was already refused before the key left this
  process."
  [configuration error]
  (let [{:keys [answering? known?]} (server-process/store-agreement configuration)]
    (if (and answering? (not known?))
      (ex-info (str (ex-message error)
                    "\n  この process の data dir: "
                    (.getPath (config/data-dir))
                    "\n  応答した server は自分の store を公開していません"
                    "（この field より前の build）。別の install の可能性があります —"
                    " その server を再起動して `itonami status` の"
                    " serves-this-store? を確認してください")
               (assoc (ex-data error) :data-dir (.getPath (config/data-dir))))
      error)))

(defn auth-login [configuration flags]
  (let [issued (try
                 (unwrap
                  (call configuration :post "/api/agent-session"
                        {:body {:enrollment-key (read-enrollment-key)
                                :label (or (:label flags) "cli")
                                :user-id (:user-id flags)
                                :organization-id (:organization flags)
                                :ttl-days (some-> (:ttl-days flags) str parse-long)}}))
                 (catch clojure.lang.ExceptionInfo error
                   (throw (enrollment-refused configuration error))))
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

(defn- required-flag [flags key]
  (or (get flags key)
      (throw (ex-info (str "--" (name key) " が必要です")
                      {:type :cli/missing-flag :flag key}))))

(defn bot-list [configuration]
  (client/request! configuration :get "/api/agent-bots"))

(defn bot-workforce [configuration]
  (client/request! configuration :get "/api/agent-bots/workforce"))

(defn bot-workforce-provision
  "Make a registry edit live. Reconciles the installed Bots to what
  `network-awai/loop-yakuwari` declares.

  `bots workforce` reports `provisioned-at`; when that is older than the
  registry, running Bots are carrying objectives nobody wrote any more. This is
  how that gets fixed without opening the browser."
  [configuration]
  (client/request-with-timeout! configuration :post
                                "/api/agent-bots/workforce/provision" 120 {}))

(defn bot-messages [configuration flags]
  (client/request! configuration :get
                   (str "/api/agent-bots/" (required-flag flags :id) "/messages")))

(defn bot-model [configuration flags]
  (client/request! configuration :post
                   (str "/api/agent-bots/" (required-flag flags :id) "/model")
                   {:provider-id (required-flag flags :provider)
                    :model (required-flag flags :model)}))

(defn bot-task [configuration flags]
  (client/request-with-timeout!
   configuration :post
   (str "/api/agent-bots/" (required-flag flags :id) "/messages") 660
   {:text (required-flag flags :text)}))

(defn bot-handoff [configuration flags]
  (client/request-with-timeout!
   configuration :post
   (str "/api/agent-bots/" (required-flag flags :from) "/handoff") 660
   {:to (required-flag flags :to)
    :task (required-flag flags :task)
    :depth (some-> (get flags :depth) parse-long)}))

(defn bot-decide [configuration flags]
  (client/request-with-timeout!
   configuration :post
   (str "/api/agent-bots/" (required-flag flags :id) "/cards/"
        (required-flag flags :card) "/decide") 660
   {:decision (required-flag flags :decision)}))

(defn bot-cancel [configuration flags]
  (client/request! configuration :post
                   (str "/api/agent-bots/" (required-flag flags :id)
                        "/messages/" (required-flag flags :run) "/cancel") {}))

(defn bot-hygiene
  "The git hygiene the Git Maintainer Bot reads, on demand.

  Read-only and host-side, like `bots refactor scan`: the numbers come from
  west metadata on this machine, so asking the server for them would only add
  a hop. `--root` defaults to the same workspace the resident Bot is pointed
  at, because two ways to name that directory is a way for them to disagree."
  [configuration flags]
  (git-hygiene/status (or (:root flags)
                          (get-in configuration [:business :workspace-root])
                          (git-hygiene/workspace-root))))

(defn- refactor-root [configuration flags]
  (or (:root flags)
      (get-in configuration [:business :workspace-root])
      (throw (ex-info "--root または :business :workspace-root が必要です"
                      {:type :west-refactor/root-required}))))

(defn bot-refactor-scan [configuration flags]
  (west-refactor/scan (refactor-root configuration flags)
                      {:limit (or (some-> (:limit flags) parse-long) 25)}))

(defn bot-refactor-inspect [configuration flags]
  (west-refactor/inspect-project
   (refactor-root configuration flags)
   (required-flag flags :repo)
   {:limit (or (some-> (:limit flags) parse-long) 8)}))

(defn- find-bot [overview id]
  (some #(when (= id (:id %)) %) (:bots overview)))

(defn bot-refactor-start [configuration flags]
  (let [id (required-flag flags :id)
        inspection (bot-refactor-inspect configuration flags)
        expected (get-in inspection [:project :checkout])
        b (find-bot (bot-list configuration) id)]
    (when-not b
      (throw (ex-info (str "Bot が見つかりません: " id)
                      {:type :west-refactor/bot-missing :bot id})))
    (when-not (:coding? b)
      (throw (ex-info "対象Botにはcoding capabilityがありません"
                      {:type :west-refactor/coding-required :bot id})))
    (when-not (= (.getCanonicalPath (io/file expected))
                 (some-> (:workspace b) io/file .getCanonicalPath))
      (throw (ex-info (str "Bot workspaceが対象west projectと一致しません。期待: " expected)
                      {:type :west-refactor/workspace-mismatch
                       :expected expected :actual (:workspace b)})))
    (when-not (:virtual-shell-ready? b)
      (throw (ex-info "移行の適用には、隔離されたvirtual shellが利用可能なcoding Botが必要です"
                      {:type :west-refactor/verification-required :bot id})))
    (client/request-with-timeout!
     configuration :post (str "/api/agent-bots/" id "/messages") 660
     {:text (west-refactor/task-text inspection)})))

(def usage
  (str "itonami — cloud-itonami-app CLI\n\n"
       "  up                     headless server を起動（動いていれば何もしない）\n"
       "  down                   このデータディレクトリの server を停止\n"
       "  status                 server とセッションの状態\n"
       "  commands [語 …]        実行できるコマンド一覧（語で絞り込み）\n\n"
       "  <command> [--flag value …] [--json '{…}']\n"
       "                         `itonami commands` が出す任意のコマンド。\n"
       "                         server が動いていなければ自動で起動します。\n\n"
       "  auth login    --label <name> [--ttl-days N] [--user-id U] [--organization <slug|org-id>]\n"
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
       "  bots list\n"
       "  bots workforce\n"
       "  bots provision\n"
       "  bots messages --id <bot-id>\n"
       "  bots model --id <bot-id> --provider <provider-id> --model <model-id>\n"
       "  bots task --id <bot-id> --text <依頼>\n"
       "  bots handoff --from <bot-id> --to <bot-id> --task <依頼> [--depth N]\n"
       "  bots decide --id <bot-id> --card <card-id> --decision approved|rejected\n"
       "  bots cancel --id <bot-id> --run <run-id>\n"
       "  bots hygiene [--root <west-root>]\n"
       "                         west 全 checkout の git 衛生状態（読み取りのみ）\n\n"
       "  bots refactor scan --root <west-root> [--limit 25]\n"
       "  bots refactor inspect --root <west-root> --repo <west-name> [--limit 8]\n"
       "  bots refactor start --root <west-root> --repo <west-name> --id <bot-id>\n\n"
       "CLI では Bot の model route だけ変更できます。権限設定と通常モードの承認はブラウザ専用です。\n"
       "CLI承認はおまかせBotだけです。\n"))

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
      ["bots" "list"] (bot-list configuration)
      ["bots" "workforce"] (bot-workforce configuration)
      ["bots" "provision"] (bot-workforce-provision configuration)
      ["bots" "messages"] (bot-messages configuration flags)
      ["bots" "model"] (bot-model configuration flags)
      ["bots" "task"] (bot-task configuration flags)
      ["bots" "handoff"] (bot-handoff configuration flags)
      ["bots" "decide"] (bot-decide configuration flags)
      ["bots" "cancel"] (bot-cancel configuration flags)
      ["bots" "hygiene"] (bot-hygiene configuration flags)
      ["bots" "refactor"]
      (case (nth named 2 nil)
        "scan" (bot-refactor-scan configuration flags)
        "inspect" (bot-refactor-inspect configuration flags)
        "start" (bot-refactor-start configuration flags)
        (throw (ex-info "bots refactor は scan / inspect / start を指定してください"
                        {:type :cli/usage})))
      (if-let [resolved (commands/resolve-command named)]
        (run-command configuration resolved flags)
        (throw (ex-info
                (str "そのコマンドはありません: " (str/join " " named)
                     "\n`itonami commands " (first named) "` で近いものを探せます")
                {:type :cli/usage}))))))

(defn run
  "Dispatch. Returns the value to print, or throws with a message to show.

  Dispatch only. It does not start a server — `-main` does that first, for the
  commands that need one — so this stays callable from a test with a stub
  transport."
  [configuration args]
  (let [flags (parse-flags args)
        named (words args)]
    (when (empty? named)
      (throw (ex-info usage {:type :cli/usage})))
    (case (first named)
      "up" (up configuration)
      "down" (down configuration)
      "status" (status configuration)
      ;; 回遊 (local only). A lifecycle command because it reads this machine's
      ;; own state file directly — starting a server to ask it about counters
      ;; it already wrote would be the long way round, and `needs-server?`
      ;; exists for exactly that.
      ;;
      ;; This is the reader the counters were missing: cloud.itonami.app
      ;; .kaiyu-local has recorded page views since 2026-08-08 and nothing
      ;; could show them, and a counter nobody can read is not a counter.
      ;; It is a CLI command rather than an HTTP route because `server/handler`
      ;; is already at the JVM's 64 KB method ceiling — two more branches there
      ;; failed to compile, which is how this landed in the right place.
      "kaiyu" (kaiyu-local/report (store/snapshot)
                                  {:days (some-> (get flags "days") parse-long)})
      "commands" (list-commands (rest named))
      (run-server-command configuration args flags))))

(defn qualified-names
  "Every keyword in `value`, carrying the name it actually has.

  `json/write-str` renders a keyword as its `name` alone, so
  `:provider/http-error` reaches an operator as `http-error` -- which is
  indistinguishable from another namespace's `http-error` and from a bare one.
  The server is careful about this: `resident-outcomes` counts under the FULL
  name for exactly this reason, and sends it as a JSON string. `app-client`
  then parses the response with `:key-fn keyword`, and this printer wrote it
  back out with the namespace gone -- undoing the server's care in the one
  surface an operator and an agent actually read.

  Measured 2026-08-30: `bots workforce` reported `{\"http-error\": 40}` for 40
  runs whose recorded type was `:provider/http-error`. Deciding whether to look
  at the model provider or at this application began with a name that could not
  say which.

  A walk rather than `:key-fn`/`:value-fn`: those two reach map keys and map
  values, and a keyword inside a VECTOR would still be stripped. A guarantee
  with a hole in it reads exactly like a whole one."
  [value]
  (walk/postwalk #(if (keyword? %) (subs (str %) 1) %) value))

(defn -main [& args]
  (try
    (let [configuration (config/load-config)
          args (vec args)]
      (when (and (seq (words args)) (needs-server? args))
        (ensure-server! configuration))
      (println (json/write-str (qualified-names (run configuration args))
                               :escape-unicode false
                               :escape-slash false)))
    (System/exit 0)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println (ex-message e)))
      (System/exit 1))
    (catch Exception e
      (binding [*out* *err*] (println (str "error: " (ex-message e))))
      (System/exit 1))))
