;; The wave is data and a pure function of it, so it is tested without a
;; terminal. What a terminal contributes is only the sleeping between frames,
;; and that is the one part not asserted here.
(ns itonami-wave-nbb
  (:require [clojure.test :as t :refer [deftest is run-tests]]
            [itonami-wave :as wave]
            [itonami-text :as text]))

(deftest every-frame-is-the-same-rectangle
  ;; Frames are overwritten in place by moving the cursor up a fixed number of
  ;; rows. A frame with a different row count would leave the previous frame's
  ;; tail on screen and walk the whole animation down the terminal; a row of a
  ;; different width would leave the previous frame's tail on that LINE. Both
  ;; are measured with display-width, not count, because the two disagree on
  ;; the block elements the wave is drawn in.
  (is (= 8 wave/frame-count))
  (is (= #{wave/rows-per-frame} (set (map count wave/frames)))
      "every frame is the same height as the cursor movement assumes")
  (is (= #{wave/columns}
         (set (mapcat (fn [f] (map text/display-width f)) wave/frames)))
      "and every row is the same width"))

(deftest the-frames-are-drawn-in-block-elements-only
  ;; Anything outside this set is a glyph whose width a terminal may disagree
  ;; about, and one disagreement tears the frame.
  (let [allowed #{\space \█ \▀ \▄ \▌ \░ \▒ \▓ \·}
        used (into #{} (mapcat seq (mapcat identity wave/frames)))]
    (is (empty? (remove allowed used))
        (str "unexpected glyphs: " (pr-str (vec (remove allowed used)))))))

(deftest fuji-is-in-every-frame
  ;; The wave rises and breaks; Fuji does not move. If a frame loses it, the
  ;; mountain blinks.
  (doseq [i (range wave/frame-count)]
    (is (some #(re-find #"░░░" %) (wave/frame i))
        (str "frame " (inc i) " lost Fuji"))))

(deftest render-paints-and-preserves-shape
  (let [plain (wave/render 3 false)
        tinted (wave/render 3 true ["38;5;120" "38;5;65"])]
    (is (= wave/rows-per-frame (count plain) (count tinted)))
    (is (= (wave/frame 3) plain) "colour off returns the frame unchanged")
    (is (some #(re-find #"38;5;120" %) tinted) "the ramp's head is used")
    (is (some #(re-find #"38;5;65" %) tinted) "and so is its tail")))

(deftest out-of-range-shows-the-finished-wave
  ;; Rather than nil, which a caller would print as a blank band.
  (is (= (wave/frame (dec wave/frame-count)) (wave/frame 999)))
  (is (= (wave/frame 0) (wave/frame -5))))

(deftest playable-refuses-for-three-real-reasons
  (is (wave/playable? {:tty? true :color? true :disabled? false}))
  (is (not (wave/playable? {:tty? false :color? true :disabled? false}))
      "a pipe would receive eighty lines of noise")
  (is (not (wave/playable? {:tty? true :color? false :disabled? false}))
      "NO_COLOR: legible, but not the point of it")
  (is (not (wave/playable? {:tty? true :color? true :disabled? true}))
      "ITONAMI_NO_INTRO, for anyone who opens this a hundred times a day"))

(defmethod t/report [::t/default :end-run-tests] [m]
  (js/process.exit (if (t/successful? m) 0 1)))

(run-tests 'itonami-wave-nbb)
