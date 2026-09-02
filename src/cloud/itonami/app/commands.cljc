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
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]))

(def resource-name "cloud-itonami-app.commands.edn")
(def alias-resource-name "cloud-itonami-app.cli-aliases.edn")

;; Two runtimes read these tables and only one of them has a classpath. The JVM
;; keeps `io/resource`; a ClojureScript host hands the text in. The PARSING and
;; every judgement made from it stay here, so the front ends share one decider
;; rather than agreeing until the day they do not.
(defonce ^:private supplied (atom {}))

(defn install-sources!
  "Give this namespace the table text directly, for a host with no classpath."
  [m]
  (reset! supplied m))

(defn- read-source [name]
  (or (get @supplied name)
      #?(:clj (some-> (io/resource name) slurp)
         :cljs nil)))

(defn- load-table [name hint]
  (if-let [text (read-source name)]
    (edn/read-string text)
    (throw (ex-info (str name " is not available. " hint)
                    {:type :commands/no-registry :resource name}))))

(defonce ^:private registry
  (delay (load-table resource-name
                     "Run `nbb --classpath src dev/gen_commands.cljs`, or call install-sources!.")))

(defonce ^:private aliases
  (delay (load-table alias-resource-name
                     "It ships in resources/; call install-sources! on a host without a classpath.")))

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

(defn- url-encode
  "Percent-encode one path or query component.

  `URLEncoder/encode` is form encoding, which turns a space into `+`; that is
  what this app has always sent and what its routes read, so the ClojureScript
  side matches it rather than the other way round."
  [value]
  (let [s (str value)]
    #?(:clj (java.net.URLEncoder/encode s "UTF-8")
       :cljs (-> (js/encodeURIComponent s)
                 (str/replace "%20" "+")
                 (str/replace "!" "%21")
                 (str/replace "'" "%27")
                 (str/replace "(" "%28")
                 (str/replace ")" "%29")
                 (str/replace "~" "%7E")
                 (str/replace "*" "%2A")))))

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
       {:path (str/replace path (str "{" name "}") (url-encode value))
        :remaining (dissoc remaining name)}
       (throw (ex-info (str "--" name " が必要です（" template "）")
                       {:type :commands/missing-parameter :parameter name}))))
   {:path template :remaining arguments}
   params))

(defn- query-string [values]
  (str/join "&" (map (fn [[k v]] (str (url-encode k) "=" (url-encode v))) values)))

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

;; ---------------------------------------------------------------------------
;; argv
;;
;; Moved here from `cli.clj` when a second front end appeared. Two copies of
;; "which words name a command" would agree until a flag value happened to look
;; like a command word, and nothing would notice.
;; ---------------------------------------------------------------------------

(defn parse-flags
  "`--key value` pairs, and `--key` alone as true.

  A value that itself starts with `--` is not consumed, so a trailing boolean
  flag before another flag works without inventing a second syntax."
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
  flag, and a filter would read `claude-code` as a command word -- which matters
  when the words are looked up in a table rather than matched against a fixed
  list, because an unexpected word turns a valid call into 'no such command'."
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

;; ---------------------------------------------------------------------------
;; aliases — the commands an operator types
;; ---------------------------------------------------------------------------

(defn alias-commands [] (:commands @aliases))
(defn host-side-commands [] (:host-side @aliases))

(defn- longest-in [table words]
  (->> table
       (filter (fn [{:keys [command]}] (= command (vec (take (count command) words)))))
       (sort-by (comp count :command) >)
       first))

(defn resolve-alias
  "The alias these words name, or nil."
  [words]
  (longest-in (alias-commands) words))

(defn host-side
  "The host-side command these words name, or nil.

  Named separately so a front end can refuse `bots hygiene` as 'this one does
  not go through the server' rather than as 'no such command', which would read
  as a typo the operator did not make."
  [words]
  (longest-in (host-side-commands) words))

(defn- missing! [what detail]
  (throw (ex-info what (merge {:type :commands/missing-argument} detail))))

(defn- coerce
  [value parse {:keys [read-file]}]
  (case parse
    nil value
    :long (some-> value str parse-long)
    :comma-list (when (string? value)
                  (->> (str/split value #",") (map str/trim) (remove str/blank?) vec))
    :boolish (contains? #{true "true" "1" "yes"} value)
    :file-contents (if read-file
                     (read-file value)
                     (missing! "この front end はファイルを読めません"
                               {:parse :file-contents}))
    (missing! (str "未知の :parse " (pr-str parse)) {:parse parse})))

(defn- spec? [m] (and (map? m) (or (contains? m :flag) (contains? m :enrollment-key))))

(defn- body-value [flags spec opts k]
  (cond
    (not (spec? spec))
    ;; a nested body object; drop it when every leaf was absent
    (let [nested (reduce-kv (fn [acc kk v]
                              (let [x (body-value flags v opts kk)]
                                (if (= ::absent x) acc (assoc acc kk x))))
                            {} spec)]
      (if (seq nested) nested ::absent))

    (:enrollment-key spec)
    (if-let [f (:enrollment-key opts)]
      (f)
      (missing! "この front end は enrollment key を読めません" {:key k}))

    :else
    (let [raw (get flags (:flag spec) (:default spec))]
      (cond
        (and (nil? raw) (:required? spec))
        (missing! (str "--" (name (:flag spec)) " が必要です")
                  {:flag (:flag spec)})
        (nil? raw) ::absent
        :else (coerce raw (:parse spec) opts)))))

(defn alias-request
  "The HTTP request one alias invocation means.

  `opts` supplies the effects a table cannot carry: `:read-file` for
  `:parse :file-contents`, `:enrollment-key` for `auth login`. Absent and
  required is a refusal that names the flag; absent and optional drops the key,
  because the server reads a present-but-empty key as an instruction."
  [{:keys [method template params body requires-one-of timeout-seconds]} flags opts]
  (let [path (reduce (fn [path {:keys [name flag required? default hint]}]
                       (let [v (get flags flag default)]
                         (cond
                           (and (nil? v) required?)
                           (missing! (str "--" (clojure.core/name flag) " が必要です"
                                          (when hint (str "（" hint "）")))
                                     {:flag flag})
                           (nil? v)
                           (missing! (str "--" (clojure.core/name flag) " が必要です")
                                     {:flag flag})
                           :else
                           (str/replace path (str "{" name "}") (url-encode v)))))
                     template
                     params)
        built (reduce-kv (fn [acc k spec]
                           (let [v (body-value flags spec opts k)]
                             (if (= ::absent v) acc (assoc acc k v))))
                         {} (or body {}))]
    (when (and (seq requires-one-of)
               (not-any? #(contains? built %) requires-one-of))
      (missing! (str "次のいずれかを指定してください: "
                     (str/join " / " (map #(str "--" (name %)) requires-one-of)))
                {:requires-one-of requires-one-of}))
    (cond-> {:method method :path path}
      (= :post method) (assoc :body built)
      timeout-seconds (assoc :timeout-seconds timeout-seconds))))

;; ---------------------------------------------------------------------------

(defn resolve-invocation
  "What these argv words mean, as data.

  Aliases win over the generated registry: `business list` is in both, and the
  alias is the reviewed one. `:host-side` and `:unknown` are distinct outcomes,
  because 'this command does not go through the server' and 'no such command'
  send an operator to different places."
  [argv]
  (let [named (words argv)
        flags (parse-flags argv)]
    (if-let [a (resolve-alias named)]
      {:kind :alias :command a :flags flags :words named}
      (if-let [h (host-side named)]
        {:kind :host-side :command h :flags flags :words named}
        (if-let [{:keys [command rest]} (resolve-command named)]
          {:kind :registry :command command :rest rest :flags flags :words named}
          {:kind :unknown :words named :flags flags})))))
