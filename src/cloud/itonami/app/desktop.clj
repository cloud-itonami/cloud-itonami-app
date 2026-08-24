(ns cloud.itonami.app.desktop
  "The focus-free desktop capability (ADR-0059).

  Production uses the signed CuaDriver.app daemon, so macOS attributes TCC
  grants to one stable application identity instead of the launchd JVM. A small
  reviewed Swift helper remains the source-build fallback. Both are narrowed to
  accessibility element operations here: coordinate clicks, shared keyboard
  events, clipboard access, app launch and app termination are never exposed.

  It replaced a set of tools that drove the FRONTMOST application with
  `osascript` keystrokes and `cliclick`. Those took the cursor and the key
  window away from whoever was using the machine, and their safety rested on
  `require-frontmost!` -- a check that the same application was still in front,
  which is both weaker than it reads (the window could change while the
  application stayed frontmost) and only answerable by depending on focus in
  the first place. What replaced it is `--expect`: the accessibility tree the
  person approved is hashed, and a call refuses if the tree moved underneath
  it. That guard holds whether or not the target is in front, which is the
  whole point.

  Measured limits of the public accessibility API, on macOS 26.3.1,
  2026-08-18 -- these are the reasons this namespace offers what it offers:

    - reading a tree, reading menus, performing an element action and
      performing a menu command all work from the background.
    - synthesised key events do NOT. `CGEvent.postToPid` delivered `cmd+s` and
      eighteen characters of text to a background TextEdit and neither had any
      effect, because AppKit routes key events to the key window and a
      background application has none. There is therefore no `key` and no
      `type`; a shortcut is expressed as the menu command it stands for.
    - setting `AXValue` writes the widget but does not necessarily mark the
      document edited. `set-value!` returns what it observes afterwards so a
      caller can see the difference rather than assume it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config])
  (:import [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(def ^:private max-output 200000)

(def overlay-milliseconds
  "How long the marker stays over an element the agent is acting on.

  Not a parameter of any tool, and that is the design. Acting without taking
  the cursor means acting INVISIBLY: the tools this replaced were at least
  honest by accident, because the pointer jumped and a person could see it. A
  model that could pass `overlay: false` would eventually pass it, and the one
  call where it mattered would be the silent one.

  900ms is long enough to notice and to catch in a screen recording, and it is
  paid once per write -- reads draw nothing."
  900)

(defn- repo-file [& parts]
  (io/file (str/join "/" (cons (or (System/getProperty "user.dir") ".") parts))))

(defn helper-path
  "Where the built helper is, if it is there.

  An explicit environment variable wins so a packaged application can ship the
  binary somewhere other than a source tree."
  []
  (let [override (System/getenv "CLOUD_ITONAMI_DESKTOP_HELPER")
        candidate (if (str/blank? (str override))
                    (repo-file "target" "cloud-itonami-desktop-macos")
                    (io/file override))]
    (when (and (.isFile candidate) (.canExecute candidate))
      (.getCanonicalPath candidate))))

(defn cua-driver-path
  "Resolve the official signed CuaDriver CLI without depending on launchd PATH."
  []
  (let [override (System/getenv "CLOUD_ITONAMI_CUA_DRIVER")
        home (System/getProperty "user.home")
        candidates (remove str/blank?
                           [override
                            (str home "/.local/bin/cua-driver")
                            "/Applications/CuaDriver.app/Contents/MacOS/cua-driver"
                            "/opt/homebrew/bin/cua-driver"
                            "/usr/local/bin/cua-driver"])]
    (some (fn [path]
            (let [file (io/file path)]
              (when (and (.isFile file) (.canExecute file))
                (.getCanonicalPath file))))
          candidates)))

(defn- macos? []
  (str/includes? (str/lower-case (str (System/getProperty "os.name"))) "mac"))

(defn- exec! [args timeout-seconds]
  (let [builder (ProcessBuilder. ^java.util.List (vec args))
        process (.start builder)
        stdout (future (slurp (.getInputStream process)))
        stderr (future (slurp (.getErrorStream process)))
        completed? (.waitFor process timeout-seconds TimeUnit/SECONDS)]
    (when-not completed?
      (.destroyForcibly process)
      (throw (ex-info "デスクトップ操作がタイムアウトしました。"
                      {:type :desktop/timeout})))
    {:exit (.exitValue process)
     :out (let [s @stdout] (subs s 0 (min max-output (count s))))
     :err (let [s @stderr] (subs s 0 (min max-output (count s))))}))

(defn build!
  "Compile the helper once, from source in this checkout.

  Returns its path, or nil when this machine cannot build it. Deliberately not
  called from a tool path -- `swiftc` on the first agent call would look like a
  hang. Callers that want it present ask for it at startup or from settings."
  []
  (or (helper-path)
      (let [script (repo-file "bin" "cloud-itonami-build-desktop-macos")]
        (when (and (macos?) (.isFile script))
          (let [{:keys [exit out]} (exec! [(.getCanonicalPath script)] 180)]
            (when (zero? exit)
              (let [path (str/trim (last (str/split-lines out)))]
                (when (.isFile (io/file path)) path))))))))

(defn- cua-call!
  [tool input timeout-seconds]
  (let [driver (or (cua-driver-path)
                   (throw (ex-info "CuaDriverがインストールされていません。"
                                   {:type :desktop/cua-driver-missing})))
        payload (json/write-str input)
        {:keys [exit out err]} (exec! [driver "call" tool payload] timeout-seconds)]
    (if (zero? exit)
      (json/read-str out :key-fn keyword)
      (let [body (try (json/read-str (if (str/blank? err) out err) :key-fn keyword)
                      (catch Exception _ nil))]
        (throw (ex-info (or (:message body) "CuaDriverの操作に失敗しました。")
                        (merge {:type :desktop/cua-driver-error
                                :tool tool
                                :detail (str/trim (if (str/blank? err) out err))}
                               (dissoc body :message))))))))

(defn- cua-permissions []
  (when (cua-driver-path)
    (try
      (let [body (cua-call! "check_permissions" {:prompt false} 20)]
        {:helper? true
         :provider :cua-driver
         :accessibility? (true? (:accessibility body))
         :screen-recording? (true? (:screen-recording body))
         :source (:source body)})
      (catch Exception e
        {:helper? true
         :provider :cua-driver
         :accessibility? false
         :screen-recording? false
         :detail (.getMessage e)}))))

(defn available?
  "Is the focus-free capability actually usable right now?

  Three separate facts, because a missing binary, a missing Accessibility grant
  and a missing Screen Recording grant need three different answers from a
  person. Reported rather than prompted for."
  []
  (or (cua-permissions)
      (if-let [helper (helper-path)]
        (let [{:keys [exit out]} (exec! [helper "permissions"] 20)]
          (if (zero? exit)
            (let [body (json/read-str out :key-fn keyword)]
              {:helper? true
               :provider :source-helper
               :accessibility? (true? (:accessibility body))
               :screen-recording? (true? (:screen-recording body))})
            {:helper? true :provider :source-helper
             :accessibility? false :screen-recording? false}))
        {:helper? false :provider :none
         :accessibility? false :screen-recording? false})))

(defn request-permissions!
  "Ask macOS for the two grants after a person explicitly chose Settings.

  This is intentionally separate from `available?`: health checks, Bot runs,
  and page loads must never raise a system dialog. The server exposes this only
  from the human-session, same-origin, CSRF-protected preparation route."
  []
  (if-let [driver (cua-driver-path)]
    (let [{:keys [exit out err]} (exec! [driver "permissions" "grant"] 300)]
      (if (zero? exit)
        (assoc (or (try (json/read-str out :key-fn keyword)
                        (catch Exception _ nil))
                   (available?))
               :provider :cua-driver)
        (throw (ex-info "CuaDriverのmacOS権限を確認できませんでした。"
                        {:type :desktop/permission-request-failed
                         :detail (str/trim err)}))))
    (let [helper (or (build!)
                     (throw (ex-info "Computer Use helperを準備できませんでした。"
                                     {:type :desktop/build-failed})))
          {:keys [exit out err]} (exec! [helper "permissions" "--prompt" "true"] 180)]
      (if (zero? exit)
        (assoc (json/read-str out :key-fn keyword) :provider :source-helper)
        (throw (ex-info "macOSのComputer Use権限を確認できませんでした。"
                        {:type :desktop/permission-request-failed
                         :detail (str/trim err)}))))))

(defn- helper! [args timeout-seconds]
  (let [helper (or (helper-path)
                   (throw (ex-info (str "デスクトップヘルパーが未ビルドです。"
                                        "bin/cloud-itonami-build-desktop-macos を実行してください。")
                                   {:type :desktop/helper-missing})))
        {:keys [exit out err]} (exec! (into [helper] (map str args)) timeout-seconds)]
    (if (zero? exit)
      (json/read-str out :key-fn keyword)
      ;; The helper's own error body, not a status code. Its `type` says
      ;; `tree-changed` or `value-not-settable` or `application-not-running`,
      ;; and every one of those is something a person or a model can act on --
      ;; which they cannot do with "the host could not complete the operation".
      (let [body (try (json/read-str err :key-fn keyword) (catch Exception _ nil))]
        (throw (ex-info (or (:message body) "デスクトップ操作に失敗しました。")
                        (merge {:type (keyword "desktop"
                                               (or (:type body) "host-error"))}
                               (dissoc body :ok :type :message))))))))

;; ── reads ───────────────────────────────────────────────────────────────

(def ^:private cua-snapshots (atom {}))

(defn- cua? []
  ;; Admission checked permissions before the tool was offered. Do not repeat
  ;; that multi-process diagnostic on every tree/window/action call.
  (boolean (cua-driver-path)))

(defn- sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str value) "UTF-8"))]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn- cua-app! [application]
  (or (some #(when (and (:running %)
                        (or (= application (:name %))
                            (= application (:bundle_id %))))
               %)
            (:apps (cua-call! "list_apps" {} 20)))
      (throw (ex-info "指定したアプリケーションは実行されていません。"
                      {:type :desktop/application-not-running
                       :application application}))))

(defn- window-area [window]
  (* (double (or (get-in window [:bounds :width]) 0))
     (double (or (get-in window [:bounds :height]) 0))))

(defn- cua-window! [pid]
  (or (->> (:windows (cua-call! "list_windows" {} 20))
           (filter #(and (= pid (:pid %)) (= 0 (:layer %))))
           (sort-by (juxt #(if (:is_on_screen %) 0 1)
                          #(if (str/blank? (str (:title %))) 1 0)
                          (comp - window-area)
                          :z_index))
           first)
      (throw (ex-info "対象アプリケーションのウインドウが見つかりません。"
                      {:type :desktop/window-not-found :pid pid}))))

(defn- digest-elements [elements]
  (mapv #(select-keys % [:element_index :role :label :value :frame
                         :parent_index :depth :enabled :actions])
        elements))

(defn- cua-tree* [application max-elements screenshot-file]
  (let [bounded-max (int (min 400 (max 1 (long (or max-elements 400)))))
        app (cua-app! application)
        window (cua-window! (:pid app))
        input (cond-> {:pid (:pid app)
                       :window_id (:window_id window)
                       :include_screenshot false
                       :max_elements bounded-max}
                screenshot-file (assoc :screenshot_out_file screenshot-file))
        state (cua-call! "get_window_state" input 45)
        elements (mapv #(assoc % :ref (str "@a" (:element_index %))) (:elements state))
        digest (sha256 (digest-elements elements))
        refs (into {} (map (juxt :ref identity)) elements)
        snapshot {:application application :pid (:pid app)
                  :window-id (:window_id window) :window window
                  :max-elements bounded-max
                  :snapshot-id (:snapshot_id state) :digest digest :refs refs}]
    (swap! cua-snapshots assoc application snapshot)
    (assoc (select-keys state [:tree_markdown :element_count :total_element_count
                               :returned_element_count :elements_complete
                               :screenshot_file_path])
           :application application
           :window (:title window)
           :digest digest
           :elements elements)))

(defn- cua-fresh-element! [application element-ref expect]
  (let [previous (get @cua-snapshots application)
        fresh (cua-tree* application (or (:max-elements previous) 400) nil)
        snapshot (get @cua-snapshots application)]
    (when-not (= expect (:digest fresh))
      (throw (ex-info "画面が確認時から変わりました。もう一度読み取ってください。"
                      {:type :desktop/tree-changed
                       :expected expect :actual (:digest fresh)})))
    (let [element (get (:refs snapshot) element-ref)]
      (when-not element
        (throw (ex-info "指定した画面要素が見つかりません。"
                        {:type :desktop/element-not-found :ref element-ref})))
      [snapshot element])))

(defn applications []
  (if (cua?)
    (->> (:apps (cua-call! "list_apps" {} 20))
         (filter :running)
         (mapv #(hash-map :name (:name %) :bundle-id (:bundle_id %)
                          :pid (:pid %) :active (:active %))))
    (:applications (helper! ["apps"] 20))))

(defn tree
  "The target application's accessibility tree, plus the digest that binds it.

  The digest is what a later write quotes back through `:expect`. Callers that
  drop it get a write with no guard at all, so every writing function here
  takes it as a required argument rather than an option."
  [application {:keys [max include-menu?]}]
  (if (cua?)
    (cua-tree* application max nil)
    (helper! (cond-> ["tree" "--app" application]
               max (into ["--max" max])
               include-menu? (into ["--include-menu" "true"]))
             30)))

(defn menu
  "Every menu command the application offers, with the shortcut each one owns.

  The shortcut is reported so a model asking for `cmd+s` can find the command
  that shortcut stands for -- which is the only way to press it from the
  background."
  [application {:keys [contains]}]
  (if (cua?)
    {:commands []
     :application application
     :notice (str "CuaDriverはメニュー一覧を推測しません。"
                  "既知の正確な A>B>C パスは computer_menu_press で実行できます。")}
    (helper! (cond-> ["menu" "--app" application]
               (not (str/blank? (str contains))) (into ["--contains" contains]))
             30)))

(defn windows [application]
  (if (cua?)
    (let [pid (:pid (cua-app! application))]
      (->> (:windows (cua-call! "list_windows" {} 20))
           (filter #(= pid (:pid %)))
           (mapv #(hash-map :id (:window_id %) :title (:title %)
                            :frame [(:x (:bounds %)) (:y (:bounds %))
                                    (:width (:bounds %)) (:height (:bounds %))]
                            :onscreen (:is_on_screen %)))))
    (:windows (helper! ["windows" "--app" application] 20))))

(defn screenshot!
  "Capture the target application's window.

  A whole-screen capture is focus-free too, and that is exactly why the old
  tool used it -- but it hands the model every other window on the display.

  Which of the two capture modes ran is reported, because they are different
  pictures. `window-id` captures the window even when something overlaps it.
  `region` captures the rectangle the window occupies, INCLUDING whatever is on
  top of it, and it is what happens when no CoreGraphics entry matches the
  accessibility frame -- measured 2026-08-19, that is the case for this
  application's own window, whose only layer-0 entries are menu-bar strips."
  [application]
  (if (cua?)
    (let [directory (io/file (config/data-dir) "agent-screenshots")
          file (io/file directory (str "window-" (random-uuid) ".png"))
          _ (.mkdirs directory)
          state (cua-tree* application 400 (.getCanonicalPath file))
          snapshot (get @cua-snapshots application)]
      (when-not (and (.isFile file) (pos? (.length file)))
        (throw (ex-info "ウインドウのキャプチャに失敗しました。"
                        {:type :desktop/capture-failed})))
      {:image-path (.getCanonicalPath file)
       :media-type "image/png"
       :application application
       :window (:window state)
       :capture "cua-driver-window"
       :occlusion "署名済みCuaDriverが対象ウインドウを直接取得しました。"
       :frame (let [b (get-in snapshot [:window :bounds])]
                [(:x b) (:y b) (:width b) (:height b)])})
    (let [target (helper! ["capture-target" "--app" application] 20)
        [x y w h] (:frame target)
        directory (io/file (config/data-dir) "agent-screenshots")
        file (io/file directory (str "window-" (random-uuid) ".png"))
        _ (.mkdirs directory)
        region? (not= "window-id" (:match target))
        args (if region?
               ["/usr/sbin/screencapture" "-x" "-o"
                (str "-R" x "," y "," w "," h) (.getCanonicalPath file)]
               ["/usr/sbin/screencapture" "-x" "-o"
                (str "-l" (:window-id target)) (.getCanonicalPath file)])
        {:keys [exit]} (exec! args 30)]
    (when-not (and (zero? exit) (.isFile file) (pos? (.length file)))
      (throw (ex-info "ウインドウのキャプチャに失敗しました。"
                      {:type :desktop/capture-failed
                       :match (:match target)
                       :onscreen (:onscreen target)})))
    {:image-path (.getCanonicalPath file)
     :media-type "image/png"
     :application application
     :window (:title target)
     :capture (:match target)
     :occlusion (if region?
                  "この画像は矩形のキャプチャです。手前にある他のウインドウが写り込みます。"
                  "対象ウインドウのみのキャプチャです。")
     :frame (:frame target)})))

;; ── writes ──────────────────────────────────────────────────────────────

(defn press!
  "Perform an element's action where it stands, under the marker."
  [application element-ref expect {:keys [action include-menu?]}]
  (if (cua?)
    (let [[snapshot element] (cua-fresh-element! application element-ref expect)]
      (cua-call! "click" (cond-> {:pid (:pid snapshot)
                                   :window_id (:window-id snapshot)
                                   :element_token (:element_token element)
                                   :delivery_mode "background"}
                            action (assoc :action (case action
                                                    "AXPress" "press"
                                                    "AXShowMenu" "show_menu"
                                                    "AXPick" "pick"
                                                    "AXConfirm" "confirm"
                                                    "AXCancel" "cancel"
                                                    "AXOpen" "open"
                                                    action))) 30))
    (helper! (cond-> ["press" "--app" application "--ref" element-ref
                      "--expect" expect "--overlay" overlay-milliseconds]
               action (into ["--action" action])
               include-menu? (into ["--include-menu" "true"]))
             30)))

(defn menu-press!
  "Perform a menu command by its `A>B>C` path.

  No `--expect`: a menu path is a name, not an index into a walk, so it does
  not go stale the way `@a12` does. The helper refuses a disabled item, which
  is the check that matters here -- a disabled Save means the document had
  nothing to save, and pressing it anyway would have reported success."
  [application path]
  (if (cua?)
    (let [app (cua-app! application)
          window (cua-window! (:pid app))]
      (cua-call! "invoke_menu" {:pid (:pid app)
                                 :window_id (:window_id window)
                                 :path (mapv str/trim (str/split path #">"))}
                 30))
    (helper! ["menu-press" "--app" application "--path" path
              "--overlay" overlay-milliseconds]
             30)))

(defn set-value!
  "Write a text element's value, and report what is observed afterwards."
  [application element-ref text expect {:keys [include-menu?]}]
  (if (cua?)
    (let [[snapshot element] (cua-fresh-element! application element-ref expect)]
      (cua-call! "set_value" {:pid (:pid snapshot)
                               :window_id (:window-id snapshot)
                               :element_token (:element_token element)
                               :value text}
                 30))
    (helper! (cond-> ["set-value" "--app" application "--ref" element-ref
                      "--text" text "--expect" expect
                      "--overlay" overlay-milliseconds]
               include-menu? (into ["--include-menu" "true"]))
             30)))

(defn scroll!
  "Scroll a scroll area by a page, through its accessibility action."
  [application element-ref direction expect {:keys [include-menu?]}]
  (if (cua?)
    (let [[snapshot element] (cua-fresh-element! application element-ref expect)]
      (cua-call! "scroll" {:pid (:pid snapshot)
                            :window_id (:window-id snapshot)
                            :element_token (:element_token element)
                            :direction direction
                            :by "page" :amount 1
                            :delivery_mode "background"}
                 30))
    (helper! (cond-> ["scroll" "--app" application "--ref" element-ref
                      "--direction" direction "--expect" expect
                      "--overlay" overlay-milliseconds]
               include-menu? (into ["--include-menu" "true"]))
             30)))
