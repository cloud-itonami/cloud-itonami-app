(ns cloud.itonami.app.agent-control
  "Bounded local agent execution with explicit device capabilities and HIL."
  (:require [agent.run :as agent-run]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.cli-runner :as cli-runner]
            [cloud.itonami.app.chronicle :as chronicle]
            [cloud.itonami.app.desktop :as desktop]
            [cloud.itonami.app.local-query :as local-query]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.store :as store]
            [hil.core :as hil])
  (:import [java.net URI]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(def default-session-name
  "The browser profile used when nobody has said whose work this is.

  It is a SHARED profile, and that is the whole hazard this namespace used to
  carry silently: the isolated browser keeps cookies and logged-in sessions,
  so every caller reaching these tools was reusing one identity. A Bot that
  signed into a service left that session sitting there for the next one.
  Isolation was per MACHINE when the thing people reason about is per Bot."
  "cloud-itonami-agent")

(def ^:dynamic *browser-session*
  "Whose browser profile the tools act in, for the current call.

  Dynamic rather than a parameter because `execute-tool!` dispatches on a tool
  NAME and its callers are several layers up; threading an argument would have
  touched every one of them and been dropped by the first that forgot. A caller
  that says nothing keeps the previous behaviour exactly."
  nil)

(defn session-for
  "A stable browser-profile name for one principal.

  Derived from the principal's id rather than its display name: a name can be
  changed, and two Bots may share one. Non-alphanumerics are folded to `-` so
  the value is safe as a directory component, and the prefix keeps it
  recognisable next to the shared profile in a process listing."
  [principal-id]
  (let [id (str/replace (str principal-id) #"[^A-Za-z0-9_-]+" "-")]
    (if (str/blank? id)
      default-session-name
      (str default-session-name "-" id))))

(def ^:private max-output 24000)

(def ^:private browser-tools
  [{:name "browser_snapshot"
    :description "Read the isolated browser's accessibility tree."
    :parameters {:type "object" :properties {}}}
   {:name "browser_open"
    :description "Open a URL in the isolated visible browser."
    :parameters {:type "object"
                 :properties {:url {:type "string"}}
                 :required ["url"]}}
   {:name "browser_click"
    :description "Click an element reference such as @e12."
    :parameters {:type "object"
                 :properties {:ref {:type "string"}}
                 :required ["ref"]}}
   {:name "browser_type"
    :description "Type non-secret text into an element reference."
    :parameters {:type "object"
                 :properties {:ref {:type "string"} :text {:type "string"}}
                 :required ["ref" "text"]}}
   {:name "browser_press"
    :description "Press one browser key such as Enter, Tab, Escape, or Control+a."
    :parameters {:type "object"
                 :properties {:key {:type "string"}}
                 :required ["key"]}}
   {:name "browser_scroll"
    :description "Scroll the isolated browser."
    :parameters {:type "object"
                 :properties {:direction {:type "string" :enum ["up" "down"]}
                              :pixels {:type "integer"}}
                 :required ["direction"]}}])

(def ^:private computer-tools
  "The desktop tools, after ADR-0059 replaced the ones that took the cursor.

  Three names are gone and none of them was renamed: `computer_click`,
  `computer_key` and `computer_type` drove whatever application happened to be
  in front, using `cliclick` and `osascript` keystrokes. They are not here as
  focus-free versions of themselves, because two of them cannot exist in that
  form -- synthesised key events are measured not to reach a background
  application at all -- and the third was only ever a screen coordinate, which
  addresses whatever moved into that spot rather than the thing the model saw.

  What replaces them addresses elements and menu commands by name. That is why
  every write here takes `expect`: the tree the person approved is hashed, and
  the call refuses if the application changed underneath it."
  [{:name "computer_tree"
    :description (str "Read a macOS application's accessibility tree WITHOUT bringing it "
                      "to the front. Returns elements with refs like @a12 and a digest; "
                      "quote that digest back as `expect` on any write.")
    :parameters {:type "object"
                 :properties {:application {:type "string"
                                            :description "Application name, or bundle id such as com.apple.TextEdit"}
                              :max {:type "integer"}}
                 :required ["application"]}}
   {:name "computer_menu"
    :description (str "List a macOS application's menu commands and their keyboard "
                      "shortcuts. Use this instead of pressing a shortcut: a background "
                      "application does not receive synthesised keys, but it does perform "
                      "the menu command the shortcut stands for.")
    :parameters {:type "object"
                 :properties {:application {:type "string"}
                              :contains {:type "string"}}
                 :required ["application"]}}
   {:name "computer_screenshot"
    :description (str "Capture ONE window of a named application. Not the whole screen: "
                      "everything else on the display belongs to the person, not to this "
                      "run.")
    :parameters {:type "object"
                 :properties {:application {:type "string"}}
                 :required ["application"]}}
   {:name "computer_press"
    :description (str "Perform an element's action (default AXPress) where it stands. "
                      "No cursor moves and the application is not raised.")
    :parameters {:type "object"
                 :properties {:application {:type "string"}
                              :ref {:type "string" :description "Element ref from computer_tree, such as @a12"}
                              :action {:type "string" :description "Defaults to AXPress"}
                              :expect {:type "string" :description "Digest from computer_tree"}}
                 :required ["application" "ref" "expect"]}}
   {:name "computer_menu_press"
    :description "Perform a menu command by its path, such as ファイル>保存."
    :parameters {:type "object"
                 :properties {:application {:type "string"}
                              :path {:type "string"}}
                 :required ["application" "path"]}}
   {:name "computer_set_value"
    :description (str "Write text into a text element by ref. Reports what it observes "
                      "afterwards -- some document applications accept the write into the "
                      "widget without marking the document edited.")
    :parameters {:type "object"
                 :properties {:application {:type "string"}
                              :ref {:type "string"}
                              :text {:type "string"}
                              :expect {:type "string"}}
                 :required ["application" "ref" "text" "expect"]}}
   {:name "computer_scroll"
    :description "Scroll a scroll area by one page, through its accessibility action."
    :parameters {:type "object"
                 :properties {:application {:type "string"}
                              :ref {:type "string"}
                              :direction {:type "string" :enum ["up" "down"]}
                              :expect {:type "string"}}
                 :required ["application" "ref" "direction" "expect"]}}])

(def ^:private done-tool
  {:name "done"
   :description "Finish the bounded task and explain the verified result."
   :parameters {:type "object"
                :properties {:text {:type "string"}
                             :success {:type "boolean"}}
                :required ["text"]}})

(def ^:private local-query-tool
  {:name "local_datalog_query"
   :description (str "Query this device's current local EDN datom projection. "
                     "The query is Datomic/DataScript-compatible EDN and never runs remotely.")
   :parameters {:type "object"
                :properties {:query {:type "string"
                                     :description "EDN query vector containing :find and :where"}}
                :required ["query"]}})

(def ^:private read-only-tools
  #{"browser_snapshot" "local_datalog_query"
    "computer_tree" "computer_menu" "computer_screenshot"})

(defn- now-ms [] (System/currentTimeMillis))

(defn- defaults [configuration]
  (get configuration :agent-control {}))

(defn settings [configuration]
  (let [saved (get-in (store/snapshot) [:agent-control :settings] {})]
    (-> (defaults configuration)
        (merge (select-keys saved [:enabled? :max-turns :max-tool-calls]))
        (update :browser merge (:browser saved))
        (update :computer merge (:computer saved))
        (update :cli merge (:cli saved)))))

(defn- clean-domains [domains]
  (->> domains
       (map str)
       (map str/lower-case)
       (map str/trim)
       (filter #(re-matches #"[a-z0-9.-]+" %))
       distinct
       (take 24)
       vec))

(defn configure! [configuration request]
  (let [current (settings configuration)
        workspace (some-> (get-in request [:cli :workspace])
                          str str/trim not-empty)
        workspace (when workspace
                    (let [directory (.getCanonicalFile (io/file workspace))]
                      (when-not (.isDirectory directory)
                        (throw (ex-info "Coding Agent workspace が存在しません。"
                                        {:type :cli-agent/invalid-workspace})))
                      (.getPath directory)))
        access (keyword (or (get-in request [:cli :access]) "read-only"))
        _ (when-not (contains? #{:read-only :workspace-write} access)
            (throw (ex-info "Coding Agent access が不正です。"
                            {:type :cli-agent/invalid-access})))
        next-settings
        (-> current
            (assoc :enabled? (true? (:enabled? request)))
            (assoc-in [:browser :enabled?]
                      (true? (get-in request [:browser :enabled?])))
            (assoc-in [:browser :headed?] true)
            (assoc-in [:browser :allowed-domains]
                      (let [domains (clean-domains
                                     (get-in request [:browser :allowed-domains]))]
                        (if (seq domains) domains ["localhost" "127.0.0.1"])))
            (assoc-in [:computer :enabled?]
                      (true? (get-in request [:computer :enabled?])))
            (assoc-in [:cli :enabled?]
                      (true? (get-in request [:cli :enabled?])))
            (assoc-in [:cli :workspace] workspace)
            (assoc-in [:cli :access] access))]
    (store/update-agent-control! assoc :settings next-settings)
    (store/update-agent-control!
     update :events #(vec (take-last 200
                                    (conj (or % [])
                                          {:type :agent/settings-changed
                                           :at (store/now)
                                           :enabled? (:enabled? next-settings)
                                           :browser? (get-in next-settings
                                                             [:browser :enabled?])
                                           :computer? (get-in next-settings
                                                              [:computer :enabled?])
                                           :cli? (get-in next-settings
                                                         [:cli :enabled?])}))))
    next-settings))

(defn- executable-path [name]
  (some #(let [candidate (io/file % name)]
           (when (and (.isFile candidate) (.canExecute candidate))
             (.getCanonicalPath candidate)))
        (distinct
         (concat (str/split (or (System/getenv "PATH") "") #":")
                 ;; A Finder/LaunchAgent process often has a deliberately
                 ;; narrow PATH. These are installation locations, not a
                 ;; request to run an arbitrary shell command.
                 ["/opt/homebrew/bin" "/usr/local/bin"]))))

(defn- browser-executable []
  (let [override (some-> (System/getenv "CLOUD_ITONAMI_AGENT_BROWSER")
                         str str/trim not-empty)]
    (if override
      (let [candidate (io/file override)]
        (when (and (.isFile candidate) (.canExecute candidate))
          (.getCanonicalPath candidate)))
      (executable-path "agent-browser"))))

(defn diagnostics [configuration]
  (let [s (settings configuration)]
    {:platform (System/getProperty "os.name")
     :enabled? (:enabled? s)
     :browser {:enabled? (get-in s [:browser :enabled?])
               :host "kotoba-lang/browser-use compatible agent-browser"
               :available? (boolean (browser-executable))
               :executable (browser-executable)
               :allowed-domains (get-in s [:browser :allowed-domains])}
     :computer (let [state (desktop/available?)]
                 (merge {:enabled? (get-in s [:computer :enabled?])
                         :host "cloud-itonami focus-free desktop helper (ADR-0059)"
                         ;; Three facts, not one. A missing binary, a missing
                         ;; Accessibility grant and a missing Screen Recording
                         ;; grant need three different answers from a person,
                         ;; and folding them into one `available?` was how the
                         ;; settings screen used to say "unavailable" to
                         ;; somebody whose only problem was one checkbox.
                         :available? (and (:helper? state) (:accessibility? state))
                         :permissions ["Accessibility" "Screen Recording"]}
                        state))
     :cli {:enabled? (get-in s [:cli :enabled?])
           :workspace (get-in s [:cli :workspace])
           :access (get-in s [:cli :access])
           :runners (mapv #(select-keys % [:id :name :runner :model
                                          :binary :available?])
                          (cli-runner/profiles))}}))

(defn- run-command! [args timeout-seconds env]
  (let [builder (doto (ProcessBuilder. ^java.util.List (vec args))
                  (.redirectErrorStream true))
        environment (.environment builder)
        _ (doseq [[key value] env] (.put environment key value))
        process (.start builder)
        completed? (.waitFor process timeout-seconds TimeUnit/SECONDS)]
    (when-not completed?
      (.destroyForcibly process)
      (throw (ex-info "端末操作がタイムアウトしました。"
                      {:type :agent/host-timeout})))
    (let [output (slurp (.getInputStream process))
          output (subs output 0 (min max-output (count output)))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "端末ホストが操作を完了できませんでした。"
                        {:type :agent/host-error :output output})))
      output)))

(defn- browser-command! [& args]
  (run-command!
   (into [(or (browser-executable)
              (throw (ex-info "agent-browser がインストールされていません。"
                              {:type :agent/browser-unavailable})))] args)
   45 {"AGENT_BROWSER_SESSION" (or *browser-session* default-session-name)
       "AGENT_BROWSER_HEADED" "true"}))

(defn- allowed-url! [settings value]
  (let [uri (URI/create (str value))
        scheme (some-> (.getScheme uri) str/lower-case)
        host (some-> (.getHost uri) str/lower-case)
        allowed (set (get-in settings [:browser :allowed-domains]))]
    (when-not (and (#{"http" "https"} scheme)
                   host
                   (some #(or (= host %) (str/ends-with? host (str "." %)))
                         allowed))
      (throw (ex-info "URL は許可ドメイン外です。Settings で明示的に追加してください。"
                      {:type :agent/domain-denied :host host})))
    (str uri)))

(defn- element-ref! [value]
  (let [value (str value)]
    (when-not (re-matches #"@e[0-9]+" value)
      (throw (ex-info "browser element は snapshot の @e番号で指定してください。"
                      {:type :agent/invalid-element})))
    value))

(defn- guarded-browser! [settings & args]
  (let [output (apply browser-command! args)
        current-url (str/trim (browser-command! "get" "url"))]
    (allowed-url! settings current-url)
    (str output
         (when-not (str/includes? output current-url)
           (str "\nCurrent URL: " current-url)))))

(defn- short-text! [value limit label]
  (let [value (str value)]
    (when (or (str/blank? value) (> (count value) limit))
      (throw (ex-info (str label " が空、または長すぎます。")
                      {:type :agent/invalid-input :field label})))
    value))

(defn- application!
  "The target application, as a name or a bundle id.

  Bundle ids are the reliable form and the tool description says so: this
  machine is Japanese-localized, where TextEdit answers to テキストエディット
  and not to `TextEdit`. The helper reports what IS running when the name does
  not resolve, so a model can correct itself in one turn."
  [value]
  (short-text! value 120 "application"))

(defn- desktop-ref!
  "An element reference from `computer_tree`, in the shape the walk produces."
  [value]
  (let [value (str value)]
    (when-not (re-matches #"@a[0-9]+" value)
      (throw (ex-info "element は computer_tree の @a番号で指定してください。"
                      {:type :agent/invalid-element})))
    value))

(defn- digest!
  "The tree digest a write is bound to.

  Required, not optional. This is what took over from `require-frontmost!`, and
  an optional guard is not a guard -- the model would omit it exactly when the
  screen was busiest."
  [value]
  (let [value (str value)]
    (when-not (re-matches #"sha256:[0-9a-f]{64}" value)
      (throw (ex-info "expect には computer_tree が返した digest を渡してください。"
                      {:type :agent/invalid-digest})))
    value))

(defn- execute-tool! [configuration name input]
  (let [s (settings configuration)]
    (case name
      "browser_snapshot"
      (guarded-browser! s "snapshot" "-i" "--urls")

      "browser_open"
      (guarded-browser! s "open" (allowed-url! s (:url input)))

      "browser_click"
      (guarded-browser! s "click" (element-ref! (:ref input)))

      "browser_type"
      (guarded-browser! s "type" (element-ref! (:ref input))
                        (short-text! (:text input) 4000 "text"))

      "browser_press"
      (guarded-browser! s "press" (short-text! (:key input) 80 "key"))

      "browser_scroll"
      (guarded-browser! s "scroll"
                        (if (= "up" (:direction input)) "up" "down")
                        (str (min 4000 (max 100 (long (or (:pixels input) 700))))))

      "computer_tree"
      (pr-str (desktop/tree (application! (:application input))
                            {:max (:max input)}))

      "computer_menu"
      (pr-str (desktop/menu (application! (:application input))
                            {:contains (:contains input)}))

      "computer_screenshot"
      (desktop/screenshot! (application! (:application input)))

      "computer_press"
      (pr-str (desktop/press! (application! (:application input))
                              (desktop-ref! (:ref input))
                              (digest! (:expect input))
                              {:action (some-> (:action input) str not-empty)}))

      "computer_menu_press"
      (pr-str (desktop/menu-press! (application! (:application input))
                                   (short-text! (:path input) 300 "path")))

      "computer_set_value"
      (pr-str (desktop/set-value! (application! (:application input))
                                  (desktop-ref! (:ref input))
                                  (short-text! (:text input) 4000 "text")
                                  (digest! (:expect input))
                                  {}))

      "computer_scroll"
      (pr-str (desktop/scroll! (application! (:application input))
                               (desktop-ref! (:ref input))
                               (if (= "up" (:direction input)) "up" "down")
                               (digest! (:expect input))
                               {}))

      "cli_agent"
      (let [selected (policy/select-provider configuration (:provider input))
            expected (get-in s [:cli :workspace])
            requested (.getCanonicalPath (io/file (:workspace input)))]
        (when-not (and selected (= :cli (:kind selected)))
          (throw (ex-info "CLI provider は許可されていません。"
                          {:type :provider/denied})))
        (when-not (and (get-in s [:cli :enabled?])
                       expected (= requested expected))
          (throw (ex-info "承認後にCoding Agent workspace設定が変わりました。"
                          {:type :cli-agent/workspace-changed})))
        (cli-runner/run-agent!
         selected {:prompt (:goal input) :model (:model input)
                   :cwd requested :access (keyword (:access input))}))

      "local_datalog_query"
      (pr-str (local-query/query-state (store/snapshot) (:query input)))

      (throw (ex-info "未知の端末toolです。" {:type :agent/unknown-tool
                                            :tool name})))))

(defn- available-tools [configuration]
  (let [s (settings configuration)]
    (cond-> [done-tool local-query-tool]
      (get-in s [:browser :enabled?]) (into browser-tools)
      (get-in s [:computer :enabled?]) (into computer-tools))))

(defn- capability-for [tool-name]
  (cond
    (str/starts-with? tool-name "browser_") :browser/use
    (str/starts-with? tool-name "computer_") :computer/use
    (= "cli_agent" tool-name) :workspace/use
    :else :agent/run))

(defn- approval-summary [tool-name input]
  (let [preview (fn [value]
                  (let [value (str value)]
                    (if (> (count value) 100)
                      (str (subs value 0 100) "…")
                      value)))]
    (case tool-name
      "browser_open" (str "分離ブラウザーで " (preview (:url input)) " を開きます。")
      "browser_click" (str "分離ブラウザーの " (:ref input) " をクリックします。")
      "browser_type" (str (:ref input) " に「" (preview (:text input)) "」と入力します。")
      "browser_press" (str "分離ブラウザーで " (:key input) " キーを押します。")
      "browser_scroll" (str "分離ブラウザーを " (:direction input) " にスクロールします。")
      "computer_tree" (str (:application input) " のアクセシビリティツリーを読みます。")
      "computer_menu" (str (:application input) " のメニュー項目を読みます。")
      "computer_screenshot" (str (:application input) " のウインドウを1枚キャプチャします。")
      "computer_press" (str (:application input) " の " (:ref input) " に "
                            (or (:action input) "AXPress") " を実行します（前面化しません）。")
      "computer_menu_press" (str (:application input) " のメニュー "
                                 (preview (:path input)) " を実行します（前面化しません）。")
      "computer_set_value" (str (:application input) " の " (:ref input)
                                " に「" (preview (:text input)) "」を書き込みます。")
      "computer_scroll" (str (:application input) " の " (:ref input)
                             " を " (:direction input) " にスクロールします。")
      "cli_agent" (str (:provider input) " を " (:access input)
                       " で " (preview (:workspace input)) " に実行します。")
      (str tool-name " を実行します。"))))

(defn browser-enabled?
  "Whether THIS MACHINE's isolated browser is on.

  Independent of any Bot's `:bot/browser?`. A Bot that asked for the browser
  on a deployment that never enabled it must not silently grow tools — the
  field stays, the tools do not appear, and the screen can say why."
  [configuration]
  (boolean (get-in (settings (or configuration {})) [:browser :enabled?])))

(defn computer-enabled?
  "Whether THIS MACHINE admits focus-free macOS computer tools.

  This is only the deployment half of the gate. A Bot must also carry its own
  `:bot/computer?` grant before any tool is shown to its model."
  [configuration]
  (boolean (get-in (settings (or configuration {})) [:computer :enabled?])))

(defn computer-ready?
  "Machine admission plus the concrete helper and Accessibility grant."
  [configuration]
  (let [state (desktop/available?)]
    (boolean (and (computer-enabled? configuration)
                  (:helper? state) (:accessibility? state)))))

(defn browser-tool?
  "Is this an isolated-browser tool name, and not a computer or connector one?"
  [tool-name]
  (str/starts-with? (str tool-name) "browser_"))

(defn browser-write?
  "`browser_snapshot` reads. Everything else changes the page, so a Bot turn
  holds it the same way it holds a Gmail send."
  [tool-name]
  (and (browser-tool? tool-name)
       (not (contains? read-only-tools (str tool-name)))))

(defn computer-tool? [tool-name]
  (str/starts-with? (str tool-name) "computer_"))

(defn computer-write? [tool-name]
  (and (computer-tool? tool-name)
       (not (contains? read-only-tools (str tool-name)))))

(defn browser-tool-definitions
  "The isolated-browser tools as a model sees them, or none.

  Computer tools are not on this list. A Bot's `:bot/browser?` opts into the
  isolated browser, not into keystrokes on the frontmost app; those stay on
  agent-control's own loop."
  [configuration]
  (if (browser-enabled? configuration)
    (mapv (fn [t]
            (cond-> t
              (browser-write? (:name t))
              (update :description #(str % " (write)"))))
          browser-tools)
    []))

(defn computer-tool-definitions
  "The focus-free computer tools as a Bot model sees them, or none.

  No coordinate click, synthetic key or free-form typing tool exists. Writes
  remain bound to the digest returned by `computer_tree`."
  [configuration]
  (if (computer-ready? configuration)
    (mapv (fn [t]
            (cond-> t
              (computer-write? (:name t))
              (update :description #(str % " (write; requires current tree digest)"))))
          computer-tools)
    []))

(defn describe-browser-tool
  "What an approval card should say about one browser call."
  [tool-name input]
  (approval-summary (str tool-name) (or input {})))

(defn describe-computer-tool [tool-name input]
  (approval-summary (str tool-name) (or input {})))

(defn call-browser-tool!
  "Run one isolated-browser tool as a named principal.

  The profile is `session-for`, so two Bots do not share cookies. This is the
  seam ADR-0034 left unwired: the field existed, the profile name existed, and
  nothing in a Bot turn called through."
  [configuration principal-id tool-name input]
  (let [name (str tool-name)]
    (when-not (browser-tool? name)
      (throw (ex-info "browser tool ではありません。"
                      {:type :agent/unknown-tool :tool name})))
    (when-not (browser-enabled? configuration)
      (throw (ex-info "分離ブラウザーは有効ではありません。"
                      {:type :agent/browser-disabled :tool name})))
    (binding [*browser-session* (session-for principal-id)]
      (execute-tool! (or configuration {}) name (or input {})))))

(defn call-computer-tool!
  "Run one focus-free computer tool after both machine and Bot admission.

  The Bot admission is checked by `bots/tool-definitions`; this seam repeats
  the machine check so a stale model call cannot cross a Settings change."
  [configuration tool-name input]
  (let [name (str tool-name)]
    (when-not (computer-tool? name)
      (throw (ex-info "computer tool ではありません。"
                      {:type :agent/unknown-tool :tool name})))
    (when-not (computer-ready? configuration)
      (throw (ex-info "Computer Use は有効ではありません。"
                      {:type :agent/computer-disabled :tool name})))
    (execute-tool! (or configuration {}) name (or input {}))))

(defn- approval [run-id tool-name input]
  (hil/approval-request
   {:id (str run-id ":" tool-name)
    :title (if (= "cli_agent" tool-name)
             "Coding Agent の実行確認"
             "Cloud Itonami Agent の端末操作")
    :summary (approval-summary tool-name input)
    :action tool-name
    :impact (cond
              (= "cli_agent" tool-name)
              (if (= "workspace-write" (:access input))
                "指定workspace内のファイルを読み書きできます。shell bypassは許可しません。"
                "指定workspaceを読み取れますが、ファイル変更は許可しません。")
              (str/starts-with? tool-name "computer_")
              (str "指定したアプリの状態が変わる可能性があります。カーソル・キーボードフォーカス・"
                   "Spaceは動きません（ADR-0059）。承認時のツリーから変化していれば実行は拒否されます。")
              :else
              "分離されたブラウザー上のページ状態が変わる可能性があります。")}))

(defn- public-run [run]
  (-> run
      (dissoc :agent/messages :agent/pending-call)
      (update :agent/approval
              #(when % (select-keys % [:id :title :summary :action :impact])))
      (select-keys [:agent.run/id :agent.run/goal :agent.run/status
                    :agent.run/yakuwari :agent.run/work-item
                    :agent.run/actor :agent/executor :agent/provider-id
                    :agent/model
                    :agent.run/required-capabilities :agent.run/budget
                    :agent.run/created-at :agent.run/updated-at
                    :agent/result :agent/error :agent/approval
                    :agent/tool-count :agent/turn-count])))

(defn runs []
  (->> (vals (get-in (store/snapshot) [:agent-control :runs] {}))
       (sort-by :agent.run/created-at >)
       (mapv public-run)))

(defn run-by-id [run-id]
  (some-> (get-in (store/snapshot) [:agent-control :runs run-id]) public-run))

(declare transition save-run!)

(defn record-dispatch-failure!
  "Persist a dispatch failure as a real terminal AgentRun. Repeating the same
  id is idempotent and returns the first durable outcome."
  [run-id {:keys [goal yakuwari work-item actor]} error]
  (if-let [existing (run-by-id run-id)]
    existing
    (let [queued (cond->
                  (agent-run/agent-run
                   {:id run-id :goal goal :mode :local :actor actor
                    :capabilities #{} :budget {:max-turns 1 :max-tool-calls 1}}
                   (now-ms))
                   yakuwari (assoc :agent.run/yakuwari yakuwari)
                   work-item (assoc :agent.run/work-item work-item))
          failed (-> queued
                     (transition :leased {})
                     (transition :running {})
                     (transition :failed
                                 {:agent/error
                                  {:type (or (:type (ex-data error))
                                             :dispatch/error)
                                   :message (.getMessage error)}}))]
      (save-run! failed)
      (public-run failed))))

(defn record-external-admission!
  "Persist an externally supervised execution after its intent inbox returns an
  admission receipt. Admission is held, never success."
  [run-id {:keys [goal yakuwari work-item actor]} admission-receipt]
  (if-let [existing (run-by-id run-id)]
    existing
    (let [queued (cond->
                  (agent-run/agent-run
                   {:id run-id :goal goal :mode :remote :actor actor
                    :capabilities #{} :budget {}}
                   (now-ms))
                   yakuwari (assoc :agent.run/yakuwari yakuwari)
                   work-item (assoc :agent.run/work-item work-item))
          held (-> queued
                   (transition :leased {})
                   (transition :running
                               {:agent/executor :organism-worker})
                   (transition :held
                               {:agent/result admission-receipt
                                :agent/external-receipt admission-receipt}))]
      (save-run! held)
      (public-run held))))

(defn record-external-outcome!
  "Advance a held external AgentRun from a supervisor receipt."
  [run-id status result]
  (let [run (get-in (store/snapshot) [:agent-control :runs run-id])]
    (when-not run
      (throw (ex-info "external AgentRun was not found"
                      {:type :agent/not-found :run-id run-id})))
    (if (= status (:agent.run/status run))
      (public-run run)
      (let [running (if (= :held (:agent.run/status run))
                      (-> run (transition :leased {})
                          (transition :running {}))
                      run)
            finished (transition running status
                                 (if (= :failed status)
                                   {:agent/error result}
                                   {:agent/result result}))]
        (save-run! finished)
        (public-run finished)))))

(defn- save-run! [run]
  (store/update-agent-control! assoc-in [:runs (:agent.run/id run)] run)
  run)

(defn- remember-finished-run! [run]
  ;; The AgentRun is already durably succeeded. Chronicle is optional and may
  ;; never rewrite that outcome when its own persistence is unavailable.
  (try
    (chronicle/remember-tool! (:agent.run/actor run)
                              (:agent.run/goal run)
                              (:agent/result run))
    (catch Exception _ nil))
  run)

(defn- transition [run status attrs]
  (agent-run/transition run status (now-ms) attrs))

(defn- append-audit! [run tool-name decision]
  (store/update-agent-control!
   update :events
   #(vec (take-last 200
                    (conj (or % [])
                          {:type :agent/tool-decision
                           :at (store/now)
                           :run-id (:agent.run/id run)
                           :tool tool-name
                           :capability (capability-for tool-name)
                           :decision decision})))))

(declare advance!)

(defn- fail! [run error]
  (let [failed (transition run :failed
                           {:agent/error {:message (.getMessage error)
                                          :type (some-> error ex-data :type)}})]
    (save-run! failed)
    (public-run failed)))

(defn- model-turn [configuration run]
  (let [selected (policy/select-provider
                  configuration (:agent/provider-id run))]
    (when-not selected
      (throw (ex-info "選択したmodel providerは許可されていません。"
                      {:type :provider/denied})))
    (provider/agent-turn
     selected
     {:model (:agent/model run)
      :messages (:agent/messages run)
      :tools (available-tools configuration)
      :temperature 0.2})))

(defn- add-tool-result [run call result]
  (-> run
      (update :agent/messages conj
              {:role "tool" :tool-call-id (:id call)
               :name (:name call) :content result})
      (update :agent/tool-count (fnil inc 0))
      (assoc :agent/pending-call nil :agent/approval nil)))

(defn advance! [configuration run]
  (try
    (loop [run run]
      (let [budget (:agent.run/budget run)]
        (when (>= (:agent/turn-count run 0) (:max-turns budget))
          (throw (ex-info "AgentRun の turn budget に到達しました。"
                          {:type :agent/budget-exhausted})))
        (when (>= (:agent/tool-count run 0) (:max-tool-calls budget))
          (throw (ex-info "AgentRun の tool budget に到達しました。"
                          {:type :agent/budget-exhausted})))
        (let [result (model-turn configuration run)
              calls (:tool-calls result)
              run (-> run
                      (update :agent/turn-count (fnil inc 0))
                      (update :agent/messages conj
                              {:role "assistant" :content (:content result)
                               :tool-calls calls}))]
          (cond
            (empty? calls)
            (let [finished (transition run :succeeded
                                       {:agent/result (:content result)})]
              (save-run! finished)
              (remember-finished-run! finished)
              (public-run finished))

            (> (count calls) 1)
            (throw (ex-info "一度に複数の端末操作は実行できません。"
                            {:type :agent/multiple-tool-calls}))

            :else
            (let [{:keys [name input] :as call} (first calls)]
              (if (= "done" name)
                (let [finished (transition run :succeeded
                                           {:agent/result (or (:text input)
                                                              (:content result))})]
                  (save-run! finished)
                  (remember-finished-run! finished)
                  (public-run finished))
                (if (contains? read-only-tools name)
                  (let [output (execute-tool! configuration name input)
                        run (add-tool-result run call output)]
                    (append-audit! run name :auto-read)
                    (save-run! run)
                    (recur run))
                  (let [held (transition
                              run :held
                              {:agent/pending-call call
                               :agent/approval
                               (approval (:agent.run/id run) name input)})]
                    (append-audit! held name :pending)
                    (save-run! held)
                    (public-run held)))))))))
    (catch Exception error
      (fail! (get-in (store/snapshot)
                     [:agent-control :runs (:agent.run/id run)]
                     run)
             error))))

(defn- create-cli-run!
  [configuration {:keys [id goal model provider yakuwari work-item]} actor]
  (let [s (settings configuration)
        cli (:cli s)
        selected (policy/select-provider configuration provider)
        workspace (:workspace cli)
        access (keyword (or (:access cli) :read-only))]
    (when-not (and (:enabled? s) (:enabled? cli))
      (throw (ex-info "Coding Agent はSettingsで無効です。"
                      {:type :cli-agent/disabled})))
    (when-not (and selected (= :cli (:kind selected)))
      (throw (ex-info "CLI provider は許可されていません。"
                      {:type :provider/denied})))
    (when-not (and workspace (.isDirectory (io/file workspace)))
      (throw (ex-info "Coding Agent workspace をSettingsで設定してください。"
                      {:type :cli-agent/workspace-required})))
    (let [call {:id (str "cli-" (UUID/randomUUID))
                :name "cli_agent"
                :input {:goal goal :model model :provider (:id selected)
                        :workspace workspace :access (name access)}}
          queued (cond->
                  (agent-run/agent-run
                   {:id id :goal goal :mode :local :model model :actor actor
                    :capabilities #{:workspace/use}
                    :budget {:max-turns 1 :max-tool-calls 1}}
                   (now-ms))
                   yakuwari (assoc :agent.run/yakuwari yakuwari)
                   work-item (assoc :agent.run/work-item work-item))
          held (-> queued
                   (transition :leased {})
                   (transition :running
                               {:agent/provider-id (:id selected)
                                :agent/model model
                                :agent/executor :cli
                                :agent/turn-count 0
                                :agent/tool-count 0})
                   (transition :held
                               {:agent/pending-call call
                                :agent/approval
                                (approval (:agent.run/id queued)
                                          "cli_agent" (:input call))}))]
      (append-audit! held "cli_agent" :pending)
      (save-run! held)
      (public-run held))))

(defn create-run!
  [configuration {:keys [id goal model provider mode yakuwari work-item]
                  :as request} actor]
  (if-let [existing (and id (run-by-id id))]
    existing
    (if (= "cli" mode)
      (create-cli-run! configuration request actor)
      (let [s (settings configuration)]
    (when-not (:enabled? s)
      (throw (ex-info "端末AgentはSettingsで無効です。"
                      {:type :agent/disabled})))
    (when-not (or (get-in s [:browser :enabled?])
                  (get-in s [:computer :enabled?]))
      (throw (ex-info "browser または computer capability を有効にしてください。"
                      {:type :agent/no-capability})))
    (let [configuration-provider
          (or provider (get-in configuration [:routing :default-provider]))
          configuration-model
          (or model (get-in configuration [:routing :default-model]))
          capabilities
          (cond-> #{}
            (get-in s [:browser :enabled?]) (conj :browser/use)
            (get-in s [:computer :enabled?]) (conj :computer/use))
          queued
          (cond->
           (agent-run/agent-run
            {:id id :goal goal :mode :local :model configuration-model
             :actor actor :capabilities capabilities
             :budget {:max-turns (:max-turns s)
                      :max-tool-calls (:max-tool-calls s)}}
            (now-ms))
            yakuwari (assoc :agent.run/yakuwari yakuwari)
            work-item (assoc :agent.run/work-item work-item))
          running
          (-> queued
              (transition :leased {})
              (transition :running
                          {:agent/provider-id configuration-provider
                           :agent/model configuration-model
                           :agent/messages
                           [{:role "system"
                             :content
                             (str "You are Cloud Itonami's bounded local-device agent. "
                                  "Use exactly one tool per turn. Inspect before acting. "
                                  "Never request, reveal, or type a password, token, MFA, "
                                  "or other secret. Use done only after verification.")}
                            {:role "user" :content (str "Task: " goal)}]
                           :agent/turn-count 0
                           :agent/tool-count 0}))]
        (save-run! running)
        (advance! configuration running))))))

(defn decide! [configuration run-id decision]
  (let [run (get-in (store/snapshot) [:agent-control :runs run-id])]
    (when-not run
      (throw (ex-info "AgentRun が見つかりません。" {:type :agent/not-found})))
    (when-not (= :held (:agent.run/status run))
      (throw (ex-info "このAgentRunは承認待ちではありません。"
                      {:type :agent/not-held})))
    (let [call (:agent/pending-call run)
          decision (keyword decision)]
      (when-not (contains? hil/decisions decision)
        (throw (ex-info "承認判断が不正です。" {:type :agent/invalid-decision})))
      (append-audit! run (:name call) decision)
      (if (= :approved decision)
        (try
          (let [running (-> run
                            (transition :leased {})
                            (transition :running {}))
                output (execute-tool! configuration (:name call) (:input call))
                continued (add-tool-result running call output)]
            (if (= "cli_agent" (:name call))
              (let [finished
                    (transition continued :succeeded
                                {:agent/result (:content output)})]
                (save-run! finished)
                (remember-finished-run! finished)
                (public-run finished))
              (do
                (save-run! continued)
                (advance! configuration continued))))
          (catch Exception error
            (fail! (-> run
                       (transition :leased {})
                       (transition :running {}))
                   error)))
        (let [rejected (transition run :rejected
                                   {:agent/result "端末操作はユーザーに拒否されました。"
                                    :agent/pending-call nil
                                    :agent/approval nil})]
          (save-run! rejected)
          (public-run rejected))))))
