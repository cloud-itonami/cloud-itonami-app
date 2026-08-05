(ns cloud.itonami.app.pageview-test
  "Showing an uploaded PDF, and the four things that are refused instead.

  The PDFs here are written by `pdf.core/write-document`, so what is on the
  page is known rather than assumed: an assertion that the rendering contains
  a string is an assertion that the string this test put at those coordinates
  came back out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.pageview :as pageview]
            [cloud.itonami.app.store :as store]
            [drive.store.memory :as memory]
            [pdf.core :as pdf]))

(def alice "user-alice")
(def bob "user-bob")

(defn- with-state [f]
  (let [state (atom (store/initial-state))]
    (with-redefs [store/snapshot (fn [] @state)
                  store/transact! (fn [g & args] (apply swap! state g args))]
      (f state (memory/store)))))

(defn- pdf-bytes [& lines]
  (byte-array
   (map unchecked-byte
        (pdf/write-document
         (mapv (fn [line]
                 {:width 595 :height 842
                  :content (pdf/text-command {:x 72 :y 720 :text line :size 12})})
               lines)))))

(defn- upload! [object-store bytes & [{:keys [name media-type actor]
                                       :or {name "契約.pdf"
                                            media-type "application/pdf"
                                            actor alice}}]]
  (:item (documents/upload! name media-type bytes actor object-store)))

(defn- error-type [f]
  (:type (try (f) (catch clojure.lang.ExceptionInfo e (ex-data e)))))

;; ── the page ─────────────────────────────────────────────────────────────────

(deftest a-pdf-in-the-drive-can-be-looked-at
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store (pdf-bytes "Master Agreement"))
            out (pageview/page (:id item) alice 0 object-store)]
        (is (:ok? out))
        (is (= 1 (:count out)))
        (is (= "契約.pdf" (:filename out)))
        (testing "the rendering is the page, not a description of it"
          (is (str/starts-with? (:svg out) "<svg "))
          (is (str/includes? (:svg out) "Master Agreement"))
          (is (str/includes? (:svg out) "viewBox=\"0 0 595 842\"")))
        (testing "and the text is available beside it, for search"
          (is (= ["Master Agreement"] (:text out)))
          (is (false? (:scanned? out))))))))

(deftest the-rendering-loads-nothing-and-runs-nothing
  ;; The property that lets this exist on a page whose CSP is
  ;; `default-src 'none'` without widening it. Asserted on the output rather
  ;; than left to `hanmen`'s own suite, because it is THIS app's CSP that the
  ;; claim is about.
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store (pdf-bytes "plain"))
            svg (:svg (pageview/page (:id item) alice 0 object-store))]
        (doseq [forbidden ["<script" "href" "src=" "xlink" "url(" "javascript:"
                           "data:" "<foreignObject" "<image" "<style" "on"]]
          (when-not (= forbidden "on")
            (is (not (str/includes? svg forbidden)) forbidden)))
        ;; No event-handler attribute of any name, rather than a list of the
        ;; ones anyone thought of.
        (is (nil? (re-find #"\son[a-z]+=" svg)))))))

(deftest text-a-document-put-on-the-page-cannot-become-markup
  ;; The whole reason a PDF may not be served inline from this origin. Here
  ;; the bytes are parsed and the text comes back as a text node.
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store (pdf-bytes "</text><script>alert(1)</script>"))
            svg (:svg (pageview/page (:id item) alice 0 object-store))]
        (is (not (str/includes? svg "<script")))
        (is (str/includes? svg "&lt;script&gt;"))))))

(deftest pages-are-turned-and-a-stale-index-is-clamped
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store (pdf-bytes "one" "two" "three"))
            at (fn [n] (pageview/page (:id item) alice n object-store))]
        (is (= 3 (:count (at 0))))
        (is (str/includes? (:svg (at 1)) "two"))
        (is (= "Page 2" (get-in (at 1) [:page :page/label])))
        (testing "a client asking for a page that is not there gets the last one"
          ;; Rather than a 400: a stale client re-rendering turns an obvious
          ;; best answer into an error banner.
          (is (str/includes? (:svg (at 99)) "three"))
          (is (str/includes? (:svg (at -5)) "one")))))))

(deftest the-listing-says-how-many-without-drawing-any
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store (pdf-bytes "a" "b"))
            out (pageview/document (:id item) alice object-store)]
        (is (= 2 (:count out)))
        (is (nil? (:svg out)))))))

;; ── what is refused ──────────────────────────────────────────────────────────

(deftest a-file-that-is-not-a-pdf-is-refused-by-type
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store (byte-array (map unchecked-byte (repeat 32 65)))
                          {:name "notes.txt" :media-type "text/plain"})]
        (is (= :pageview/not-viewable
               (error-type #(pageview/page (:id item) alice 0 object-store))))))))

(deftest a-document-is-not-a-file-and-cannot-be-paged
  ;; `documents/file-bytes` already draws this line; asserted here so that a
  ;; future caller of this namespace cannot reach the parser with a workbook.
  (with-state
    (fn [_ object-store]
      (let [doc (:item (documents/create! :sheets "売上" alice object-store))]
        (is (= :drive/not-a-file
               (error-type #(pageview/page (:id doc) alice 0 object-store))))))))

(deftest bytes-that-claim-to-be-a-pdf-and-are-not-produce-no-pages
  ;; The declared type selects the parser and is trusted for nothing else.
  ;; 422 rather than a 500 with a parse error in it.
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store
                          (.getBytes "this is not a PDF at all" "UTF-8"))]
        (is (= :pageview/no-pages
               (error-type #(pageview/page (:id item) alice 0 object-store))))))))

(deftest a-decoder-saying-no-is-its-own-answer
  ;; Measured, not anticipated: of thirty real PDFs on this machine, one
  ;; threw `zlib: unsupported compression method` out of `org-ietf-deflate`.
  ;; Without a name for it that is a 500 with a zlib message in it, which
  ;; reads as this app being broken rather than as this file needing a
  ;; decoder nobody has written. `app-preview.source/undecodable` drew the
  ;; same distinction first.
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store (pdf-bytes "fine"))]
        (with-redefs [pdf/parse (fn [_] (throw (Exception. "zlib: unsupported compression method")))]
          (is (= :pageview/undecodable
                 (error-type #(pageview/page (:id item) alice 0 object-store)))))
        (testing "and it is not confused with a file that has no pages"
          (is (= :pageview/no-pages
                 (error-type #(pageview/page
                               (:id (upload! object-store
                                             (.getBytes "not a PDF" "UTF-8")))
                               alice 0 object-store)))))))))

(deftest a-file-too-large-to-show-is-still-a-file
  ;; The Drive quota is a gibibyte, so "the user uploaded it" is not a bound
  ;; on what a parse will do. Refusing to SHOW it does not refuse to hold it.
  (with-state
    (fn [_ object-store]
      (with-redefs [pageview/render-limit-bytes 128]
        (let [item (upload! object-store (pdf-bytes "a long document"))]
          (is (= :pageview/too-large
                 (error-type #(pageview/page (:id item) alice 0 object-store))))
          (testing "and it still downloads"
            (is (seq (:bytes (documents/file-bytes (:id item) alice object-store))))))))))

(deftest somebody-else-s-pdf-is-not-readable
  ;; No second opinion about permission: the ACL is `drive.object/read-item`'s
  ;; answer, reached through `documents/file-bytes` exactly as every other
  ;; reader does.
  (with-state
    (fn [_ object-store]
      (let [item (upload! object-store (pdf-bytes "private"))]
        (is (some? (error-type #(pageview/page (:id item) bob 0 object-store)))
            "bob cannot page alice's upload")))))

;; ── the report the pane renders ──────────────────────────────────────────────

(deftest a-page-with-no-text-says-so
  ;; Otherwise a search over it comes back empty and the reader concludes the
  ;; search is broken.
  (with-state
    (fn [_ object-store]
      (let [bytes (byte-array
                   (map unchecked-byte
                        (pdf/write-document
                         [{:width 200 :height 100
                           :content (pdf/rect-command {:x 0 :y 0 :width 200
                                                       :height 100 :fill? true})}])))
            item (upload! object-store bytes)
            out (pageview/page (:id item) alice 0 object-store)]
        (is (true? (:scanned? out)))
        (is (= [] (:text out)))))))

(deftest viewable-is-reported-from-the-item-the-drive-already-returns
  ;; So the client does not re-derive it from a media type — the same reason
  ;; `:previewable?` is a report of one allowlist rather than a rule the
  ;; browser applies.
  (with-state
    (fn [_ object-store]
      (let [pdf-item (upload! object-store (pdf-bytes "x"))
            txt (upload! object-store (byte-array (map unchecked-byte (repeat 8 65)))
                         {:name "n.txt" :media-type "text/plain"})
            doc (:item (documents/create! :docs "設計" alice object-store))]
        (is (pageview/viewable? pdf-item))
        (is (not (pageview/viewable? txt)))
        (is (not (pageview/viewable? doc)))))))
