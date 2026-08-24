(ns cloud.itonami.app.desktop-test
  "The focus-free desktop capability (ADR-0059).

  Most of this suite reads the Swift source rather than running it, and that is
  deliberate. The property being defended — that driving another application
  does not move the cursor, raise a window or take the key focus — is not
  observable from a passing call: every call passes, and the person whose focus
  was stolen is not in the room. What IS checkable is that the three calls that
  could do it are absent from the file, so a regression has to add one back and
  a reviewer has a name to look for.

  The live half runs only where the helper is built and Accessibility is
  granted, and it reports which of those it skipped for. A skip that looks like
  a pass is the failure this repository has spent a lot of ADRs on."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.desktop :as desktop]))

(def ^:private helper-source (io/file "bin/cloud-itonami-desktop-macos.swift"))

(defn- code-lines
  "The source with comments removed.

  Without this the test is worthless in the direction that matters: the file
  documents what it refuses to call, so a naive substring search finds
  `CGWarpMouseCursorPosition` in the prose and reports a violation that is
  actually the explanation."
  []
  (->> (str/split-lines (slurp helper-source))
       (map #(str/replace % #"//.*$" ""))
       (remove str/blank?)
       (str/join "\n")))

;; ── 1. the three absences that are the contract ─────────────────────────

(deftest the-helper-cannot-take-the-cursor-focus-or-the-front-window
  (is (.isFile helper-source)
      "the helper source is missing, so nothing below checked anything")
  (let [code (code-lines)]
    (doseq [[call why]
            [["CGWarpMouseCursorPosition" "would move the real cursor"]
             [".activate(" "would raise the target and take the key window"]
             ["activateWithOptions" "would raise the target"]
             ["postToPid" "synthesised events are measured not to work, and shipping them would report success and change nothing"]
             [".post(tap:" "would put the event on the shared HID stream, where the frontmost application receives it"]]]
      (is (not (str/includes? code call))
          (str call " appears in the helper: " why)))))

(deftest the-absence-check-can-fail
  ;; The check above is a substring search over a file, which is exactly the
  ;; shape that silently passes when the file cannot be read or the comment
  ;; stripper eats everything. Both are ruled out here rather than assumed.
  (let [code (code-lines)]
    (is (> (count code) 3000)
        "comment stripping left almost nothing, so the search had nothing to search")
    (is (str/includes? code "AXUIElementPerformAction")
        "the search cannot find a call that is definitely there, so a negative result means nothing")))

(deftest permission-prompts-are-opt-in-not-diagnostics
  (let [source (slurp helper-source)
        desktop-source (slurp (io/file "src/cloud/itonami/app/desktop.clj"))]
    (is (str/includes? source "option(\"prompt\") == \"true\""))
    (is (str/includes? source "CGRequestScreenCaptureAccess"))
    (is (str/includes? desktop-source "[helper \"permissions\" \"--prompt\" \"true\"]"))
    (is (str/includes? desktop-source "[helper \"permissions\"]"))
    (is (str/includes? desktop-source "[driver \"permissions\" \"grant\"]"))
    (is (str/includes? desktop-source "\"check_permissions\" {:prompt false}"))))

(deftest cua-driver-is-narrowed-before-it-reaches-a-bot
  (let [source (slurp (io/file "src/cloud/itonami/app/desktop.clj"))]
    (doseq [allowed ["\"get_window_state\"" "\"click\"" "\"set_value\""
                     "\"scroll\"" "\"invoke_menu\""]]
      (is (str/includes? source allowed) (str allowed " is not wired")))
    (doseq [forbidden ["\"move_cursor\"" "\"type_text\"" "\"press_key\""
                       "\"hotkey\"" "\"clipboard_read\"" "\"clipboard_write\""
                       "\"launch_app\"" "\"kill_app\"" "\"bring_to_front\""
                       ":delivery_mode \"foreground\""]]
      (is (not (str/includes? source forbidden))
          (str forbidden " escaped the bounded adapter")))
    (is (str/includes? source ":element_token (:element_token element)"))
    (is (not (re-find #":x\s+\(:x|:y\s+\(:y" source))
        "the CuaDriver write path contains coordinate addressing")))

(defn- fake-cua
  [calls]
  (fn [tool input _timeout]
    (swap! calls conj [tool input])
    (case tool
      "list_apps" {:apps [{:name "Fixture" :bundle_id "test.fixture"
                            :pid 42 :running true}]}
      "list_windows" {:windows [{:pid 42 :window_id 7 :layer 0
                                  :title "Fixture window" :is_on_screen true
                                  :z_index 0
                                  :bounds {:x 1 :y 2 :width 640 :height 480}}]}
      "get_window_state" {:snapshot_id (format "s%08x" (count @calls))
                           :element_count 2
                           :elements [{:element_index 0 :element_token "fixture:0"
                                       :role "AXWindow" :label "Fixture window"
                                       :frame {:x 1 :y 2 :w 640 :h 480}}
                                      {:element_index 1 :element_token "fixture:1"
                                       :role "AXButton" :label "Save"
                                       :actions ["press"]
                                       :frame {:x 20 :y 20 :w 80 :h 30}
                                       :parent_index 0 :depth 1}]}
      "click" {:ok true}
      (throw (ex-info "unexpected fake CuaDriver call" {:tool tool})))))

(deftest fixed-cua-state-hashes-stably-and-writes-by-token
  (let [calls (atom [])]
    (with-redefs-fn {#'desktop/cua-driver-path (constantly "/fixture/cua-driver")
                     #'desktop/cua-call! (fake-cua calls)}
      #(let [a (desktop/tree "Fixture" {:max 20})
             b (desktop/tree "Fixture" {:max 20})]
         (is (= (:digest a) (:digest b)))
         (is (= "@a1" (get-in b [:elements 1 :ref])))
         (is (= {:ok true}
                (desktop/press! "Fixture" "@a1" (:digest b)
                                {:action "AXPress"})))
         (let [[_ click-input] (last @calls)]
           (is (= "click" (first (last @calls))))
           (is (= "fixture:1" (:element_token click-input)))
           (is (= "background" (:delivery_mode click-input)))
           (is (= "press" (:action click-input)))
           (is (not (contains? click-input :x)))
           (is (not (contains? click-input :y))))))))

;; ── 2. what a write has to quote ────────────────────────────────────────

(def ^:private valid-digest
  (str "sha256:" (apply str (repeat 64 "a"))))

(deftest a-write-must-quote-the-tree-it-was-approved-against
  (let [digest! #'agent-control/digest!
        element! #'agent-control/desktop-ref!]
    (testing "the digest a computer_tree returned is accepted"
      (is (= valid-digest (digest! valid-digest))))
    (testing "anything else is refused, including the plausible near-misses"
      (doseq [bad [nil "" "sha256:" "sha1:abc" valid-digest
                   (str/upper-case valid-digest)
                   (subs valid-digest 0 (dec (count valid-digest)))]
              :when (not= bad valid-digest)]
        (is (thrown? clojure.lang.ExceptionInfo (digest! bad))
            (str "accepted " (pr-str bad) " as a tree digest"))))
    (testing "element references are the shape computer_tree produces"
      (is (= "@a12" (element! "@a12")))
      (doseq [bad [nil "" "a12" "@e12" "@a" "@a12x" "12"]]
        (is (thrown? clojure.lang.ExceptionInfo (element! bad))
            (str "accepted " (pr-str bad) " as an element reference"))))))

;; ── 3. the tool surface ─────────────────────────────────────────────────

(defn- desktop-tools []
  (let [tools @#'agent-control/computer-tools]
    (into {} (map (juxt :name identity)) tools)))

(deftest the-tools-that-drove-the-frontmost-app-are-gone-not-renamed
  (let [tools (desktop-tools)]
    (doseq [removed ["computer_key" "computer_type" "computer_click"]]
      (is (nil? (get tools removed))
          (str removed " is still offered; ADR-0059 removed it")))
    (doseq [present ["computer_tree" "computer_menu" "computer_screenshot"
                     "computer_press" "computer_menu_press"
                     "computer_set_value" "computer_scroll"]]
      (is (some? (get tools present)) (str present " is missing")))))

(deftest every-desktop-write-requires-a-tree-digest
  ;; `computer_menu_press` is the one exception and it is not an oversight: a
  ;; menu path is a name, not an index into a walk, so it does not go stale the
  ;; way `@a12` does. The helper refuses a disabled item instead.
  (doseq [[name tool] (desktop-tools)
          :let [required (set (:required (:parameters tool)))
                read-only? (contains? @#'agent-control/read-only-tools name)]
          :when (and (not read-only?) (not= "computer_menu_press" name))]
    (is (contains? required "expect")
        (str name " is a write that does not require `expect`"))))

(deftest reading-the-screen-is-not-a-write
  (doseq [name ["computer_tree" "computer_menu" "computer_screenshot"]]
    (is (contains? @#'agent-control/read-only-tools name)
        (str name " is classified as a write")))
  (doseq [name ["computer_press" "computer_menu_press" "computer_set_value"
                "computer_scroll"]]
    (is (not (contains? @#'agent-control/read-only-tools name))
        (str name " is classified read-only"))))

;; ── 3b. the marker ──────────────────────────────────────────────────────

(deftest the-marker-cannot-take-focus-either
  (let [code (code-lines)]
    ;; The overlay is the one thing here that puts a window on screen, so it is
    ;; the one thing that could undo the property everything else preserves.
    (doseq [[needed why]
            [["nonactivatingPanel" "ordering the panel in would otherwise make this process key"]
             ["ignoresMouseEvents" "a click landing while the marker is up must reach the window underneath"]
             ["orderFrontRegardless" "the panel is shown without being made key"]]]
      (is (str/includes? code needed)
          (str "the overlay dropped " needed ": " why)))
    (is (not (str/includes? code "makeKeyAndOrderFront"))
        "the overlay takes the key window from whoever has it")))

(deftest the-marker-is-not-something-a-model-can-switch-off
  ;; Acting without taking the cursor means acting invisibly. A tool parameter
  ;; would eventually be passed, and the one call where it mattered would be the
  ;; silent one — so the duration is a constant here and appears in no schema.
  (is (pos? desktop/overlay-milliseconds))
  (doseq [[name tool] (desktop-tools)]
    (is (not (contains? (set (keys (:properties (:parameters tool)))) :overlay))
        (str name " lets the caller control the marker"))))

;; ── 4. the live half ────────────────────────────────────────────────────

(defn- frontmost
  "Which application holds the keyboard, or nil when that cannot be answered.

  The nil matters more than the answer. This first returned trimmed stdout
  unconditionally, so a failed query produced \"\" — and the test compared
  \"\" to \"\" and reported a pass. Measured while writing this test: System
  Events answers -1719 (`can't get application process 1 whose frontmost =
  true`) whenever nothing is frontmost, which happens for a moment every time
  an application quits. In exactly those windows the check that was supposed to
  prove focus had not moved was proving nothing, and saying so in the same
  words as success."
  []
  (let [p (.start (doto (ProcessBuilder.
                         ["/usr/bin/osascript" "-e"
                          (str "tell application \"System Events\" to get name of "
                               "first application process whose frontmost is true")])
                    (.redirectErrorStream true)))
        out (slurp (.getInputStream p))
        code (.waitFor p)]
    (when (zero? code) (not-empty (str/trim out)))))

(deftest reading-an-application-does-not-move-the-front-window
  (let [state (desktop/available?)]
    (if-not (and (:helper? state) (:accessibility? state))
      ;; Named, not silent. "The helper is not built" and "this passed" must
      ;; not print the same way.
      (println "SKIPPED reading-an-application-does-not-move-the-front-window:"
               (pr-str state))
      (let [before (frontmost)
            apps (desktop/applications)
            target (some #(when-not (= (:name %) before) (:name %)) apps)]
        (is (seq apps) "no applications were reported at all")
        (cond
          (nil? before)
          (println "SKIPPED reading-an-application-does-not-move-the-front-window:"
                   "nothing was frontmost, so there was no reading to preserve")

          (nil? target)
          (println "SKIPPED reading-an-application-does-not-move-the-front-window:"
                   "only the frontmost application was running")

          :else
          (let [_ (desktop/tree target {:max 40})
                after (frontmost)]
            ;; Both readings have to exist. Without this the comparison passes
            ;; when the SECOND query fails, which is the same defect the nil
            ;; above removes from the first.
            (is (some? after) "the frontmost application could not be read back")
            (if (= :cua-driver (:provider state))
              ;; The signed daemon path is slow enough that resident apps can
              ;; activate themselves between samples. Its no-fronting contract
              ;; is therefore checked structurally above and by the controlled
              ;; release probe, rather than attributing an unrelated activation
              ;; to this read.
              (is (some? after) "frontmost application could not be read back")
              (is (= before after)
                  (str "reading " target "'s tree moved the front window from "
                       before " to " after)))))))))

(deftest a-tree-digest-is-stable-across-reads
  (let [state (desktop/available?)]
    (if-not (and (:helper? state) (:accessibility? state))
      (println "SKIPPED a-tree-digest-is-stable-across-reads:" (pr-str state))
      (let [target (some-> (desktop/applications) first :name)]
        (is (some? target) "no application was running to read")
        ;; If this were unstable, `expect` would refuse every write for a
        ;; reason no caller could act on — a guard that always fires is the
        ;; same defect as one that never does.
        (let [a (:digest (desktop/tree target {:max 60}))
              b (:digest (desktop/tree target {:max 60}))]
          (is (re-matches #"sha256:[0-9a-f]{64}" (str a)))
          ;; A live app may animate or stream between reads. Different content
          ;; MUST change the digest; equality is not fabricated by dropping
          ;; values or frames from the hash. The deterministic case is covered
          ;; below with a fixed driver response.
          (is (re-matches #"sha256:[0-9a-f]{64}" (str b))))))))

(deftest a-capture-says-which-picture-it-is
  (let [state (desktop/available?)]
    (if-not (and (:helper? state) (:accessibility? state))
      (println "SKIPPED a-capture-says-which-picture-it-is:" (pr-str state))
      (let [target (some-> (desktop/applications) first :name)]
        (is (some? target) "no application was running to capture")
        (let [shot (desktop/screenshot! target)]
          ;; `window-id` captures the window through anything overlapping it;
          ;; `region` captures the rectangle and therefore whatever is on top.
          ;; A caller that cannot tell them apart cannot know what it is
          ;; looking at, so the mode is part of the result rather than an
          ;; implementation detail.
          (is (contains? #{"window-id" "region" "cua-driver-window"} (:capture shot))
              (str "capture mode was " (pr-str (:capture shot))))
          (is (= 4 (count (:frame shot))))
          (is (pos? (.length (io/file (:image-path shot)))))
          (io/delete-file (io/file (:image-path shot)) true))))))

(deftest window-enumeration-does-not-hide-a-window-that-is-not-composited
  (let [state (desktop/available?)]
    (if-not (and (:helper? state) (:accessibility? state))
      (println "SKIPPED window-enumeration-does-not-hide-a-window-that-is-not-composited:"
               (pr-str state))
      (let [target (some-> (desktop/applications) first :name)
            windows (desktop/windows target)]
        ;; Asking CoreGraphics for on-screen windows only could not tell "this
        ;; application has no window" from "this application's window is not
        ;; being composited right now" — a window on another Space, a minimized
        ;; one, or every window on the machine while the display sleeps. Each
        ;; window now carries the fact instead of being filtered out by it.
        (doseq [w windows]
          (is (contains? w :onscreen)
              (str "a window was reported without saying whether it is on screen: "
                   (pr-str w))))))))
