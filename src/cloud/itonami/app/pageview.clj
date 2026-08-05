(ns cloud.itonami.app.pageview
  "Showing an uploaded PDF, one page at a time.

  ## What this closes

  The Drive has been able to hold a PDF since `documents/upload!` — a
  quotation, a signed contract, a counterparty's terms. What it could do with
  one was hand it back: `previewable-media-types` is raster images only and
  everything else leaves as `application/octet-stream` with
  `Content-Disposition: attachment`. So the answer to 「何が届いたのか」 was
  *download it and open something else*, which is the answer a Drive exists to
  stop giving.

  ADR-0007 settled what a document surface is for the four kinds this app
  writes: the rendered thing is the default view, because a list of fields is
  the fields of a value and not the value. A PDF got none of that, for a
  reason that was correct and is not a conclusion: serving user bytes inline
  from this origin is stored XSS unless the format cannot carry script, and
  PDF very much can.

  ## Why this does not widen the allowlist

  It does not serve the file. `kotoba-lang/hanmen` parses the PDF **on the
  server** into placed marks — a size, a rotation, text runs with coordinates
  — and emits SVG from a closed vocabulary of three item kinds. Nothing from
  the file becomes an element name or an attribute name; content reaches the
  output only as an escaped text node or an escaped attribute value, and no
  element it may emit takes a URL.

  So what reaches the browser is markup this server generated, in the same
  category as the workbook charts `sheets.chart` already draws into the page
  — not bytes somebody uploaded. `previewable-media-types` still says what it
  said, `/preview` still serves only rasters, and the page CSP is unchanged:
  markup in the document is not a load, so `default-src 'none'` has nothing
  to permit. Deciding what this page may *load* remains ADR-0007's open
  follow-up and is untouched here.

  ## What is refused, and why refusing is cheap

  A PDF is parsed by scanning the whole file. The Drive quota is a gibibyte,
  so \"the user uploaded it\" is not a bound on what this will do — an
  800 MB file would hold a request thread and a multiple of that in heap for
  as long as it took. `render-limit-bytes` refuses above a size that no
  business document reaches, with a message that says the file is too large
  to show rather than pretending it failed.

  The declared media type selects the parser and is not trusted for anything
  else. A file claiming `application/pdf` that is not one produces no pages,
  which is `:pageview/no-pages` — a thing this cannot show, reported as such."
  (:require [cloud.itonami.app.documents :as documents]
            [hanmen.page :as hpage]
            [hanmen.pdf :as hpdf]
            [hanmen.svg :as hsvg]
            [pdf.core :as pdf]
            [png.encode :as png]))

(def schema "cloud.itonami.app.pageview.v1")

(def viewable-media-types
  "What this can turn into pages.

  One entry. `kasane` decodes PSD and AI and `hanmen` would take their marks
  the same way, but neither has a producer written, and listing a type here
  that nothing can parse would put a viewer in front of a file and then fail
  at it."
  #{"application/pdf"})

(def render-limit-bytes
  "Above this, the answer is that it is too large to show.

  32 MiB. A hundred-page contract with embedded fonts is a few megabytes; a
  scanned bundle at 300dpi is tens. The number is a ceiling on what one
  request may pull into heap, not a judgement about the document — the file
  stays in the Drive and downloads as it always did."
  (* 32 1024 1024))

(defn- refuse! [message type detail]
  (throw (ex-info message (assoc detail :type type))))

(defn viewable?
  "Whether this item is one this can show. Answered from the item view the
  Drive already returns, so the client does not re-derive it from a media
  type — the same reason `:previewable?` is reported rather than inferred."
  [item]
  (boolean (and (:file? item)
                (contains? viewable-media-types (str (:media-type item))))))

;; ── the parse cache ──────────────────────────────────────────────────────────

(def cache-limit
  "How many parsed documents to keep.

  Small on purpose. This exists so that turning to page 12 of a contract does
  not re-scan the file — a session's worth of use by one person — not so that
  the server holds everybody's documents. When it is full the whole map is
  dropped rather than the least-recently-used entry evicted: an LRU needs an
  access order, an access order needs a write on every READ, and a correct one
  needs that write to be atomic with the read. All of that to choose better
  than starting again, on a map of eight.

  ## What this is under concurrency, said rather than assumed

  `swap!` makes the map safe: no entry is ever half-written and the count
  never exceeds this. What it does NOT do is stop two requests parsing the
  same document at once — both parse, both store, and the second store wins
  with an equal value. That is wasted work and not a wrong answer, and the
  fix for it (a promise per key, so the second waits) trades a bounded waste
  for a way to deadlock a request thread on a parse that throws. Not worth
  it at this size, and this paragraph is here so the trade is a decision
  rather than an omission.

  The bound is on ENTRIES and each entry is a parsed PDF, so the real
  ceiling is eight times `render-limit-bytes` worth of object graph. That is
  the number to look at if this ever needs raising, not the eight."
  8)

(defonce ^:private parse-cache (atom {}))

(defn- cached-parse
  "`(parse!)` for `ref`, remembered.

  Keyed on the object reference, which is a PieceCID — it names the CONTENT.
  The bytes for a given reference cannot change, so there is no invalidation
  here because there is nothing to invalidate, and two people who uploaded the
  same file share the entry without either being shown the other's: the key is
  what the bytes are rather than who holds them.

  A refusal is NOT cached. `parse!` throws for a file no decoder can read, and
  remembering that would make the answer permanent for the life of the
  process — including across the deploy that adds the decoder.

  Concurrency: see `cache-limit`. Two callers may parse the same reference at
  once and both store; neither can observe a partial entry."
  [ref parse!]
  (if-not ref
    (parse!)
    (or (get @parse-cache ref)
        (let [parsed (parse!)]
          (swap! parse-cache
                 (fn [cache]
                   (assoc (if (>= (count cache) cache-limit) {} cache) ref parsed)))
          parsed))))

(defn forget-cached!
  "Drop everything. For tests, and for an operator who has a reason."
  []
  (reset! parse-cache {}))

;; ── reading the file ─────────────────────────────────────────────────────────

(defn- parsed-of
  "The file, through the ACL, parsed.

  `documents/file-bytes` is the seam: it refuses a document (this is for
  uploads), and `drive.object/read-item` inside it is what answers whether
  this principal may have the bytes at all. Nothing here consults the store.

  The cache is read AFTER that call, never before, and the ordering is the
  whole security of it: a cache consulted first would answer faster and
  wrong. What is saved is the parse, not the permission check."
  [id actor object-store]
  (let [out (documents/file-bytes id actor object-store)
        bytes (:bytes out)]
    (when-not (contains? viewable-media-types (str (:declared-media-type out)))
      (refuse! "このファイルはページとして表示できません。ダウンロードしてください。"
               :pageview/not-viewable
               {:item-id id :media-type (:declared-media-type out)}))
    (when (> (count bytes) render-limit-bytes)
      (refuse! "このファイルは表示するには大きすぎます。ダウンロードしてください。"
               :pageview/too-large
               {:item-id id :size-bytes (count bytes) :limit render-limit-bytes}))
    {:parsed
     (cached-parse
      (:object-ref out)
      (fn []
        ;; A decoder saying no is its own answer, and `app-preview.source`
        ;; named it first: `undecodable` is distinct from a refused grant
        ;; because the fix is a decoder rather than a permission, and telling
        ;; somebody to check something else sends them nowhere useful.
        ;;
        ;; Measured rather than anticipated: of thirty real PDFs on this
        ;; machine, twenty-nine rendered and one threw `zlib: unsupported
        ;; compression method` out of `org-ietf-deflate`. Without this it was
        ;; a 500 with a zlib message in it, which reads as this app being
        ;; broken rather than as this file needing a decoder nobody wrote.
        (try (pdf/parse bytes)
             (catch Exception e
               (refuse! "このファイルを読み取れる復号器がありません。ダウンロードしてください。"
                        :pageview/undecodable {:item-id id :cause (.getMessage e)})))))
     :filename (:filename out)}))

(defn document
  "How many pages, and what the file is called. No page is walked."
  ([id actor] (document id actor (documents/store-instance)))
  ([id actor object-store]
   (let [{:keys [parsed filename]} (parsed-of id actor object-store)
         n (hpdf/page-count parsed)]
     (when (zero? n)
       (refuse! "このファイルからページを読み取れませんでした。"
                :pageview/no-pages {:item-id id}))
     {:schema schema :ok? true :id id :filename filename :count n})))

;; ── images ───────────────────────────────────────────────────────────────────

(defn- image-url
  "Where page `page-index`'s image `index` is served from.

  Built here, out of two integers and the item id the caller already holds.
  `hanmen` never puts a document-supplied string in a URL — an `:image` item
  carries an index precisely so that it cannot — and it refuses an href that
  is not a same-origin path, so this is the narrow place where the shape of
  that URL is decided."
  [id page-index index]
  (str "/api/workspace/drive/documents/"
       (java.net.URLEncoder/encode ^String id java.nio.charset.StandardCharsets/UTF_8)
       "/pages/" page-index "/images/" index))

(defn- samples-per-pixel [bytes width height]
  (when (and width height (pos? (long width)) (pos? (long height)))
    (let [n (/ (double (count bytes)) (* (long width) (long height)))]
      (when (== n (Math/floor n)) (long n)))))

(defn- narrow-16-bit
  "Two bytes per sample down to one, big-endian high byte.

  Truncation and not scaling, because 16-bit PNG samples are linear in the
  same range: the high byte IS the 8-bit value. A screen shows 8 bits, and a
  scan that ships 16 is shipping precision for print.

  This throws away real data and says so here rather than pretending the
  image was 8-bit all along — an archival copy comes from `/download`, which
  hands over the file untouched."
  [bytes]
  (into [] (take-nth 2) bytes))

(defn- expand-indexed
  "An indexed image's codes through its palette, into RGB.

  The palette is `[/Indexed base hival lookup]` and `lookup` is a string or
  a stream of `base`-many components per entry. Only an RGB base is expanded
  — a palette over CMYK or a separation needs the same conversion the CMYK
  branch below refuses to guess at, and one wrong palette produces an image
  in confidently wrong colours, which is worse than no image.

  A code past the end of the palette becomes black rather than throwing: a
  malformed index in one pixel should not lose the page."
  [codes palette]
  (when (seq palette)
    (into []
          (mapcat (fn [code]
                    (let [at (* 3 (long code))]
                      (if (< (+ at 2) (count palette))
                        [(nth palette at) (nth palette (+ at 1)) (nth palette (+ at 2))]
                        [0 0 0]))))
          codes)))

(defn- cmyk->rgb
  "Naive CMYK, which is what a screen wants and what nobody should print
  from. The same conversion `hanmen` uses for a fill colour, so a CMYK image
  and a CMYK rule agree about what a colour is."
  [bytes]
  (into []
        (mapcat (fn [[c m y k]]
                  (let [f (fn [v] (long (* 255 (- 1.0 (/ (double v) 255.0))
                                          (- 1.0 (/ (double (or k 0)) 255.0)))))]
                    [(f c) (f m) (f (or y 0))])))
        (partition-all 4 bytes)))

(defn- to-png-shape
  "An image XObject's samples in a shape `png.encode` can write, or nil.

  The four this understands, in the order a file is likely to be them:
  8-bit gray/RGB/RGBA straight through, 16-bit narrowed, indexed expanded
  through an RGB palette, and CMYK converted. Anything else — a separation,
  a 1-bit bilevel scan, a JPX — returns nil and the caller refuses BY NAME,
  because a wrongly encoded image opens and looks like a corrupt document
  rather than an unsupported one."
  [{:keys [bytes width height bits colorspace palette]}]
  (let [w (long (or width 0)) h (long (or height 0))]
    (when (and (pos? w) (pos? h))
      (let [px (* w h)
            bytes (if (= 16 bits) (narrow-16-bit bytes) bytes)
            ;; An EXACT multiple, not integer division. Five bytes over four
            ;; pixels is not "one sample per pixel with a spare" — it is a
            ;; shape this does not understand, and rounding it down hands
            ;; the encoder a sample count that does not fit the geometry.
            ;; Measured: it did, and `png.encode` refused it with its own
            ;; error instead of this one naming the image.
            n (when (and (pos? px) (zero? (rem (count bytes) px)))
                (quot (count bytes) px))]
        (cond
          (and (= :indexed colorspace) palette (= 1 n))
          {:pixels (expand-indexed bytes palette) :color :rgb}

          (and (= :cmyk colorspace) (= 4 n))
          {:pixels (cmyk->rgb bytes) :color :rgb}

          (= 1 n) {:pixels (vec bytes) :color :gray}
          (= 3 n) {:pixels (vec bytes) :color :rgb}
          (= 4 n) {:pixels (vec bytes) :color :rgba}
          :else nil)))))

(defn image
  "One image off a page, as bytes a browser can render.

  `index` counts in `Do` order — the order `hanmen` reached the images while
  walking the content stream, which is the order `:item/index` was assigned.
  Resolving it through `hanmen.pdf/page-images` rather than the page's own
  `/XObject` dictionary is what keeps those the same: the dictionary is in
  resource order, and the two agree only on a document that never invokes a
  form. Mixing them serves the wrong picture for the right box, on exactly
  the documents whose pictures matter.

  A `DCTDecode` XObject already IS a JPEG, so it passes through untouched —
  decoding and re-encoding it would spend time and quality to arrive at the
  same picture. Anything else is raw samples, and `png.encode` turns the
  shapes it knows into a PNG. A shape it does not know — 16-bit, an indexed
  palette, a CMYK separation — is refused BY NAME rather than encoded
  wrongly, because a wrongly encoded image opens."
  ([id actor page-index index]
   (image id actor page-index index (documents/store-instance)))
  ([id actor page-index index object-store]
   (let [{:keys [parsed]} (parsed-of id actor object-store)
         images (vec (hpdf/page-images parsed (max 0 (or page-index 0))))
         {:keys [media-type bytes width height] :as found}
         (nth images (or index -1) nil)]
     (when-not found
       (refuse! "その画像はこのページにありません。" :pageview/no-such-image
                {:item-id id :page page-index :index index :available (count images)}))
     (if media-type
       {:schema schema :ok? true :media-type media-type
        :bytes (byte-array (map unchecked-byte bytes))}
       (let [shaped (to-png-shape found)]
         (when-not shaped
           (refuse! "この画像の形式は表示できません。" :pageview/unsupported-image
                    {:item-id id :page page-index :index index
                     :width width :height height
                     :bits (:bits found) :colorspace (:colorspace found)
                     :samples-per-pixel (samples-per-pixel bytes width height)}))
         {:schema schema :ok? true :media-type png/media-type
          :bytes (byte-array
                  (map unchecked-byte
                       (png/encode (:pixels shaped)
                                   {:width width :height height
                                    :color (:color shaped)})))})))))

;; ── a page ───────────────────────────────────────────────────────────────────

(defn page
  "One page, as SVG and as the facts a viewer puts around it.

  `index` is clamped rather than refused. A stale client asking for page 40 of
  a document that now has 12 is asking a question with an obvious best answer,
  and a 400 there turns a re-render into an error banner."
  ([id actor index] (page id actor index (documents/store-instance)))
  ([id actor index object-store]
   (let [{:keys [parsed filename]} (parsed-of id actor object-store)
         n (hpdf/page-count parsed)]
     (when (zero? n)
       (refuse! "このファイルからページを読み取れませんでした。"
                :pageview/no-pages {:item-id id}))
     (let [index (max 0 (min (dec n) (or index 0)))
           p (hpdf/page-at parsed index)]
       {:schema schema :ok? true :id id :filename filename
        :count n
        :page (hpage/summary p)
        ;; The page in its own units, scaled by the browser — see
        ;; `hanmen.svg/emit`. A fixed pixel size here would mean re-rendering
        ;; on every resize.
        ;;
        ;; `:image-href` is what turns an image from an outlined region into
        ;; a picture. Passing it is this app's decision and not the library's
        ;; default: without it the fragment loads nothing at all.
        :svg (hsvg/->svg p {:image-href
                            (fn [{image-index :index}]
                              (image-url id index image-index))})
        ;; Said out loud so the pane can explain an empty search rather than
        ;; leaving the reader to conclude the search is broken —
        ;; `app-preview.model/scanned?` learned the same thing about a listing.
        :scanned? (hpage/scanned? p)
        :text (hpage/text-of p)
        ;; Both, because they answer different questions. `:text` is what the
        ;; document says in the order it said it; `:reading-text` is a guess
        ;; about how to read it — the one a person quoting the page wants and
        ;; the one a diff of the file does not.
        :reading-text (hpage/reading-text p)}))))
