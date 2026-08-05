(ns cloud.itonami.app.commands
  "Every operation this app serves, as a command an operator can name.

  ## One list, generated from the routes

  The registry is `resources/cloud-itonami-app.commands.edn`, produced by
  `dev/gen_commands.cljs` from `server.clj`'s own `cond`. Nothing here is hand
  maintained, because a hand-maintained list of what an app can do drifts in
  exactly one direction: a route lands, the command is never added, and the CLI
  covers less than it says it does while nothing anywhere reports the gap.
  Measured 2026-08-05, before this existed — seventeen commands against more than
  two hundred routes.

  `commands-test` regenerates the registry and fails when the checked-in copy has
  fallen behind, so adding a route without regenerating breaks the suite.

  ## What is NOT here, and why that is not a gap

  Three route kinds are deliberately absent, and the registry records how many of
  each so the absence is a number rather than a silence:

  - **Human-passkey routes** — funding, settlement, and governed approval.
    `approve/finish` needs a WebAuthn user-verifying assertion. No CLI can
    produce one and no agent can either (ADR-0006), so publishing these as
    commands would mean publishing commands that are certain to refuse.
  - **The browser handshake** — passkey enroll/authenticate, the OAuth callbacks
    a provider redirects to, `/` and the page routes. These are steps in a
    conversation with a browser, not operations.
  - **Unauthenticated infrastructure** — `/health`, `/.well-known/*`, the webhook
    receivers. Nothing to drive.

  ## The shape of a command

      {:command [\"workspace\" \"drive\" \"documents\" \"rename\"]
       :method \"POST\"
       :template \"/api/workspace/drive/documents/{document}/rename\"
       :params [{:name \"document\" :in \"path\" :required? true}]
       :flags [\"title\"]}

  `:flags` are the keys the route was seen to read. They are hints for help
  output, NOT a schema — unknown flags are passed through, because a schema this
  cannot verify would refuse calls the server would have accepted."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def resource-name "cloud-itonami-app.commands.edn")

(defn- load-registry []
  (if-let [resource (io/resource resource-name)]
    (edn/read-string (slurp resource))
    (throw (ex-info (str resource-name " is not on the classpath. "
                         "Run `nbb --classpath src dev/gen_commands.cljs`.")
                    {:type :commands/no-registry}))))

(defonce ^:private registry (delay (load-registry)))

(defn all
  "Every command, in the order the registry lists them."
  []
  (:commands @registry))

(defn counts
  "How many routes there are and how many this registry covers. Reported by
  `itonami commands` so the operator reads a coverage number rather than
  inferring one from the length of a list."
  []
  (:counts @registry))

(defn- longest-match
  "The command whose words are a prefix of `words`, preferring the longest.

  Longest wins because `workspace drive documents` and `workspace drive
  documents rename` are both commands, and a shorter match would swallow the
  more specific one's trailing word as an argument."
  [words]
  (->> (all)
       (filter (fn [{:keys [command]}]
                 (= command (vec (take (count command) words)))))
       (sort-by (comp count :command) >)
       first))

(defn resolve-command
  "`{:command … :rest …}` for these words, or nil.

  `:rest` is what followed the command's own words — positional arguments for
  path parameters, so `itonami esign envelopes show env-1` works alongside
  `--envelope env-1`."
  [words]
  (when-let [command (longest-match words)]
    {:command command :rest (vec (drop (count (:command command)) words))}))

(defn matching
  "Commands whose name contains every one of `terms`. Empty terms means all."
  [terms]
  (let [terms (remove str/blank? (map str/lower-case terms))]
    (filter (fn [{:keys [command]}]
              (let [name (str/lower-case (str/join " " command))]
                (every? #(str/includes? name %) terms)))
            (all))))

(defn command-name [{:keys [command]}] (str/join " " command))

(defn- fill-template
  "The concrete path, and the arguments that were not consumed filling it."
  [{:keys [template params]} arguments]
  (reduce
   (fn [{:keys [path remaining]} {:keys [name]}]
     (if-let [value (get remaining name)]
       {:path (str/replace path (str "{" name "}")
                           (java.net.URLEncoder/encode (str value) "UTF-8"))
        :remaining (dissoc remaining name)}
       (throw (ex-info (str "--" name " が必要です（" template "）")
                       {:type :commands/missing-parameter :parameter name}))))
   {:path template :remaining arguments}
   params))

(defn- query-string [values]
  (str/join "&" (map (fn [[k v]]
                       (str (java.net.URLEncoder/encode (str k) "UTF-8") "="
                            (java.net.URLEncoder/encode (str v) "UTF-8")))
                     values)))

(defn request
  "The HTTP request one invocation means.

  Path parameters come out of `arguments` by name; everything left over becomes
  the query string for a read and the JSON body for a write. That split is not a
  convention invented here — it is where each kind of route already looks."
  [{:keys [method] :as command} arguments body-override]
  (let [{:keys [path remaining]} (fill-template command arguments)
        read? (= "GET" method)]
    {:method (keyword (str/lower-case method))
     :path (if (and read? (seq remaining))
             (str path "?" (query-string remaining))
             path)
     :body (when-not read?
             (merge (into {} (map (fn [[k v]] [(keyword k) v])) remaining)
                    body-override))}))

(defn usage
  "One line of help for a command: its name, then the flags it is known to take."
  [{:keys [params flags] :as command}]
  (str (command-name command)
       (str/join "" (map #(str " --" (:name %) " <" (:name %) ">") params))
       (str/join "" (map #(str " [--" % " <value>]") flags))))
