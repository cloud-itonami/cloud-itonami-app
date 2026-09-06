#!/usr/bin/env nbb
;; itonami_text — what a terminal cell is, and how wide a string is in them.
;;
;; Split out of `itonami_splash` when the line editor needed the same answers
;; (ADR-2609062800). Two implementations of `display-width` would have been two
;; opinions about where a border goes, and the second one would have been
;; written by whoever noticed the first was somewhere else.
;;
;; The rule, unchanged: East Asian Wide and Fullwidth count two columns;
;; everything else -- box drawing and block elements included, which UAX#11
;; calls Ambiguous -- counts one, because that is what a terminal in a Latin
;; locale draws.

(ns itonami-text
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; colour
;; ---------------------------------------------------------------------------

(def ^:private esc (js/String.fromCharCode 27))

(defn sgr
  "One SGR sequence. A blank code means no sequence at all, so a caller with
  colour switched off emits nothing rather than an empty escape."
  [code]
  (if (str/blank? (str code)) "" (str esc "[" code "m")))

(def reset (sgr 0))

(defn paint
  "`s` wrapped in `code`, or `s` unchanged when `color?` is false. Colour is
  decided once, at the top, and threaded down — not re-derived at each call
  site, where one site would eventually forget."
  [color? code s]
  (if (and color? (not (str/blank? (str code)))) (str (sgr code) s reset) (str s)))


;; ---------------------------------------------------------------------------
;; width
;; ---------------------------------------------------------------------------

(def ^:private ansi-pattern (js/RegExp. (str esc "\\[[0-9;]*m") "g"))

(defn strip-ansi [s] (str/replace (str s) ansi-pattern ""))

(defn wide?
  "East Asian Wide (W) or Fullwidth (F): two columns in every terminal.

  Ambiguous (A) is deliberately absent. Box drawing, block elements and `·`
  are all Ambiguous, and a terminal in a Latin locale draws them in one
  column — which is what this frame is built out of. Counting them as two
  would push every border right by the width of the art."
  [cp]
  (or (<= 0x1100 cp 0x115F)
      (<= 0x2E80 cp 0x303E)
      (<= 0x3041 cp 0x33FF)
      (<= 0x3400 cp 0x4DBF)
      (<= 0x4E00 cp 0x9FFF)
      (<= 0xA000 cp 0xA4CF)
      (<= 0xAC00 cp 0xD7A3)
      (<= 0xF900 cp 0xFAFF)
      (<= 0xFE30 cp 0xFE6F)
      (<= 0xFF00 cp 0xFF60)
      (<= 0xFFE0 cp 0xFFE6)
      (<= 0x1F300 cp 0x1F64F)
      (<= 0x1F900 cp 0x1F9FF)))

(defn display-width
  "Columns `s` occupies once colour is stripped."
  [s]
  (let [t (strip-ansi s)]
    (loop [i 0 w 0]
      (if (>= i (.-length t))
        w
        (let [cp (.codePointAt t i)]
          (recur (+ i (if (> cp 0xFFFF) 2 1))
                 (+ w (if (wide? cp) 2 1))))))))

(defn pad-right
  "`s` padded with spaces to `n` columns. A string already wider than `n` is
  returned unchanged: truncating here would hide an overflow the caller needs
  to see, and `truncate` is where cutting is asked for by name."
  [s n]
  (let [w (display-width s)]
    (if (>= w n) (str s) (str s (.repeat " " (- n w))))))

(defn truncate
  "`s` cut to at most `n` columns, ending in `…` when it was cut."
  [s n]
  (let [t (str s)]
    (cond
      (<= n 0) ""
      (<= (display-width t) n) t
      :else
      (let [limit (dec n)]
        (loop [i 0 w 0]
          (if (>= i (.-length t))
            t
            (let [cp (.codePointAt t i)
                  cw (if (wide? cp) 2 1)]
              (if (> (+ w cw) limit)
                (str (subs t 0 i) "…")
                (recur (+ i (if (> cp 0xFFFF) 2 1)) (+ w cw))))))))))

(defn centre [s n]
  (let [w (display-width s)]
    (pad-right (str (.repeat " " (max 0 (quot (- n w) 2))) s) n)))

(defn shorten-path
  "`p` inside `n` columns: `$HOME` first becomes `~`, and only if that is
  still too long is the head dropped for `…/`. Dropping the head keeps the
  part an operator uses to tell two checkouts apart; `truncate` would have
  kept the part they share."
  [p home n]
  (let [p (str p)
        p (if (and (not (str/blank? (str home))) (str/starts-with? p (str home)))
            (str "~" (subs p (count (str home))))
            p)]
    (if (<= (display-width p) n)
      p
      (let [segs (str/split p #"/")]
        (loop [i 1]
          (let [tail (str "…/" (str/join "/" (drop i segs)))]
            (cond
              (<= (display-width tail) n) tail
              (>= i (dec (count segs))) (truncate (last segs) n)
              :else (recur (inc i)))))))))

(defn wrap-words
  "`words` packed into lines of at most `n` columns.

  The caller used to hand this in pre-wrapped at a fixed six per line, which
  the column then cut mid-name (`/bots…`, `/hist…`). A truncated command name
  is worse than a shorter list: it is not a name you can type. Only the width
  actually available can decide where the break goes, and that is known here."
  [words n]
  (if (empty? words)
    []
    (loop [[w & more] words line "" out []]
      (let [candidate (if (str/blank? line) (str w) (str line " " w))]
        (cond
          (nil? w) (if (str/blank? line) out (conj out line))
          (<= (display-width candidate) n) (recur more candidate out)
          (str/blank? line) (recur more "" (conj out (truncate (str w) n)))
          :else (recur (cons w more) "" (conj out line)))))))

