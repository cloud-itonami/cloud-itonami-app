#!/usr/bin/env nbb
;; itonami_editor — the input area: a multi-line editor, a frame, a status bar
;; (ADR-2609062800).
;;
;; ## Why not readline
;;
;; `node:readline/promises` gives one line and owns the row it is on. It cannot
;; draw a rule above and below itself, cannot put a status bar under the
;; cursor, and -- the reason this exists -- binds Up to history unconditionally.
;; In an editor holding more than one line, Up has to mean "the line above"
;; first, and "the previous thing I sent" only when there is no line above.
;; That is one keystroke readline will not give up.
;;
;; ## The split
;;
;; Everything in this file is pure: `handle` takes a state and a decoded key
;; and returns a state; `render` takes a state and a geometry and returns the
;; exact rows plus where the caret goes. Raw mode, byte reading and cursor
;; motion live in `bin/itonami`, and hold no rules of their own. That is what
;; lets the key semantics be tested without a TTY -- and the key semantics are
;; the whole point.
;;
;; ## What a state is
;;
;;   {:lines ["a" "b"]   ; logical lines; one per newline the operator made
;;    :row 1 :col 0      ; the caret, in logical coordinates
;;    :history [...]     ; oldest first, most recent last
;;    :hist nil          ; index while browsing history, nil while editing
;;    :stash nil}        ; the draft Up borrowed, so Down can give it back
;;
;; `:col` counts CHARACTERS. Display columns appear only in `render`, where the
;; width of a glyph is asked of `itonami-text`.

(ns itonami-editor
  (:require [clojure.string :as str]
            [itonami-text :as text]))

(def ^:private esc (js/String.fromCharCode 27))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defn fresh
  ([] (fresh []))
  ([history] {:lines [""] :row 0 :col 0
              :history (vec history) :hist nil :stash nil}))

(defn buffer
  "The whole input as one string, newlines where the operator put them."
  [{:keys [lines]}]
  (str/join "\n" lines))

(defn blank? [state] (str/blank? (buffer state)))

(defn- line-at [{:keys [lines row]}] (nth lines row ""))

(defn- put
  "Replace the buffer with `text`, caret at its end."
  [state text]
  (let [ls (vec (str/split (str text) #"\n" -1))
        ls (if (seq ls) ls [""])]
    (assoc state :lines ls :row (dec (count ls)) :col (count (peek ls)))))

;; ---------------------------------------------------------------------------
;; editing
;; ---------------------------------------------------------------------------

(defn- insert-text [{:keys [lines row col] :as s} t]
  (let [cur (nth lines row "")
        head (subs cur 0 col)
        tail (subs cur col)
        parts (vec (str/split (str t) #"\n" -1))]
    (if (= 1 (count parts))
      (assoc s :lines (assoc lines row (str head (first parts) tail))
               :col (+ col (count (first parts)))
               :hist nil)
      ;; A paste carrying newlines becomes real lines. Dropping them would
      ;; silently join two things the operator wrote apart.
      (let [mid (vec (concat [(str head (first parts))]
                             (subvec parts 1 (dec (count parts)))
                             [(str (peek parts) tail)]))]
        (assoc s :lines (vec (concat (subvec lines 0 row) mid
                                     (subvec lines (inc row))))
                 :row (+ row (dec (count parts)))
                 :col (count (peek parts))
                 :hist nil)))))

(defn- newline-at [{:keys [lines row col] :as s}]
  (let [cur (nth lines row "")]
    (assoc s :lines (vec (concat (subvec lines 0 row)
                                 [(subs cur 0 col) (subs cur col)]
                                 (subvec lines (inc row))))
             :row (inc row) :col 0 :hist nil)))

(defn- backspace [{:keys [lines row col] :as s}]
  (cond
    (pos? col)
    (let [cur (nth lines row "")]
      (assoc s :lines (assoc lines row (str (subs cur 0 (dec col)) (subs cur col)))
               :col (dec col) :hist nil))
    (pos? row)
    (let [prev (nth lines (dec row) "")
          cur (nth lines row "")]
      (assoc s :lines (vec (concat (subvec lines 0 (dec row))
                                   [(str prev cur)]
                                   (subvec lines (inc row))))
               :row (dec row) :col (count prev) :hist nil))
    :else s))

(defn- delete-forward [{:keys [lines row col] :as s}]
  (let [cur (nth lines row "")]
    (cond
      (< col (count cur))
      (assoc s :lines (assoc lines row (str (subs cur 0 col) (subs cur (inc col))))
               :hist nil)
      (< row (dec (count lines)))
      (assoc s :lines (vec (concat (subvec lines 0 row)
                                   [(str cur (nth lines (inc row) ""))]
                                   (subvec lines (+ row 2))))
               :hist nil)
      :else s)))

;; ---------------------------------------------------------------------------
;; motion — and the one rule this file exists for
;; ---------------------------------------------------------------------------

(defn- up
  "The line above, if there is one. Only at the top does Up reach for history.

  `:stash` holds the draft so Down can give it back: browsing history must not
  consume what was being written."
  [{:keys [row col lines history hist stash] :as s}]
  (cond
    (pos? row)
    (assoc s :row (dec row) :col (min col (count (nth lines (dec row) ""))))

    (empty? history) s

    :else
    (let [i (if (nil? hist) (count history) hist)]
      (if (zero? i)
        s
        (-> (put s (nth history (dec i)))
            (assoc :hist (dec i)
                   :stash (if (nil? hist) (buffer s) stash)))))))

(defn- down
  [{:keys [row col lines history hist stash] :as s}]
  (cond
    (< row (dec (count lines)))
    (assoc s :row (inc row) :col (min col (count (nth lines (inc row) ""))))

    (nil? hist) s

    (< (inc hist) (count history))
    (-> (put s (nth history (inc hist))) (assoc :hist (inc hist) :stash stash))

    ;; past the newest entry: the draft comes back
    :else (-> (put s (or stash "")) (assoc :hist nil :stash nil))))

(defn- left [{:keys [row col lines] :as s}]
  (cond (pos? col) (assoc s :col (dec col))
        (pos? row) (assoc s :row (dec row) :col (count (nth lines (dec row) "")))
        :else s))

(defn- right [{:keys [row col lines] :as s}]
  (cond (< col (count (nth lines row ""))) (assoc s :col (inc col))
        (< row (dec (count lines))) (assoc s :row (inc row) :col 0)
        :else s))

(defn- word-left [{:keys [col] :as s}]
  (let [cur (line-at s)
        head (subs cur 0 col)
        trimmed (str/replace head #"[^\p{L}\p{N}_]*[\p{L}\p{N}_]*$" "")]
    (if (= head trimmed) (left s) (assoc s :col (count trimmed)))))

(defn- word-right [{:keys [col] :as s}]
  (let [cur (line-at s)
        eaten (re-find #"^[^\p{L}\p{N}_]*[\p{L}\p{N}_]*" (subs cur col))]
    (if (str/blank? (str eaten)) (right s) (assoc s :col (+ col (count eaten))))))

;; ---------------------------------------------------------------------------
;; handle — one key, one state
;; ---------------------------------------------------------------------------

(defn handle
  "Apply one decoded key. A state carrying `:submit` is the caller's cue to
  take that string and start a turn; one carrying `:signal` names something
  only the caller can do (`:eof`, `:interrupt`)."
  [state {:keys [kind ch text]}]
  (let [s (dissoc state :submit :signal :cleared)]
    (case kind
      :char (insert-text s ch)
      ;; A paste is text even when it holds newlines: the operator moved it
      ;; here as one thing, and sending its first line is not what they did.
      :paste (insert-text s text)
      :newline (newline-at s)

      :enter
      ;; A trailing backslash is the continuation an operator can type without
      ;; a modifier: terminals disagree about Shift+Enter and several send a
      ;; bare Return for it, so a modifier-only newline is unreachable on some.
      (let [cur (line-at s)]
        (if (and (str/ends-with? cur "\\") (= (:col s) (count cur)))
          (-> (assoc s :lines (assoc (:lines s) (:row s)
                                     (subs cur 0 (dec (count cur))))
                       :col (dec (count cur)))
              newline-at)
          (let [t (buffer s)]
            (if (str/blank? t)
              s
              (assoc (fresh (if (= t (peek (:history s)))
                              (:history s)
                              (conj (:history s) t)))
                     :submit t)))))

      :backspace (backspace s)
      :delete (delete-forward s)
      :up (up s)
      :down (down s)
      :left (left s)
      :right (right s)
      :word-left (word-left s)
      :word-right (word-right s)
      :home (assoc s :col 0)
      :end (assoc s :col (count (line-at s)))
      :kill-to-end (let [cur (line-at s)]
                     (assoc s :lines (assoc (:lines s) (:row s) (subs cur 0 (:col s)))))
      :kill-to-start (let [cur (line-at s)]
                       (assoc s :lines (assoc (:lines s) (:row s) (subs cur (:col s)))
                                :col 0))
      ;; Ctrl+C clears what is being written; only an already-empty buffer
      ;; passes the signal up, so a half-typed line is never a lost session.
      :interrupt (if (blank? s)
                   (assoc s :signal :interrupt)
                   (assoc (fresh (:history s)) :cleared true))
      :eof (if (blank? s) (assoc s :signal :eof) (delete-forward s))
      s)))

;; ---------------------------------------------------------------------------
;; key decoding
;; ---------------------------------------------------------------------------

(def ^:private csi
  {"A" :up "B" :down "C" :right "D" :left
   "H" :home "F" :end "3~" :delete "1~" :home "4~" :end
   "1;5C" :word-right "1;5D" :word-left
   "1;3C" :word-right "1;3D" :word-left})

(defn decode
  "Bytes from a raw-mode terminal to a vector of keys.

  One chunk can carry several keystrokes -- a paste is one chunk -- so this
  returns a sequence and never a single key. An escape sequence it does not
  know is DROPPED rather than inserted: printing `[200~` into the buffer
  because the terminal announced a bracketed paste is worse than ignoring it."
  [s]
  (let [n (count s)]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [c (subs s i (inc i))
              code (.charCodeAt s i)]
          (cond
            (= c esc)
            (let [rest* (subs s (inc i))]
              (cond
                ;; Alt/Option+Enter: the newline that needs no backslash
                (or (str/starts-with? rest* "\r") (str/starts-with? rest* "\n"))
                (recur (+ i 2) (conj out {:kind :newline}))

                ;; Bracketed paste. Without it a pasted newline is the same
                ;; byte as Return, so pasting three lines SENDS the first and
                ;; drops the other two -- which is what this did before the
                ;; terminal was asked to bracket (measured 2026-09-06).
                (str/starts-with? rest* "[200~")
                (let [open (+ i 6)
                      close (str/index-of s (str esc "[201~") open)
                      end (or close n)]
                  (recur (if close (+ close 6) n)
                         (conj out {:kind :paste :text (subs s open end)})))

                (str/starts-with? rest* "[")
                ;; `?` `<` `=` `>` introduce a private sequence -- a terminal's
                ;; unsolicited device-attributes reply is `ESC[?1;2c`, and
                ;; without this it was typed into the line as `[?1;2c`.
                (if-let [m (re-find #"^\[[?<=>]?([0-9;]*[A-Za-z~])" rest*)]
                  (recur (+ i 1 (count (first m)))
                         (if-let [k (csi (second m))] (conj out {:kind k}) out))
                  (recur (inc i) out))

                (str/starts-with? rest* "O")
                (if-let [m (re-find #"^O([A-Za-z])" rest*)]
                  (recur (+ i 1 (count (first m)))
                         (if-let [k (csi (second m))] (conj out {:kind k}) out))
                  (recur (inc i) out))

                (str/starts-with? rest* "b") (recur (+ i 2) (conj out {:kind :word-left}))
                (str/starts-with? rest* "f") (recur (+ i 2) (conj out {:kind :word-right}))
                :else (recur (inc i) (conj out {:kind :escape}))))

            (or (= c "\r") (= c "\n")) (recur (inc i) (conj out {:kind :enter}))
            (= code 127) (recur (inc i) (conj out {:kind :backspace}))
            (= code 1) (recur (inc i) (conj out {:kind :home}))
            (= code 2) (recur (inc i) (conj out {:kind :left}))
            (= code 3) (recur (inc i) (conj out {:kind :interrupt}))
            (= code 4) (recur (inc i) (conj out {:kind :eof}))
            (= code 5) (recur (inc i) (conj out {:kind :end}))
            (= code 6) (recur (inc i) (conj out {:kind :right}))
            (= code 8) (recur (inc i) (conj out {:kind :backspace}))
            (= code 11) (recur (inc i) (conj out {:kind :kill-to-end}))
            (= code 14) (recur (inc i) (conj out {:kind :down}))
            (= code 16) (recur (inc i) (conj out {:kind :up}))
            (= code 21) (recur (inc i) (conj out {:kind :kill-to-start}))
            (< code 32) (recur (inc i) out)
            :else (recur (inc i) (conj out {:kind :char :ch c}))))))))

;; ---------------------------------------------------------------------------
;; render
;; ---------------------------------------------------------------------------

(defn chunk-line
  "`s` cut into pieces of at most `n` columns, each reporting the character
  index it starts at. Cutting by columns and reporting by characters is what
  lets the caret be placed on a line holding Japanese."
  [s n]
  (let [n (max 1 n)
        len (count s)]
    (loop [i 0 start 0 w 0 out []]
      (if (>= i len)
        (conj out {:start start :end i :text (subs s start i)})
        (let [cw (text/glyph-columns (.codePointAt s i))]
          (if (> (+ w cw) n)
            (recur i i 0 (conj out {:start start :end i :text (subs s start i)}))
            (recur (inc i) start (+ w cw) out)))))))

(defn visual-rows
  "Every terminal row the buffer occupies, and which logical line and character
  range each one shows."
  [{:keys [lines]} width]
  (vec (mapcat (fn [row line]
                 (map #(assoc % :row row) (chunk-line line width)))
               (range) lines)))

(defn caret
  "Where the caret sits among `visual-rows`: [visual-row display-column]."
  [rows {:keys [row col]}]
  (let [last-i (max 0 (dec (count rows)))
        i (or (first (keep-indexed
                      (fn [i r]
                        (when (and (= row (:row r))
                                   (<= (:start r) col)
                                   (or (< col (:end r))
                                       (= col (:end r))))
                          i))
                      rows))
              last-i)
        r (nth rows i {:start 0 :text ""})]
    [i (text/display-width (subs (:text r) 0 (max 0 (- col (:start r)))))]))

(defn status-line
  "The bar under the input. Left is what is true right now, right is the one
  command that explains the rest.

  Nothing here is a claim about the server: it is the client's own state, which
  is the only thing knowable without asking. `esc で中断` appears only while a
  run is actually interruptible.

  Segments are dropped from the right, whole, when the terminal is too narrow.
  Truncating instead cut a hint mid-word and left the operator reading half an
  instruction -- and a bar wider than the terminal wraps onto the row the caret
  is about to be moved to, which tears the frame."
  [{:keys [profile slash-count held running? queued colour accent-code dim-code]} width]
  (let [p #(text/paint colour %1 %2)
        head (p accent-code (str "\u25b6\u25b6 " profile))
        tail (concat (when (and queued (pos? queued))
                       [(str "\u23f8 " queued " 件待機 (/queue)")])
                     [(str slash-count " slash")]
                     (cond
                       held [(str "\u26a0 承認待ち: " held)
                             "/approve /deny"]
                       ;; A run in flight does not take the keyboard: the
                       ;; editor stays live, /steer and /stop reach the run,
                       ;; and a plain line joins the queue.
                       running? ["esc で中断" "入力は受け付けています"]
                       :else ["enter で送信" "\\ + enter で改行" "\u2191 で履歴"]))
        right (p dim-code "/help")
        room (- width (text/display-width right) 1)
        left (reduce (fn [acc seg]
                       (let [next* (str acc " \u00b7 " seg)]
                         (if (<= (text/display-width next*) room) next* acc)))
                     (text/truncate (str "\u25b6\u25b6 " profile) room)
                     tail)
        ;; colour goes on last, so the widths above are the widths on screen
        painted (str (p accent-code (subs left 0 (min (count left)
                                                      (count (str "\u25b6\u25b6 " profile)))))
                     (p dim-code (subs left (min (count left)
                                                 (count (str "\u25b6\u25b6 " profile))))))
        gap (max 1 (- width (text/display-width left) (text/display-width right)))]
    (str painted (.repeat " " gap) right)))

(defn elapsed
  "`ms` as a person reads a wait: seconds under a minute, minutes and seconds
  over one, hours and minutes over an hour."
  [ms]
  (let [total (quot (max 0 ms) 1000)
        h (quot total 3600)
        m (quot (rem total 3600) 60)
        sec (rem total 60)]
    (cond
      (pos? h) (str h "h " m "m")
      (pos? m) (str m "m " sec "s")
      :else (str sec "s"))))

(def spinner-frames ["\u2733" "\u2734" "\u2735" "\u2736" "\u2737" "\u2738" "\u2739"])

(defn progress-line
  "The one line shown while a run is in flight.

  It says what the RUN reported (its phase), how long it has been going, and
  what the stream has actually delivered. Every part of it is something that
  arrived: there is no estimate here, and no percentage, because the server
  does not send one and inventing it would be the only untrue thing on the
  screen."
  [{:keys [phase ms tick tokens colour accent-code dim-code interruptible?]} width]
  (let [p #(text/paint colour %1 %2)
        glyph (nth spinner-frames (mod (or tick 0) (count spinner-frames)))
        label (or (not-empty (str phase)) "working")
        detail (cond-> [(elapsed (or ms 0))]
                 (and tokens (pos? tokens)) (conj (str "\u2193 " tokens " tokens"))
                 interruptible? (conj "esc \u3067\u4e2d\u65ad"))
        line (str (p accent-code (str glyph " " label "\u2026 "))
                  (p dim-code (str "(" (str/join " \u00b7 " detail) ")")))]
    (text/truncate line (max 8 width))))

(defn render
  "The rows to draw and where the caret goes.

  Returns `{:rows [...] :caret [visual-row column]}`. The caret column is
  measured from the start of the row INCLUDING the prompt, so the terminal
  half has nothing left to compute."
  [state {:keys [width prompt continuation colour accent-code dim-code status header
                 tail]}]
  (let [width (max 24 (or width 80))
        prompt (or prompt "> ")
        pw (text/display-width prompt)
        continuation (or continuation (.repeat " " pw))
        ;; one column held back: a row that exactly fills the terminal makes it
        ;; wrap on its own, and then every row below it is one off
        cw (max 8 (- width pw 1))
        rows (visual-rows state cw)
        [vr vc] (caret rows state)
        rule (text/paint colour dim-code (text/rule "─" width))
        ;; The answer's incomplete last line. It is a ROW OF THE BLOCK, not a
        ;; partial line the terminal is left holding, because a partial line
        ;; wider than the terminal wraps and then no amount of cursor
        ;; arithmetic finds its end again (measured 2026-09-07: a streamed line
        ;; of sixty numbers resumed at the last column of the wrong row). It
        ;; graduates to the scrollback the moment its newline arrives.
        tail-rows (when (seq (str tail))
                    (map :text (chunk-line (str tail) width)))
        body (map-indexed
              (fn [i {:keys [text]}]
                (str (text/paint colour accent-code (if (zero? i) prompt continuation))
                     text))
              rows)]
    ;; `header` is the progress line while a run is going. It is a ROW OF THE
    ;; BLOCK rather than a line of its own, because a line of its own has to be
    ;; told where the block is, and the two then disagree the moment either
    ;; moves. As a row it is redrawn with everything else and cannot collide.
    ;; The tail first: it continues the answer in the scrollback directly above
    ;; it, and putting the progress line between them would cut the sentence in
    ;; half. The progress line then sits immediately over the frame, where it
    ;; is read as belonging to the turn rather than to the text.
    {:rows (vec (concat tail-rows (when header [header])
                        [rule] body [rule] [(or status "")]))
     ;; +1 for the rule above the first input row, plus whatever is above it
     :caret [(+ vr 1 (if header 1 0) (count tail-rows)) (+ pw vc)]}))
