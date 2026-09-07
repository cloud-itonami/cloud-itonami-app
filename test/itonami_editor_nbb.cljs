
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


;; ---------------------------------------------------------------------------
;; how wide the terminal says a glyph is
;; ---------------------------------------------------------------------------

(deftest ambiguous-width-is-asked-not-assumed
  ;; UAX#11 leaves U+276F, U+25B6, U+00B7 and all of box drawing to the
  ;; terminal: one column in a Latin locale, two in a CJK one. Assuming one and
  ;; being wrong by one is not cosmetic -- it is what made the frame tear
  ;; (measured 2026-09-07), because a row one column too wide wraps and the
  ;; block then occupies more rows than were counted.
  (try
    (text/set-ambiguous-columns! 1)
    (is (= 2 (text/display-width "❯ ")))
    (is (= 2 (text/display-width "▶▶")))
    (text/set-ambiguous-columns! 2)
    (is (= 3 (text/display-width "❯ ")) "the terminal's answer was ignored")
    (is (= 4 (text/display-width "▶▶")))
    ;; unambiguous widths do not move with it
    (is (= 4 (text/display-width "営み")))
    (is (= 3 (text/display-width "abc")))
    (finally (text/set-ambiguous-columns! 1))))

(deftest only-a-terminal-width-is-accepted-as-an-answer
  ;; A query that came back as anything else was not an answer. Writing it down
  ;; would turn "the terminal did not reply" into a measurement.
  (try
    (text/set-ambiguous-columns! 2)
    (is (= 2 (text/set-ambiguous-columns! 0)))
    (is (= 2 (text/set-ambiguous-columns! 7)))
    (is (= 2 (text/set-ambiguous-columns! nil)))
    (is (= 1 (text/set-ambiguous-columns! 1)))
    (finally (text/set-ambiguous-columns! 1))))

(deftest the-frame-follows-the-measured-width
  ;; The point of measuring: every row still fits after the answer changes.
  (try
    (doseq [n [1 2]]
      (text/set-ambiguous-columns! n)
      (let [{:keys [rows]} (ed/render (ed "hi") {:width 40 :prompt "❯ " :status "S"})]
        (is (every? #(<= (text/display-width %) 40) rows)
            (str "ambiguous=" n))))
    (finally (text/set-ambiguous-columns! 1))))

;; ---------------------------------------------------------------------------
;; the line that runs under a turn
;; ---------------------------------------------------------------------------

(deftest elapsed-reads-like-a-wait
  (is (= "0s" (ed/elapsed 0)))
  (is (= "9s" (ed/elapsed 9400)))
  (is (= "1m 14s" (ed/elapsed 74000)))
  (is (= "2h 1m" (ed/elapsed 7260000)))
  (is (= "0s" (ed/elapsed -5)) "a clock that went backwards is not a negative wait"))

(deftest the-progress-line-says-only-what-arrived
  (let [line (ed/progress-line {:phase "model" :ms 74000 :tick 0
                                :interruptible? true :colour false} 80)]
    (is (str/includes? line "model…"))
    (is (str/includes? line "1m 14s"))
    (is (str/includes? line "esc"))
    ;; there is no percentage and no bar: the server sends phases, not
    ;; progress, and a bar filling on a timer would be the one thing on this
    ;; screen that nothing measured
    (is (not (str/includes? line "%")))
    (is (not (str/includes? line "█"))))
  ;; a run that has reported no phase yet says so rather than inventing one
  (is (str/includes? (ed/progress-line {:ms 3000 :colour false} 80) "working…"))
  ;; tokens appear only once some have been counted
  (is (not (str/includes? (ed/progress-line {:ms 3000 :colour false} 80) "tokens")))
  (is (str/includes? (ed/progress-line {:ms 3000 :tokens 12 :colour false} 80) "12 tokens")))

(deftest the-progress-line-fits-and-turns
  (doseq [w [20 40 100]]
    (is (>= w (text/display-width
               (ed/progress-line {:phase "a-very-long-phase-name-from-the-server"
                                  :ms 999999 :tokens 12345 :interruptible? true
                                  :colour false} w)))))
  ;; it is a spinner: consecutive ticks differ
  (let [f #(first (ed/progress-line {:ms 0 :tick % :colour false} 40))]
    (is (not= (f 0) (f 1)))
    (is (= (f 0) (f (count ed/spinner-frames))) "the frames do not cycle")))


;; ---------------------------------------------------------------------------
;; the block holds what is in flight
;; ---------------------------------------------------------------------------

(deftest the-progress-line-and-the-streaming-tail-are-rows-of-the-block
  ;; Both used to be lines of their own, which means being told where the block
  ;; is -- and disagreeing with it the moment either moves. As rows they are
  ;; redrawn with everything else and the row count stays true.
  (let [plain (ed/render (ed "hi") geo)
        full (ed/render (ed "hi") (assoc geo :header "H" :tail "streamed"))]
    (is (= 4 (count (:rows plain))))
    (is (= 6 (count (:rows full))))
    ;; the tail continues the answer above it; the progress line sits over the
    ;; frame, not between the answer and its own last line
    (is (= ["streamed" "H"] (take 2 (:rows full))))
    (is (= [1 4] (:caret plain)))
    (is (= [3 4] (:caret full)) "the caret did not move down past the new rows")))

(deftest a-tail-wider-than-the-terminal-becomes-several-rows
  ;; This is the reason the tail is not left on the terminal: a partial line
  ;; wider than the screen wraps, and ESC[nC clamps at the last column, so
  ;; returning to its end is not possible. Counted here instead.
  (let [long* (apply str (repeat 90 "x"))
        {:keys [rows caret]} (ed/render (ed "hi") (assoc geo :tail long*))]
    (is (= 7 (count rows)) "40 wide, 90 columns of tail => 3 rows + the usual 4")
    (is (every? #(<= (text/display-width %) 40) rows))
    (is (= [4 4] caret))))

(deftest the-status-bar-says-what-is-waiting
  (let [busy (ed/status-line {:profile "p" :slash-count 29 :running? true
                              :colour false} 100)]
    (is (str/includes? busy "入力は受け付けています")
        "a run in flight must not read as a keyboard that is gone"))
  (let [queued (ed/status-line {:profile "p" :slash-count 29 :running? true
                                :queued 3 :colour false} 100)]
    (is (str/includes? queued "3 件待機"))
    (is (str/includes? queued "/queue")))
  ;; nothing waiting says nothing about a queue
  (is (not (str/includes? (ed/status-line {:profile "p" :slash-count 29
                                           :queued 0 :colour false} 100)
                          "待機"))))


;; ---------------------------------------------------------------------------
;; what the loop has to do about a chunk of keys
;; ---------------------------------------------------------------------------

(deftest typing-asks-for-a-redraw
  ;; Reported 2026-09-07: nothing appeared while typing, so the operator typed
  ;; the line again and the buffer really did hold it twice -- the echo showed
  ;; the same sentence twice on submit.
  ;;
  ;; The loop redrew only on the branches that happened to remember, and after
  ;; the input loop was separated from the turn, the ordinary branch -- a
  ;; character -- was not one of them. Every pty check written before that ran
  ;; a slash command or a run in the same breath, and both redraw through other
  ;; paths. None of them typed a character and looked.
  (let [{:keys [state submits signal redraw?]} (ed/step (ed/fresh) (ed/decode "X"))]
    (is (true? redraw?) "a keystroke that changes the buffer must reach the screen")
    (is (= ["X"] (:lines state)))
    (is (empty? submits))
    (is (nil? signal)))
  ;; and a chunk that changes nothing still redraws: one frame is cheap, and
  ;; deciding which keys are invisible is the decision that was got wrong
  (is (true? (:redraw? (ed/step (ed/fresh) [{:kind :up}])))))

(deftest a-chunk-carries-out-everything-it-contained
  ;; A paste, or a fast typist, arrives as one read. Handling only the first
  ;; key would drop the rest.
  (let [{:keys [state submits]} (ed/step (ed/fresh) (ed/decode "one\rtwo\rthree"))]
    (is (= ["one" "two"] submits) "a submit mid-chunk was dropped")
    (is (= ["three"] (:lines state)) "what followed the last submit was lost")))

(deftest a-signal-stops-the-chunk-there
  ;; Ctrl+D on an empty buffer ends the session; keys behind it in the same
  ;; read must not be applied to a session that is going away.
  (let [{:keys [signal submits]} (ed/step (ed/fresh) [{:kind :eof}
                                                      {:kind :char :ch "z"}])]
    (is (= :eof signal))
    (is (empty? submits)))
  ;; the control: without the signal the same keys go through
  (is (= ["z"] (:lines (:state (ed/step (ed/fresh) [{:kind :char :ch "z"}]))))))


;; ---------------------------------------------------------------------------
;; the slash menu
;; ---------------------------------------------------------------------------

(def ^:private cmds
  [{:name "/help" :description "この一覧"}
   {:name "/status" :description "状態"}
   {:name "/steer" :description "割り込み"}
   {:name "/timestamps" :description "時刻"}
   {:name "/west-pin-advance" :description "pin"}])

(defn- ed* [s] (type* (ed/fresh [] cmds) s))

(deftest a-slash-opens-the-list-and-typing-narrows-it
  (is (= ["/help" "/status" "/steer" "/timestamps" "/west-pin-advance"]
         (mapv :name (:items (ed/menu (ed* "/"))))))
  ;; `/west-pin-advance` is here because "west" contains "st". That is the
  ;; substring half of the match doing its job -- it is how `/pin` finds it
  ;; too -- and the prefix half keeps it last.
  (is (= ["/status" "/steer" "/timestamps" "/west-pin-advance"]
         (mapv :name (:items (ed/menu (ed* "/st"))))))
  (is (= ["/west-pin-advance"] (mapv :name (:items (ed/menu (ed* "/pin"))))))
  (is (nil? (ed/menu (ed* "hello"))) "the list is for slash commands, not messages"))

(deftest a-prefix-comes-before-a-word-that-merely-contains-it
  ;; `/st` should offer `/status` before `/timestamps`: a prefix is what the
  ;; operator is typing, and `st` in the middle of a word is not what they mean.
  (let [names (mapv :name (:items (ed/menu (ed* "/st"))))]
    (is (< (.indexOf names "/status") (.indexOf names "/timestamps")))
    (is (< (.indexOf names "/steer") (.indexOf names "/timestamps")))))

(deftest the-list-closes-once-arguments-are-being-typed
  ;; A list that keeps covering the screen while arguments are typed is a list
  ;; that has to be dismissed rather than one that helps.
  (is (some? (ed/menu (ed* "/steer"))))
  (is (nil? (ed/menu (ed* "/steer stop counting")))))

(deftest arrows-move-the-selection-and-not-the-history
  ;; Up in a one-line buffer means history everywhere else. Over a half-typed
  ;; command name that would throw the name away.
  (let [s (type* (ed/fresh ["earlier message"] cmds) "/st")
        d (press s :down)]
    (is (= 1 (:index (ed/menu d))))
    (is (= ["/st"] (:lines d)) "history was pulled over the typed command")
    (is (= 0 (:index (ed/menu (press d :up)))))
    ;; and it stops at the ends rather than wrapping
    (is (= 0 (:index (ed/menu (press d :up :up :up)))))
    (let [last* (press d :down :down :down :down)]
      (is (= 3 (:index (ed/menu last*))) "the selection ran past the last item"))))

(deftest tab-completes-the-selection
  (let [s (press (ed* "/st") :down :tab)]
    (is (= ["/steer "] (:lines s)))
    (is (= 7 (:col s)) "the caret did not land after the completed name")
    (is (nil? (ed/menu s)) "the list stayed up over a completed command")))

(deftest enter-completes-a-partial-name-and-sends-a-whole-one
  ;; Completing `/help` to `/help ` and asking for a second Enter would be the
  ;; menu getting in the way of the commonest case.
  (let [partial* (press (ed* "/st") :enter)]
    (is (nil? (:submit partial*)))
    (is (= ["/status "] (:lines partial*))))
  (let [whole (press (ed* "/help") :enter)]
    (is (= "/help" (:submit whole)) "a fully typed command was completed instead of sent")))

(deftest escape-dismisses-the-list-and-it-stays-dismissed
  ;; Until the word changes -- otherwise it reappears on the next keystroke and
  ;; the key looks broken.
  (let [closed (press (ed* "/st") :escape)]
    (is (nil? (ed/menu closed)))
    (is (nil? (ed/menu (press closed :left))))
    (is (some? (ed/menu (type* closed "a"))) "typing did not bring it back")))

(deftest a-command-with-no-match-shows-no-list
  ;; And Enter then sends the line, so the operator gets the server's own
  ;; "no such command" rather than a menu that silently ate the key.
  (let [s (ed* "/zzz")]
    (is (nil? (ed/menu s)))
    (is (= "/zzz" (:submit (press s :enter))))))

(deftest the-list-is-rows-of-the-block-and-the-caret-clears-them
  (let [{:keys [rows caret]} (ed/render (ed* "/st") (assoc geo :width 60))]
    ;; rule + 4 candidates + input + rule + status
    (is (= 8 (count rows)))
    (is (str/starts-with? (nth rows 1) "▸ ") "the selected row is not marked")
    (is (every? #(<= (text/display-width %) 60) rows))
    (is (= [5 5] caret) "the caret did not clear the candidate rows")))

(deftest a-long-list-is-counted-not-silently-cut
  ;; A list that stops at eight teaches an operator that the ninth command does
  ;; not exist.
  (let [many (mapv #(hash-map :name (str "/c" %) :description "d") (range 30))
        s (type* (ed/fresh [] many) "/c")
        {:keys [rows]} (ed/render s (assoc geo :width 60))]
    (is (= 30 (count (:items (ed/menu s)))))
    (is (= (+ ed/menu-rows-max 1 4) (count rows)))
    (is (some #(str/includes? % "+22 more") rows))))

(deftest the-status-bar-names-the-keys-that-are-live
  (let [open (ed/status-line {:profile "p" :slash-count 61 :menu? true :colour false} 100)
        shut (ed/status-line {:profile "p" :slash-count 61 :colour false} 100)]
    (is (str/includes? open "選択"))
    (is (str/includes? open "補完"))
    (is (not (str/includes? open "履歴")) "it offered a key the menu had taken")
    (is (str/includes? shut "履歴"))))


;; ---------------------------------------------------------------------------
;; panels
;; ---------------------------------------------------------------------------

(def ^:private demo-panel
  {:title "itonami help"
   :tab 0 :scroll 0
   :tabs [{:label "General" :lines ["one" "two"]}
          {:label "Commands" :lines (mapv #(str "/c" %) (range 40))}
          {:label "CLI" :lines ["usage"]}]})

(defn- panelled [] (assoc (ed/fresh [] cmds) :panel demo-panel))

(defn- press* [s & kinds]
  (reduce (fn [st k] (ed/handle st {:kind k})) s kinds))

(deftest a-panel-takes-over-the-block
  ;; There is nothing to type into, so a prompt and a status bar of keys that
  ;; do nothing would be two lies at once.
  (let [{:keys [rows caret panel?]} (ed/render (panelled) (assoc geo :width 60 :rows 24))]
    (is (true? panel?))
    (is (= [0 0] caret))
    (is (str/includes? (first rows) "itonami help"))
    (is (some #(str/includes? % "[General]") rows) "the open tab is not marked")
    (is (some #(str/includes? % "esc で閉じる") rows))
    (is (not-any? #(str/includes? % "▶▶") rows) "the status bar is still there")
    (is (every? #(<= (text/display-width %) 60) rows))))

(deftest typing-into-a-panel-does-nothing
  ;; A key that edits a buffer nobody can see is a key that loses what it
  ;; typed the moment the panel closes.
  (let [s (ed/handle (panelled) {:kind :char :ch "x"})]
    (is (= [""] (:lines s)))
    (is (some? (:panel s)))))

(deftest escape-and-enter-close-it
  (is (nil? (:panel (ed/handle (panelled) {:kind :escape}))))
  (is (nil? (:panel (ed/handle (panelled) {:kind :enter}))))
  (is (nil? (:submit (ed/handle (panelled) {:kind :enter}))) "closing it also sent something"))

(deftest ctrl-d-still-leaves
  ;; Whatever is on the screen, the way out has to keep working.
  (is (= :eof (:signal (ed/handle (panelled) {:kind :eof})))))

(deftest tabs-cycle-both-ways
  (let [s (panelled)]
    (is (= 1 (get-in (ed/handle s {:kind :tab}) [:panel :tab])))
    (is (= 1 (get-in (ed/handle s {:kind :right}) [:panel :tab])))
    ;; wrapping, so neither end is a dead key
    (is (= 2 (get-in (ed/handle s {:kind :left}) [:panel :tab])))
    (is (= 0 (get-in (press* s :tab :tab :tab) [:panel :tab])))))

(deftest scrolling-stops-at-the-top
  (let [s (panelled)]
    (is (= 0 (get-in (ed/handle s {:kind :up}) [:panel :scroll])))
    (is (= 1 (get-in (ed/handle s {:kind :down}) [:panel :scroll])))))

(deftest a-short-tab-cannot-be-scrolled-off-the-screen
  ;; The clamp lives in the renderer, so a scroll value past the end still
  ;; shows the end rather than an empty frame.
  (let [s (assoc-in (panelled) [:panel :scroll] 999)
        {:keys [rows]} (ed/render s (assoc geo :width 60 :rows 24))]
    (is (some #(str/includes? % "one") rows) "scrolling past the end blanked the panel")))

(deftest the-frame-does-not-jump-between-tabs
  ;; A tab with two lines and a tab with forty must produce the same number of
  ;; rows, or switching tabs moves everything under it.
  (let [g (assoc geo :width 60 :rows 24)
        a (count (:rows (ed/render (panelled) g)))
        b (count (:rows (ed/render (assoc-in (panelled) [:panel :tab] 1) g)))]
    (is (= a b))))

(deftest a-long-tab-says-where-it-is
  (let [{:keys [rows]} (ed/render (assoc-in (panelled) [:panel :tab] 1)
                                  (assoc geo :width 60 :rows 24))]
    (is (some #(str/includes? % "/40") rows) "the panel did not say how much there is")
    (is (some #(str/includes? % "スクロール") rows))))


;; ---------------------------------------------------------------------------
;; the session cookie
;; ---------------------------------------------------------------------------

(deftest a-session-arrives-in-a-header-not-a-body
  ;; The claim endpoint hands the session over as a Set-Cookie and sends a body
  ;; that carries `:ready?` and nothing secret. A client reading only the body
  ;; waits for a token that is never coming -- which is what it did, running
  ;; the poll to its timeout with the person already signed in.
  (let [h "cloud_itonami_identity=abc123XYZ; Path=/; HttpOnly; SameSite=Strict; Max-Age=1209600"]
    (is (= "abc123XYZ" (text/cookie-value "cloud_itonami_identity" h))))
  ;; several cookies in one header, and the wanted one not first
  (is (= "tok" (text/cookie-value "cloud_itonami_identity"
                                  "other=1; Path=/, cloud_itonami_identity=tok; HttpOnly")))
  ;; a name that merely ENDS with the wanted one must not match
  (is (nil? (text/cookie-value "cloud_itonami_identity"
                               "x_cloud_itonami_identity=nope; Path=/")))
  (is (nil? (text/cookie-value "cloud_itonami_identity" "")))
  (is (nil? (text/cookie-value "cloud_itonami_identity" nil))))


;; ---------------------------------------------------------------------------
;; a panel you can choose from
;; ---------------------------------------------------------------------------

(def ^:private list-panel
  {:title "model routing" :tab 0 :index 0 :scroll 0
   :tabs [{:label "Bot" :task "bot" :scope "default"
           :items [{:label "murakumo-main" :model "murakumo-main" :provider-id "murakumo"}
                   {:label "murakumo-edge" :model "murakumo-edge" :provider-id "murakumo"}
                   {:label "third" :model "third" :provider-id "murakumo"}]}
          {:label "Chat" :task "chat" :scope "default"
           :items [{:label "only" :model "only" :provider-id "murakumo"}]}]})

(defn- listed [] (assoc (ed/fresh [] cmds) :panel list-panel))

(deftest a-list-panel-moves-a-selection-not-a-scrollbar
  ;; /model printed a routing document that contains no model names at all and
  ;; offered nothing to pick — the same as not having answered.
  (let [s (press* (listed) :down)]
    (is (= 1 (get-in s [:panel :index])))
    (is (= "murakumo-edge" (:model (ed/panel-choice (:panel s)))))
    (is (= 0 (get-in (press* s :up :up) [:panel :index])) "the selection ran off the top")
    (is (= 2 (get-in (press* s :down :down :down) [:panel :index]))
        "the selection ran off the bottom")))

(deftest enter-on-a-list-chooses-and-hands-the-choice-out
  ;; The editor is pure, so choosing produces a VALUE; the effect happens in
  ;; the caller. A panel that acted for itself would put an HTTP call inside
  ;; `handle` and the key semantics would stop being testable.
  (let [s (press* (listed) :down :enter)]
    (is (nil? (:panel s)) "the panel stayed open after choosing")
    (is (= "murakumo-edge" (get-in s [:chose :item :model])))
    (is (= "bot" (:task (ed/panel-tab (get-in s [:chose :panel])))))
    (is (= "default" (:scope (ed/panel-tab (get-in s [:chose :panel])))))
    ;; and it is consumed: the next key must not re-fire it
    (is (nil? (:chose (ed/handle s {:kind :char :ch "x"}))))))

(deftest enter-on-a-text-panel-still-just-closes
  (let [s (ed/handle (panelled) {:kind :enter})]
    (is (nil? (:panel s)))
    (is (nil? (:chose s)))))

(deftest changing-tab-starts-the-selection-again
  ;; Carrying index 2 into a tab with one item would point at nothing.
  (let [s (press* (listed) :down :down :tab)]
    (is (= 1 (get-in s [:panel :tab])))
    (is (= 0 (get-in s [:panel :index])))
    (is (= "only" (:model (ed/panel-choice (:panel s)))))))

(deftest a-list-panel-says-enter-decides
  (let [{:keys [rows]} (ed/render (listed) (assoc geo :width 60 :rows 24))]
    (is (some #(str/includes? % "▸ murakumo-main") rows) "the selection is not marked")
    (is (some #(str/includes? % "enter で決定") rows))))

;; `run-tests` returns nil under nbb, so the old
;;   (let [{:keys [fail error]} (run-tests 'ns)] (process.exit (if (pos? …) 1 0)))
;; destructured nil, added 0 to 0, and **exited 0 whether or not tests failed**.
;; Measured 2026-09-07 by breaking one assertion on purpose: the runner printed
;; "1 failures, 0 errors" and still exited 0. A suite that cannot report failure
;; reports that the process ended, not that the tests passed.
;;
;; cljs.test DOES hand the summary to the :end-run-tests report method.
(defmethod t/report [::t/default :end-run-tests] [m]
  (js/process.exit (if (t/successful? m) 0 1)))

(run-tests 'itonami-editor-nbb)
