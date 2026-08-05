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
            [pdf.core :as pdf]))

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

(defn- parsed-of
  "The file, through the ACL, parsed.

  `documents/file-bytes` is the seam: it refuses a document (this is for
  uploads), and `drive.object/read-item` inside it is what answers whether
  this principal may have the bytes at all. Nothing here consults the store.

  Re-parsed per request. That is a real cost on a long document and it is
  left as one rather than hidden behind a cache: the object reference is a
  PieceCID, so the bytes for a given id are immutable and a memo keyed on it
  would be trivially correct — which makes it a change to make deliberately,
  with a bound on its size, and not a side effect of adding a viewer."
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
     ;; A decoder saying no is its own answer, and `app-preview.source` named
     ;; it first: `undecodable` is distinct from a refused grant because the
     ;; fix is a decoder rather than a permission, and telling somebody to
     ;; check something else sends them nowhere useful.
     ;;
     ;; Measured rather than anticipated: of thirty real PDFs on this
     ;; machine, twenty-nine rendered and one threw `zlib: unsupported
     ;; compression method` out of `org-ietf-deflate`. Without this it was a
     ;; 500 with a zlib message in it, which reads as this app being broken.
     (try (pdf/parse bytes)
          (catch Exception e
            (refuse! "このファイルを読み取れる復号器がありません。ダウンロードしてください。"
                     :pageview/undecodable {:item-id id :cause (.getMessage e)})))
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
        :svg (hsvg/->svg p)
        ;; Said out loud so the pane can explain an empty search rather than
        ;; leaving the reader to conclude the search is broken —
        ;; `app-preview.model/scanned?` learned the same thing about a listing.
        :scanned? (hpage/scanned? p)
        :text (hpage/text-of p)}))))
