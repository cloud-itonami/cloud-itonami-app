;; itonami wave — 神奈川沖浪裏, animated, as the logo's opening.
;;
;; ## What it is
;;
;; Owner, 2026-09-07: ロゴの方は hokusai の 富嶽 波のアニメーションを。
;;
;; Eight frames of Hokusai's Great Wave off Kanagawa: a calm sea with Fuji on
;; the horizon, a swell, the rise, the crest opening its hollow, the curl, the
;; claws, the break, and the wave standing. It plays once when `itonami` opens
;; and is then erased, so the start screen keeps the height it had.
;;
;; ## Why one colour is not a compromise
;;
;; The frames carry no colour of their own; each row is tinted from the active
;; skin's ramp, exactly as the wordmark is, so the wave is green here and gold
;; under the gold skin. A single-ink Great Wave is not a limitation of the
;; terminal — 神奈川沖浪裏 is a famous 藍摺 print, and reading it in one ink is
;; how most people have always seen it.
;;
;; ## Geometry
;;
;; Every frame is ten rows of exactly fifty columns, in `█ ▀ ▄ ▌ ░ ▒ ▓ ·` only
;; — the same block elements the mark uses, so `display-width` counts them the
;; same way. The frames are drawn in place by moving the cursor up ten rows,
;; never by clearing the screen: a start screen that erases scrollback destroys
;; what the operator was reading a moment ago.
;;
;; `frames` is data and `render` is a pure function of it, so the animation is
;; testable without a terminal. What a terminal adds is only the sleeping.

(ns itonami-wave
  (:require [itonami-text :as text]))

(def paint text/paint)
(def display-width text/display-width)

;; The palette is the caller's. Green by default (owner, 2026-09-06).
(def default-ramp ["38;5;120" "38;5;114" "38;5;78" "38;5;71" "38;5;65"])

(def rows-per-frame 10)
(def columns 50)


;; The eight frames. Hand-authored: a wave is a drawing, not a function of t.
(def frames
  [
   ;; 1. calm — Fuji on the horizon
   ["                                                  "
    "                                                  "
    "                                                  "
    "                                     ▄            "
    "                                    ▄▀▄           "
    "                                  ▄▀░░░▀▄         "
    "                                ▄▀░░░░░░░▀▄       "
    "                           ▄▄▄▀▀▀▀▀▀▀▀▀▀▀▀▀▀▄▄▄   "
    "    ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄████████████████████████"
    "      ░░▒▒▓▓██████████████████████████████████████"]
   ;; 2. the swell
   ["                                                  "
    "                                                  "
    "                                                  "
    "                                     ▄            "
    "                                    ▄▀▄           "
    "   ▄▄▄                            ▄▀░░░▀▄         "
    " ▄█████▄                        ▄▀░░░░░░░▀▄       "
    " ███████▄▄▄                ▄▄▄▀▀▀▀▀▀▀▀▀▀▀▀▀▀▄▄▄   "
    "    ▀▀█████▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄████████████████████████"
    "      ░░▒▒▓▓██████████████████████████████████████"]
   ;; 3. rising
   ["                                                  "
    "                                                  "
    "   ▄▄▄▄▄                                          "
    " ▄████████▄                          ▄            "
    " ██████████▄                        ▄▀▄           "
    " █████████████▄                   ▄▀░░░▀▄         "
    " ▀███████                       ▄▀░░░░░░░▀▄       "
    "  ▀████▄▄▄                 ▄▄▄▀▀▀▀▀▀▀▀▀▀▀▀▀▀▄▄▄   "
    "    ▀▀█████▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄████████████████████████"
    "      ░░▒▒▓▓██████████████████████████████████████"]
   ;; 4. the crest opens its hollow
   ["     ▄▄▄▄▄▄                                       "
    "   ▄█████████▄                                    "
    "  ████████████▄▄                                  "
    " ██████▀▀▀██████▄                    ▄            "
    " █████     ▀▀████▄▄                 ▄▀▄           "
    " ████            ▀▀▄              ▄▀░░░▀▄         "
    " ▀███▄                          ▄▀░░░░░░░▀▄       "
    "  ▀████▄▄                  ▄▄▄▀▀▀▀▀▀▀▀▀▀▀▀▀▀▄▄▄   "
    "    ▀▀█████▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄████████████████████████"
    "      ░░▒▒▓▓██████████████████████████████████████"]
   ;; 5. curling
   ["     ▄▄▄▄    ▄                                    "
    "   ▄███████▄██▄                                   "
    "  ███████████████▄                                "
    " ██████▀▀    ▀▀████▄▄                ▄            "
    " █████           ▀▀███▄             ▄▀▄           "
    " ████                ▀█▌          ▄▀░░░▀▄         "
    " ▀███▄                          ▄▀░░░░░░░▀▄       "
    "  ▀████▄▄                  ▄▄▄▀▀▀▀▀▀▀▀▀▀▀▀▀▀▄▄▄   "
    "    ▀▀█████▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄████████████████████████"
    "      ░░▒▒▓▓██████████████████████████████████████"]
   ;; 6. claws out
   ["     ▄▄▄▄    ▄  ▄                                 "
    "   ▄███████▄██▄██▄                                "
    "  ██████████████████▄                             "
    " ██████▀▀     ▀▀▀█████▄▄             ▄            "
    " █████            ▀▀████▄           ▄▀▄           "
    " ████                 ▀██▌        ▄▀░░░▀▄         "
    " ▀███▄                          ▄▀░░░░░░░▀▄       "
    "  ▀████▄▄                  ▄▄▄▀▀▀▀▀▀▀▀▀▀▀▀▀▀▄▄▄   "
    "    ▀▀█████▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄████████████████████████"
    "      ░░▒▒▓▓██████████████████████████████████████"]
   ;; 7. breaking, foam scatters
   ["     ▄▄▄▄    ▄  ▄   ·  ·                          "
    "   ▄███████▄██▄██▄ ·   ·  ·                       "
    "  ██████████████████▄▄  ·   ·                     "
    " ██████▀▀     ▀▀▀█████▄▄  ·          ▄            "
    " █████            ▀▀████▄  ·        ▄▀▄           "
    " ████                 ▀██▌  ·     ▄▀░░░▀▄         "
    " ▀███▄                          ▄▀░░░░░░░▀▄       "
    "  ▀████▄▄                  ▄▄▄▀▀▀▀▀▀▀▀▀▀▀▀▀▀▄▄▄   "
    "    ▀▀█████▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄████████████████████████"
    "      ░░▒▒▓▓██████████████████████████████████████"]
   ;; 8. the wave stands
   ["     ▄▄▄▄    ▄  ▄                                 "
    "   ▄███████▄██▄██▄                                "
    "  ██████████████████▄▄                            "
    " ██████▀▀     ▀▀▀█████▄▄             ▄            "
    " █████            ▀▀████▄           ▄▀▄           "
    " ████                 ▀██▌        ▄▀░░░▀▄         "
    " ▀███▄                          ▄▀░░░░░░░▀▄       "
    "  ▀████▄▄                  ▄▄▄▀▀▀▀▀▀▀▀▀▀▀▀▀▀▄▄▄   "
    "    ▀▀█████▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄████████████████████████"
    "      ░░▒▒▓▓██████████████████████████████████████"]
  ])

(def frame-count (count frames))

(defn frame
  "Frame `n`, clamped. Out of range returns the last frame rather than nil, so
  a caller that miscounts shows the finished wave instead of nothing."
  [n]
  (nth frames (max 0 (min (dec frame-count) (long (or n 0))))))

(defn render
  "Frame `n` as painted rows, tinted top to bottom from `ramp`."
  ([n color?] (render n color? default-ramp))
  ([n color? ramp]
   (let [rows (frame n)
         ramp (vec (or (seq ramp) default-ramp))
         k (count ramp)]
     (vec (map-indexed
           (fn [i r]
             (paint color? (nth ramp (min (dec k) (quot (* i k) (count rows)))) r))
           rows)))))

(defn playable?
  "Whether to play at all.

  Three ways to say no, and each is a real situation rather than a preference:
  output that is not a terminal (the frames would be written to a pipe as
  eighty lines of noise), colour turned off (the wave is legible but the point
  of it is not), and `ITONAMI_NO_INTRO` set, which is the answer for anyone who
  opens this a hundred times a day."
  [{:keys [tty? color? disabled?]}]
  (boolean (and tty? color? (not disabled?))))
