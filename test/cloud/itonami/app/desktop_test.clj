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
            (is (= before after)
                (str "reading " target "'s tree moved the front window from "
                     before " to " after))))))))

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
          (is (= a b) (str "two consecutive reads of " target " disagreed")))))))
