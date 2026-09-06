
;; The input area's contract: what a key means, and where the caret lands.
;;
;;   nbb test/itonami_editor_nbb.cljs
;;
;;   0  all checks passed
;;   1  a check failed

(ns itonami-editor-nbb
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is run-tests]]
            [nbb.classpath :as classpath]
            [nbb.core :refer [*file*]]))

(classpath/add-classpath (path/resolve (path/dirname *file*) ".." "bin"))
(require '[itonami-editor :as ed]
         '[itonami-text :as text])

(def ^:private esc (js/String.fromCharCode 27))

(defn- type* [state s]
  (reduce ed/handle state (ed/decode s)))

(defn- press [state & kinds]
  (reduce (fn [s k] (ed/handle s {:kind k})) state kinds))

(defn- ed [s] (type* (ed/fresh) s))

;; ---------------------------------------------------------------------------
;; the rule this file exists for
;; ---------------------------------------------------------------------------

(deftest up-means-the-line-above-before-it-means-history
  ;; readline binds Up to history unconditionally, so a two-line buffer cannot
  ;; be navigated at all. This is the one keystroke it would not give up.
  (let [two (-> (ed/fresh ["earlier"]) (type* "one") (press :newline) (type* "two"))]
    (is (= ["one" "two"] (:lines two)))
    (is (= 1 (:row two)))
    (let [up (press two :up)]
      (is (= 0 (:row up)) "Up on the second line went to history instead of line one")
      (is (= ["one" "two"] (:lines up)) "the buffer was replaced by a history entry")
      ;; and only from the top does it reach for history
      (let [again (press up :up)]
        (is (= ["earlier"] (:lines again)))))))

(deftest history-gives-the-draft-back
  ;; Browsing history must not consume what was being written.
  (let [s (-> (ed/fresh ["older" "newer"]) (type* "draft"))
        up1 (press s :up)
        up2 (press up1 :up)]
    (is (= ["newer"] (:lines up1)))
    (is (= ["older"] (:lines up2)))
    (is (= ["newer"] (:lines (press up2 :down))))
    (is (= ["draft"] (:lines (press up2 :down :down)))
        "the draft was not returned when Down walked past the newest entry")))

(deftest up-at-the-top-with-no-history-does-nothing
  ;; The control: it must not blank the buffer for want of somewhere to go.
  (let [s (ed "text")]
    (is (= (:lines s) (:lines (press s :up))))
    (is (= (:col s) (:col (press s :up))))))

(deftest a-caret-carried-up-does-not-fall-off-a-shorter-line
  (let [s (-> (ed/fresh) (type* "ab") (press :newline) (type* "longer"))]
    (is (= 6 (:col s)))
    (is (= 2 (:col (press s :up))) "the caret kept a column the line above does not have")))

;; ---------------------------------------------------------------------------
;; making a second line at all
;; ---------------------------------------------------------------------------

(deftest a-trailing-backslash-and-enter-is-a-newline
  ;; Terminals disagree about Shift+Enter -- several send a bare Return -- so a
  ;; newline that needs a modifier is unreachable on some of them.
  (let [s (-> (ed "ab\\") (press :enter))]
    (is (= ["ab" ""] (:lines s)))
    (is (nil? (:submit s)) "the turn was sent instead of continued"))
  ;; the control: without the backslash the same key sends
  (is (= "ab" (:submit (press (ed "ab") :enter)))))

(deftest alt-enter-is-a-newline-anywhere-in-the-line
  (let [s (-> (ed "abcd") (press :left :left :newline))]
    (is (= ["ab" "cd"] (:lines s)))
    (is (= [1 0] [(:row s) (:col s)]))))

(deftest a-backslash-in-the-middle-is-just-a-backslash
  (let [s (-> (ed "a\\b") (press :enter))]
    (is (= "a\\b" (:submit s)))))

(deftest a-bracketed-paste-with-newlines-keeps-them
  ;; Measured 2026-09-06: without bracketed paste a pasted newline is the same
  ;; byte as Return, so pasting three lines SENT the first and dropped the
  ;; other two -- the read loop stops at the first :submit.
  (let [pasted (str esc "[200~" "one\ntwo\nthree" esc "[201~")
        s (type* (ed/fresh) pasted)]
    (is (= ["one" "two" "three"] (:lines s)))
    (is (nil? (:submit s)) "a pasted newline sent the turn")
    (is (= [2 5] [(:row s) (:col s)])))
  ;; the control: the same text WITHOUT the brackets is Return, three times,
  ;; and the first line is what gets sent
  (is (= "one" (:submit (ed/handle (ed "one") {:kind :enter})))))

;; ---------------------------------------------------------------------------
;; the rest of the editing surface
;; ---------------------------------------------------------------------------

(deftest backspace-joins-lines-at-the-start-of-one
  (let [s (-> (ed "ab") (press :newline) (type* "cd") (press :home :backspace))]
    (is (= ["abcd"] (:lines s)))
    (is (= [0 2] [(:row s) (:col s)]))))

(deftest submit-records-history-without-duplicating-it
  (let [a (press (ed "x") :enter)
        b (press (type* (ed/fresh (:history a)) "x") :enter)]
    (is (= ["x"] (:history a)))
    (is (= ["x"] (:history b)) "the same line was recorded twice in a row")))

(deftest an-empty-enter-sends-nothing
  (is (nil? (:submit (press (ed/fresh) :enter)))))

(deftest ctrl-c-clears-the-draft-and-only-then-signals
  ;; An interrupt that kills the session on the first press is not an
  ;; interrupt, it is an exit with a different name.
  (let [typed (ed "half a thought")
        cleared (ed/handle typed {:kind :interrupt})]
    (is (= [""] (:lines cleared)))
    (is (nil? (:signal cleared)))
    (is (= :interrupt (:signal (ed/handle cleared {:kind :interrupt}))))))

(deftest ctrl-d-is-eof-only-on-an-empty-buffer
  (is (= :eof (:signal (ed/handle (ed/fresh) {:kind :eof}))))
  (let [s (-> (ed "abc") (press :home) (ed/handle {:kind :eof}))]
    (is (nil? (:signal s)))
    (is (= ["bc"] (:lines s)))))

;; ---------------------------------------------------------------------------
;; decoding
;; ---------------------------------------------------------------------------

(deftest arrows-are-not-typed-into-the-buffer
  (is (= [{:kind :char :ch "a"} {:kind :up} {:kind :down} {:kind :right} {:kind :left}]
         (ed/decode (str "a" esc "[A" esc "[B" esc "[C" esc "[D"))))
  ;; the control: the same bytes, if they were not decoded, would type
  ;; `[A[B[C[D` into the line
  (is (= "a" (str/join (keep :ch (ed/decode (str "a" esc "[A" esc "[B")))))))

(deftest an-unknown-escape-sequence-is-dropped-not-typed
  ;; Printing `[5;2R` into the buffer because the terminal answered a query
  ;; nobody made is worse than silence.
  (is (= [{:kind :char :ch "x"}]
         (ed/decode (str esc "[5;2R" "x" esc "[?1;2c")))))

(deftest one-chunk-can-carry-many-keys
  ;; A paste arrives as a single read; decoding only its first byte would drop
  ;; the rest of what was pasted.
  (is (= 5 (count (ed/decode "abcde"))))
  (is (= "abcde" (str/join (keep :ch (ed/decode "abcde"))))))

(deftest alt-enter-and-plain-enter-decode-differently
  (is (= [{:kind :newline}] (ed/decode (str esc "\r"))))
  (is (= [{:kind :enter}] (ed/decode "\r"))))

(deftest a-lone-escape-is-its-own-key
  ;; This is what interrupts a run; if it decoded as nothing, esc would be
  ;; a key that reports itself in the status bar and does nothing.
  (is (= [{:kind :escape}] (ed/decode esc))))

;; ---------------------------------------------------------------------------
;; render
;; ---------------------------------------------------------------------------

(def ^:private geo {:width 40 :prompt "> " :continuation "  " :status "S"})

(deftest the-frame-is-a-rule-the-input-a-rule-and-the-status
  (let [{:keys [rows caret]} (ed/render (ed "hi") geo)]
    (is (= 4 (count rows)))
    (is (= 40 (text/display-width (first rows))))
    (is (= 40 (text/display-width (nth rows 2))))
    (is (= "> hi" (nth rows 1)))
    (is (= "S" (last rows)))
    (is (= [1 4] caret))))

(deftest the-caret-is-placed-in-columns-not-characters
  ;; A line of Japanese is twice as wide as it is long. Placing the caret by
  ;; character index would put it half-way back through the text.
  (let [{:keys [caret]} (ed/render (ed "営み") geo)]
    (is (= [1 6] caret) "prompt 2 + four columns of two characters")))

(deftest a-long-line-wraps-and-the-caret-follows-it
  (let [long* (apply str (repeat 100 "x"))
        state (ed long*)
        {:keys [rows caret]} (ed/render state geo)
        [cr _] caret]
    ;; 40 wide, prompt 2, one column held back => 37 per row => 100 needs 3
    (is (= 6 (count rows)) "rule + 3 wrapped rows + rule + status")
    (is (= 3 cr) "the caret stayed on the first visual row of a wrapped line")))

(deftest every-visual-row-fits
  (let [state (-> (ed (apply str (repeat 90 "a"))) (press :newline) (type* "営みの記録"))
        {:keys [rows]} (ed/render state geo)]
    (is (every? #(<= (text/display-width %) 40) rows))))

(deftest the-status-bar-says-only-what-is-known-without-asking
  (let [idle (ed/status-line {:profile "p" :slash-count 28 :colour false} 100)
        busy (ed/status-line {:profile "p" :slash-count 28 :running? true :colour false} 100)
        held (ed/status-line {:profile "p" :slash-count 28 :held "write_file" :colour false} 100)]
    (is (str/includes? idle "28 slash"))
    (is (str/includes? idle "履歴"))
    (is (str/ends-with? idle "/help"))
    (is (= 100 (text/display-width idle)))
    ;; `esc で中断` claims a key that only works while a run is in flight
    (is (not (str/includes? idle "esc")))
    (is (str/includes? busy "esc"))
    (is (str/includes? held "write_file"))
    (is (str/includes? held "/approve"))))

(deftest a-narrow-bar-drops-whole-hints-not-halves-of-them
  ;; It truncated mid-word at 60 the first time, leaving `↑…` -- half an
  ;; instruction, which is worse than none.
  (let [facts {:profile "p" :slash-count 28 :colour false}]
    (is (str/includes? (ed/status-line facts 100) "履歴"))
    (let [narrow (ed/status-line facts 44)]
      (is (not (str/includes? narrow "…")) narrow)
      (is (str/includes? narrow "28 slash") narrow))))

(deftest the-status-bar-does-not-overflow-a-narrow-terminal
  ;; It overflowed by six columns at 60 the first time this ran. A bar wider
  ;; than the terminal wraps onto the row the caret is about to be moved to,
  ;; and the frame tears on the next redraw.
  (doseq [w [30 40 60 100]]
    (doseq [facts [{:profile "a-rather-long-profile-name" :slash-count 28}
                   {:profile "p" :slash-count 28 :held "workspace_write_file"}
                   {:profile "p" :slash-count 28 :running? true}]]
      (let [line (ed/status-line (assoc facts :colour false) w)]
        (is (= w (text/display-width line))
            (str w " columns, " (pr-str facts) ": " (text/display-width line)))))))

(let [{:keys [fail error]} (run-tests 'itonami-editor-nbb)]
  (js/process.exit (if (pos? (+ (or fail 0) (or error 0))) 1 0)))
