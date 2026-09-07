;; itonami splash — the start screen an argless `itonami` opens on
;; (ADR-2609062240), as a harness PLUGIN (ADR-2609042200).
;;
;; ## Why this file exists
;;
;; `itonami` with no arguments printed eight lines of help and exited. Every
;; other agent CLI on this machine — claude, hermes-agent — opens its REPL
;; instead and names, on that first screen, what it can actually do. The help
;; text this replaces had already begun claiming the REPL ("itonami — chat
;; REPL を開く") while `-main` still exited on an empty argv: the screen and
;; the code disagreed, and the screen was the one that lied.
;;
;; ## What it is allowed to say
;;
;; Every line is read from something: the command tables, the resolved profile
;; and its source, the keychain, and one measured `/health`. Nothing is
;; asserted that was not looked up. A start screen that says "connected"
;; without having asked is the sixth of CLAUDE.md's seven questions — a check
;; that could not run must not return the value of a check that ran and
;; passed — so `:server` has three states here, not two: answering, refused,
;; and not measured.
;;
;; ## Width
;;
;; The frame is aligned with `display-width`, which counts East Asian Wide and
;; Fullwidth as two columns and everything else — box drawing and block
;; elements included, which UAX#11 calls Ambiguous — as one. That is what a
;; terminal in a Latin locale does. Warnings print BELOW the frame, where a
;; glyph whose width a terminal disagrees about cannot push a border off.

(ns itonami-splash
  (:require [clojure.string :as str]
            [itonami-text :as text]))

;; ---------------------------------------------------------------------------
;; colour and width come from itonami-text, which the line editor shares
;; ---------------------------------------------------------------------------

(def sgr text/sgr)
(def reset text/reset)
(def paint text/paint)
(def strip-ansi text/strip-ansi)
(def wide? text/wide?)
(def display-width text/display-width)
(def pad-right text/pad-right)
(def truncate text/truncate)
(def centre text/centre)
(def wrap-words text/wrap-words)
(def shorten-path text/shorten-path)

;; The palette. Green (owner instruction 2026-09-06); a skin may replace it,
;; and `splash-plugin` reads the active skin at render time to do exactly that.
(def wordmark-ramp ["38;5;120" "38;5;114" "38;5;78" "38;5;71" "38;5;65"])
(def accent-code "38;5;114")
(def label-code "38;5;108")
(def dim-code "2")

;; ---------------------------------------------------------------------------
;; the wordmark
;; ---------------------------------------------------------------------------

(def ^:private glyphs
  {"I" ["██████" "  ██  " "  ██  " "  ██  " "██████"]
   "T" ["██████" "  ██  " "  ██  " "  ██  " "  ██  "]
   "O" ["██████" "██  ██" "██  ██" "██  ██" "██████"]
   "N" ["██  ██" "███ ██" "██████" "██ ███" "██  ██"]
   "A" ["██████" "██  ██" "██████" "██  ██" "██  ██"]
   "M" ["██    ██" "███  ███" "██ ██ ██" "██ ██ ██" "██    ██"]
   " " ["  " "  " "  " "  " "  "]})

(defn wordmark
  "`word` in five rows of block glyphs, tinted from the top down.

  A letter with no glyph is skipped rather than drawn as a hole, so this
  degrades to a shorter wordmark instead of a broken one."
  ([word color?] (wordmark word color? wordmark-ramp))
  ([word color? ramp]
   (let [cells (keep glyphs (str/upper-case (str word)))
         ramp (or (seq ramp) wordmark-ramp)]
     (when (seq cells)
       (vec (map-indexed
             (fn [row code]
               (paint color? code (str/join " " (map #(nth % row) cells))))
             ramp))))))

;; ---------------------------------------------------------------------------
;; the frame
;; ---------------------------------------------------------------------------

(defn frame
  "A titled box around `lines`, `inner` columns wide inside the borders.

  `lines` may already carry colour; they are padded by display width, so a
  colour sequence inside a cell cannot move the right border."
  [{:keys [title inner color?]} lines]
  (let [dim (fn [s] (paint color? dim-code s))
        title (str title)
        ;; "╭─ " + title + " " + fill + "╮" has to come to the same width as
        ;; "│" + " " + inner + "│", which is inner + 3.
        fill (max 0 (- inner (display-width title) 2))
        top (str (dim "╭─ ") (paint color? accent-code title) " "
                 (dim (text/rule "─" fill)) (dim "╮"))
        bottom (str (dim "╰") (dim (text/rule "─" (inc inner))) (dim "╯"))]
    (concat [top]
            (map (fn [l] (str (dim "│") " " (pad-right l inner) (dim "│"))) lines)
            [bottom])))

;; ---------------------------------------------------------------------------
;; the two columns
;; ---------------------------------------------------------------------------

(def mark
  "A lantern. Block elements and `·` only — every glyph is one column in a
  Latin-locale terminal, which is what `display-width` assumes."
  ["    ▁▁▁▁▁"
   "  ▕███████▏"
   " ·:::::::::·"
   "·:::       :::·"
   "·::    █    ::·"
   "·::   ███   ::·"
   "·:::       :::·"
   " ·:::::::::·"
   "  ▕███████▏"
   "    ▔▔▔▔▔"])

(defn left-column
  "The mark, then who this session is: profile, where the profile came from,
  the working directory, and where the session token was found."
  [{:keys [color? width profile profile-source cwd home session]}]
  (concat
   (map #(paint color? accent-code (centre % width)) mark)
   [""
    (paint color? accent-code (truncate (str profile) width))
    (paint color? dim-code (truncate (str "profile · " profile-source) width))
    ""
    (paint color? dim-code (shorten-path cwd home width))
    (paint color? dim-code (truncate (str "session: " session) width))]))

(defn section
  "A titled block of `entries`, each `[label body]`, each cut to `width`.

  An entry whose label is nil is a plain continuation line — the tail of a
  list is not a category, and labelling it as one invents a group name that
  no table contains."
  [{:keys [color? width title]} entries]
  (concat
   [(paint color? accent-code title)]
   (map (fn [[label body]]
          (if (str/blank? (str label))
            (paint color? dim-code (truncate (str body) width))
            (let [head (str label ": ")
                  room (max 8 (- width (display-width head)))]
              (str (paint color? label-code head)
                   (paint color? dim-code (truncate (str body) room))))))
        entries)))

(defn two-column
  "`left` and `right` side by side. Short columns are padded, not dropped: an
  unpadded blank on the left would slide the whole right column into it."
  [left right left-width gap]
  (let [pad (.repeat " " gap)]
    (vec (for [i (range (max (count left) (count right)))]
           (str (pad-right (nth left i "") left-width) pad (nth right i ""))))))

(defn summarise
  "`[label body]` rows for the `n` largest first-word groups of `cmds`, then
  one label-less row naming the groups that did not fit.

  The tail is named rather than dropped: a screen that lists five of
  twenty-five groups and says nothing about the other twenty is telling the
  operator those twenty do not exist."
  [cmds n]
  (let [groups (->> cmds
                    (group-by (comp first :command))
                    (sort-by (fn [[_ cs]] (- (count cs)))))
        shown (take n groups)
        remaining (drop n groups)]
    (cond-> (vec (for [[g cs] shown]
                   [g (let [tails (remove str/blank?
                                          (map #(str/join " " (rest (:command %))) cs))]
                        (if (seq tails)
                          (str (str/join ", " (take 3 tails))
                               (when (> (count tails) 3)
                                 (str ", +" (- (count tails) 3) " more")))
                          (str (count cs) (if (= 1 (count cs)) " command" " commands"))))]))
      (seq remaining)
      (conj [nil (str "+" (count remaining) " more: "
                      (str/join " · " (map first remaining)))]))))

;; ---------------------------------------------------------------------------
;; render
;; ---------------------------------------------------------------------------

(defn render
  "The start screen, as one string.

  `facts` is everything that was looked up before this was called; nothing
  here reaches for a file, an environment variable or a socket. That is what
  makes the screen testable, and it is why the caller measures `/health`
  rather than this."
  [{:keys [color? columns version base-url profile profile-source cwd home
           session groups named slash counts tagline bullet
           accent label dim ramp]
    :or {columns 88 version "?" base-url "?" profile "?" profile-source "?"
         cwd "" home "" session "?" counts [] tagline "" bullet "·"}}]
  (let [accent-code (or accent accent-code)
        label-code (or label label-code)
        dim-code (or dim dim-code)
        columns (max 68 (min 110 (or columns 88)))
        inner (- columns 4)
        gap 2
        left-w (max 26 (min 38 (quot inner 3)))
        ;; one column is held back so a full-width cell still has a space
        ;; before the border; a frame whose text touches its own rule reads
        ;; as an overflow even when nothing overflowed
        right-w (- inner left-w gap 1)
        left (vec (left-column {:color? color? :width left-w :profile profile
                                :profile-source profile-source :cwd cwd
                                :home home :session session}))
        right (vec (concat
                    (section {:color? color? :width right-w
                              :title "Available Commands"} groups)
                    [""]
                    (section {:color? color? :width right-w
                              :title "Named Commands"} named)
                    [""]
                    [(paint color? accent-code "REPL Commands")]
                    (map #(paint color? dim-code %) (wrap-words slash right-w))))
        tally (str/join (str " " bullet " ") counts)]
    (str/join
     "\n"
     (concat (or (wordmark "ITONAMI" color? ramp) [])
             [""]
             (when-not (str/blank? (str tagline))
               [(paint color? dim-code (truncate (str tagline) columns)) ""])
             (frame {:title (str "cloud-itonami-app v" version " " bullet " " base-url)
                     :inner inner :color? color?}
                    (concat [""]
                            (two-column left right left-w gap)
                            [""
                             (paint color? dim-code (truncate tally inner))
                             ""]))))))

;; ---------------------------------------------------------------------------
;; the plugin
;; ---------------------------------------------------------------------------

(defn splash-plugin
  "Provides :ctx/splash. Deps: :ctx/config (the install this describes) and
  :ctx/theme (so the skin that owns the prompt also tints the screen the
  prompt appears under)."
  []
  {:name :itonami.splash
   :inject [:ctx/config :ctx/theme]
   :provides :ctx/splash
   :description "start screen: wordmark, command inventory, measured status"
   :apply (fn [deps]
            (let [theme (:ctx/theme deps)]
              {:render (fn [facts]
                         ;; The skin is read HERE, at render time, so the
                         ;; injected dependency is one this layer actually
                         ;; consumes -- `itonami-profile` records what a
                         ;; declared-but-unread :inject cost the last time.
                         (let [skin ((:skin theme))]
                           (render (merge {:color? false
                                           :tagline (:banner skin)
                                           :bullet (:accent skin)
                                           :accent (:accent-code skin)
                                           :label (:label-code skin)
                                           :dim (:dim-code skin)
                                           :ramp (:ramp skin)}
                                          facts))))
               :prompt (fn [] (:prompt ((:skin theme))))}))})
