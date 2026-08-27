(ns cloud.itonami.app.issue-comment
  "Comment mode: a region of this application's own screen, a sentence about
  it, and the bounded Goal that sentence becomes.

  The parts that decide anything live here and are portable, so the shape of
  what a Bot is asked can be tested without a browser, a session, or a
  provider. Storing the picture and starting the turn stay in the handler.

  ## Why the crop is an SVG and not a PNG

  The page cannot rasterise. Its CSP is `img-src 'self'` — no `data:`, no
  `blob:` — which ADR-0007 chose deliberately: 「`data:` would let any string in
  the page become an image」. Rasterising a DOM subtree in a browser means
  loading an SVG into an `<img>`, and that load is exactly what the policy
  refuses. Measured 2026-08-27: under this CSP a ten-pixel `<rect>` from a blob
  URL fails to load, and `createImageBitmap` cannot decode SVG in Chrome at
  all, so there is no rasterising path left that does not weaken the policy.

  So the crop stays vector. The client serialises the selected subtree with its
  computed styles into an SVG document, the server stores that, and it is
  served from its own URL — a top-level navigation, which `img-src` does not
  govern. Nothing about the application page's policy changes.

  ## Why the picture is not evidence the model saw

  A Bot receives images only through a TOOL RESULT — `run-tool!` carries
  `:images` and `tool-messages` attaches them to the following user turn.
  `bots/send!` takes text and nothing else. So a screenshot posted with a
  comment is a record for the person, not model input, and the Goal text says
  so in those words. Writing \"添付した画像を見て\" into a prompt whose transport
  drops the image is the exact shape this repository keeps refusing: a claim
  that reads like evidence and was never measured.

  What actually lets a Bot fix the thing is the DOM descriptor. This
  application renders its own screens from `web.clj` hiccup and drives them
  from `interaction.js`, so a selector, the element's own text, and the view
  name are all searchable strings in the repository the Bot is admitted to."
  (:require [clojure.string :as str]))

(def max-comment-chars
  "One comment. Long enough for a paragraph of intent, short enough that the
  Goal text below stays inside `bots/max-message-chars` (8000) with the
  descriptor and the contract beside it."
  1200)

(def max-element-text-chars
  "How much of the selected element's own text is quoted back.

  Enough to be searchable in the repository, bounded because a region can
  legitimately cover a whole panel and the point is to name the thing, not to
  ship the screen as prose."
  400)

(def max-selector-chars 400)

(def max-image-bytes
  "Ceiling for one region crop, in UTF-8 bytes of SVG.

  A crop of a region on one screen; anything past this is a whole-page capture
  arriving under the wrong name. Measured 2026-08-27: the sign-in page's whole
  navigation column serialised to 130 KB with the curated property set."
  (* 4 1024 1024))

(defn- trimmed
  "`nil` for blank, otherwise the trimmed string cut to `limit` with an
  explicit ellipsis. The ellipsis is part of the contract: a reader must be
  able to tell a quotation that ended from one that was cut."
  [value limit]
  (let [text (str/trim (str (or value "")))]
    (cond
      (str/blank? text) nil
      (<= (count text) limit) text
      :else (str (subs text 0 limit) "…"))))

(defn- finite-long
  "A non-negative whole number from JSON, or nil. JSON numbers arrive as
  integer or floating point depending on how the client wrote them, and
  `NaN`/`Infinity` cannot be admitted into a rectangle."
  [value]
  (when (number? value)
    (let [n (double value)]
      (when (and #?(:clj (not (Double/isNaN n)) :cljs (not (js/isNaN n)))
                 #?(:clj (not (Double/isInfinite n)) :cljs (js/isFinite n))
                 (>= n 0)
                 (< n 1e7))
        (long #?(:clj (Math/round n) :cljs (js/Math.round n)))))))

(defn region
  "The selected rectangle in CSS pixels, or nil when the client did not send
  one that can be described.

  A zero-width or zero-height rectangle is refused rather than normalised: it
  means the drag never happened, and a comment attached to nothing is worse
  than a comment attached to the view."
  [raw]
  (let [x (finite-long (:x raw))
        y (finite-long (:y raw))
        w (finite-long (:width raw))
        h (finite-long (:height raw))]
    (when (and x y w h (pos? w) (pos? h))
      (cond-> {:x x :y y :width w :height h}
        (finite-long (:viewport-width raw))
        (assoc :viewport-width (finite-long (:viewport-width raw)))
        (finite-long (:viewport-height raw))
        (assoc :viewport-height (finite-long (:viewport-height raw)))
        (number? (:device-pixel-ratio raw))
        (assoc :device-pixel-ratio (double (:device-pixel-ratio raw)))))))

(defn element
  "The DOM descriptor for what the region covers, or nil.

  Every field is optional except the selector, because the selector is the
  only one a Bot can search for. An element with no selector is a rectangle,
  and `region` already says that."
  [raw]
  (when-let [selector (trimmed (:selector raw) max-selector-chars)]
    (cond-> {:selector selector}
      (trimmed (:tag raw) 40) (assoc :tag (str/lower-case (trimmed (:tag raw) 40)))
      (trimmed (:id raw) 120) (assoc :id (trimmed (:id raw) 120))
      (trimmed (:text raw) max-element-text-chars)
      (assoc :text (trimmed (:text raw) max-element-text-chars))
      (seq (:classes raw))
      (assoc :classes (into [] (comp (map #(trimmed % 80)) (filter some?) (take 12))
                            (:classes raw)))
      (seq (:data raw))
      (assoc :data (into {} (comp (map (fn [[k v]] [(name k) (trimmed v 120)]))
                                  (filter (fn [[_ v]] (some? v)))
                                  (take 12))
                         (:data raw))))))

(defn- describe-region [{:keys [x y width height viewport-width viewport-height
                                device-pixel-ratio]}]
  (str "x=" x " y=" y " w=" width " h=" height " (CSS px"
       (when (and viewport-width viewport-height)
         (str ", viewport " viewport-width "×" viewport-height))
       (when device-pixel-ratio (str ", dpr " device-pixel-ratio))
       ")"))

(defn- describe-element [{:keys [selector tag id text classes data]}]
  (->> [(str "- 要素: " selector)
        (when tag (str "- タグ: " tag))
        (when id (str "- id: " id))
        (when (seq classes) (str "- class: " (str/join " " classes)))
        (when (seq data)
          (str "- data 属性: "
               (str/join ", " (map (fn [[k v]] (str "data-" k "=\"" v "\"")) data))))
        (when text (str "- 要素のテキスト: \"" text "\""))]
       (remove nil?)
       (str/join "\n")))

(defn goal-text
  "The bounded Goal a comment becomes.

  Says what was observed, where it is in this repository, and what counts as
  finishing. The contract lines match the resident-tick contract already used
  by the workforce: advance one verified step, separate observation from
  proposal, and name one prerequisite rather than inventing a way around it."
  [{:keys [id comment view region element image]}]
  (str/join
   "\n"
   (remove
    nil?
    ["画面コメント（Cloud Itonami の UI について、その画面を見ている人からの指摘）"
     (str "受付ID: " id)
     ""
     (str "コメント: " comment)
     ""
     "観測された画面:"
     (when view (str "- ビュー: " view))
     (when element (describe-element element))
     (when region (str "- 選択範囲: " (describe-region region)))
     (if (:url image)
       (str "- 画像: " (:url image)
            "（この範囲の切り抜き。人が後から見るための記録で、"
            "あなたはこの画像を見ていません。判断は上の文字情報だけで行ってください）")
       "- 画像: ありません（切り抜きに失敗したか、送られませんでした）")
     ""
     (str "この画面は Cloud Itonami 自身の UI です。描画元は "
          "`src/cloud/itonami/app/web.clj`（hiccup と CSS）と "
          "`resources/cloud/itonami/app/interaction.js`（操作層）にあります。"
          "上の selector・class・要素テキストは、どれもその2つのファイルの中の"
          "文字列として検索できます。")
     ""
     "契約:"
     "- admitted repository の証拠で、検証できる1歩だけ進める。"
     "- 観測した事実と提案を分けて述べる。外部への影響にはその grant が要る。"
     "- 原因を1つ特定し、最小の変更を提案する。"
     "- 特定できないなら、足りない前提を1つだけ名指しして止まる。"])))

(defn request
  "Validate one posted comment into the record the handler stores, or throw
  the same shape of `ex-info` the rest of this application's handlers throw.

  Refuses rather than repairs. A comment mode whose empty submission silently
  became a Goal about the whole view would be the failure this repository
  keeps naming: an action that could not say what it was about, reported the
  same way as one that could."
  [{:keys [comment view bot-id] :as raw}]
  (let [text (trimmed comment max-comment-chars)
        selected (element (:element raw))
        rect (region (:region raw))]
    (when (str/blank? (str text))
      (throw (ex-info "コメントが空です。" {:type :issue-comment/empty})))
    (when-not (or selected rect)
      (throw (ex-info "画面のどこについてのコメントかが送られていません。"
                      {:type :issue-comment/no-target})))
    (when (str/blank? (str bot-id))
      (throw (ex-info "宛先の Bot が選ばれていません。"
                      {:type :issue-comment/no-bot})))
    (cond-> {:comment text
             :bot-id (str/trim (str bot-id))}
      (trimmed view 60) (assoc :view (trimmed view 60))
      selected (assoc :element selected)
      rect (assoc :region rect))))

(def ^:private script-shaped
  "Constructs that make a stored SVG executable if it is ever opened directly.

  The response that serves it is sandboxed and `default-src 'none'`, so this is
  the second lock rather than the only one. It is here because the crop is built
  by cloning the live DOM, and the live DOM shows content this application did
  not write — mail, Bot messages, repository text."
  [#"(?i)<\s*script" #"(?i)<\s*iframe" #"(?i)<\s*object" #"(?i)<\s*embed"
   #"(?i)javascript\s*:" #"(?i)\son[a-z]+\s*="])

(defn utf8-length
  "Bytes, not characters. A crop is mostly Japanese UI text, where the two
  differ by three times, so a character-counted cap would admit a document
  three times the size it was written to allow."
  [text]
  #?(:clj (alength (.getBytes ^String text "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) text))))

(defn svg-payload
  "One region crop as an SVG document, or a map saying why there is none.
  Never throws.

  `nil` in means the client did not send a picture, which is a normal outcome —
  the crop is best-effort — and is reported as `:absent` rather than as a
  failure, so \"there was no picture\" and \"the picture was rejected\" are
  distinguishable by the caller instead of collapsing into one silence."
  [svg]
  (let [text (str/trim (str svg))]
    (cond
      (str/blank? text) {:reason :absent}
      (not (str/starts-with? text "<svg")) {:reason :not-svg}
      (some #(re-find % text) script-shaped) {:reason :script-shaped}
      :else
      (let [bytes (utf8-length text)]
        (if (> bytes max-image-bytes)
          {:reason :too-large :bytes bytes}
          {:svg text :bytes bytes})))))
