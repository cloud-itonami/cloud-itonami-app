(ns cloud.itonami.app.desktop
  "The focus-free desktop capability (ADR-0059).

  Everything here goes through one helper binary, `bin/cloud-itonami-desktop-
  macos.swift`, whose contract is that it never activates an application, never
  moves the cursor, and posts no synthesised events at all. This namespace is
  the seam that keeps that contract checkable: it is the only place that builds
  the argv, so an argument the helper does not offer cannot be smuggled in from
  a tool call.

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
  (:import [java.util.concurrent TimeUnit]))

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

(defn available?
  "Is the focus-free capability actually usable right now?

  Three separate facts, because a missing binary, a missing Accessibility grant
  and a missing Screen Recording grant need three different answers from a
  person. Reported rather than prompted for."
  []
  (if-let [helper (helper-path)]
    (let [{:keys [exit out]} (exec! [helper "permissions"] 20)]
      (if (zero? exit)
        (let [body (json/read-str out :key-fn keyword)]
          {:helper? true
           :accessibility? (true? (:accessibility body))
           :screen-recording? (true? (:screen-recording body))})
        {:helper? true :accessibility? false :screen-recording? false}))
    {:helper? false :accessibility? false :screen-recording? false}))

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

(defn applications []
  (:applications (helper! ["apps"] 20)))

(defn tree
  "The target application's accessibility tree, plus the digest that binds it.

  The digest is what a later write quotes back through `:expect`. Callers that
  drop it get a write with no guard at all, so every writing function here
  takes it as a required argument rather than an option."
  [application {:keys [max include-menu?]}]
  (helper! (cond-> ["tree" "--app" application]
             max (into ["--max" max])
             include-menu? (into ["--include-menu" "true"]))
           30))

(defn menu
  "Every menu command the application offers, with the shortcut each one owns.

  The shortcut is reported so a model asking for `cmd+s` can find the command
  that shortcut stands for -- which is the only way to press it from the
  background."
  [application {:keys [contains]}]
  (helper! (cond-> ["menu" "--app" application]
             (not (str/blank? (str contains))) (into ["--contains" contains]))
           30))

(defn windows [application]
  (:windows (helper! ["windows" "--app" application] 20)))

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
     :frame (:frame target)}))

;; ── writes ──────────────────────────────────────────────────────────────

(defn press!
  "Perform an element's action where it stands, under the marker."
  [application element-ref expect {:keys [action include-menu?]}]
  (helper! (cond-> ["press" "--app" application "--ref" element-ref
                    "--expect" expect "--overlay" overlay-milliseconds]
             action (into ["--action" action])
             include-menu? (into ["--include-menu" "true"]))
           30))

(defn menu-press!
  "Perform a menu command by its `A>B>C` path.

  No `--expect`: a menu path is a name, not an index into a walk, so it does
  not go stale the way `@a12` does. The helper refuses a disabled item, which
  is the check that matters here -- a disabled Save means the document had
  nothing to save, and pressing it anyway would have reported success."
  [application path]
  (helper! ["menu-press" "--app" application "--path" path
            "--overlay" overlay-milliseconds]
           30))

(defn set-value!
  "Write a text element's value, and report what is observed afterwards."
  [application element-ref text expect {:keys [include-menu?]}]
  (helper! (cond-> ["set-value" "--app" application "--ref" element-ref
                    "--text" text "--expect" expect
                    "--overlay" overlay-milliseconds]
             include-menu? (into ["--include-menu" "true"]))
           30))

(defn scroll!
  "Scroll a scroll area by a page, through its accessibility action."
  [application element-ref direction expect {:keys [include-menu?]}]
  (helper! (cond-> ["scroll" "--app" application "--ref" element-ref
                    "--direction" direction "--expect" expect
                    "--overlay" overlay-milliseconds]
             include-menu? (into ["--include-menu" "true"]))
           30))
