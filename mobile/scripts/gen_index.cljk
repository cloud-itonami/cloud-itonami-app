(ns gen-index
  "Writes the one document the mobile bundle has.

  Generated rather than hand-written, and generated from `jp-go-dds.page`
  rather than from a copy of what it emits, because the head is not decoration:
  the viewport meta with `viewport-fit=cover`, `color-scheme`, `theme-color`
  and the `dds-ext-*` stylesheet all come from the design system, and a
  hand-written document is a second place they can be wrong. `dist/index.html`
  is committed for the same reason `services/app-edge/public` holds its assets:
  a repository that builds a browser bundle and ships no document has nothing
  anyone can open (ADR-2608080100).

  `--check` re-renders and compares instead of writing, so a change to the
  design system that would change the document fails a gate rather than
  arriving unnoticed in someone's next build.

  Run: npm run index   (nbb over the :cljs alias's classpath)"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:process" :as process]
            [jp-go-dds.page :as page]))

(def output-path "dist/index.html")

(defn document
  "The full HTML document, as a string.

  `:css \"\"` plus a `<link>`, exactly as the edge page does it: the 70 KB
  stylesheet is a file the WebView reads once from the app bundle, and inlining
  it would put it in the document on every launch as well.

  The paths are relative — `dds.css`, `js/main.js` — because the same directory
  is served from three different roots: `kotoba-webbundle://app/` on iOS and
  macOS, `https://appassets.androidplatform.net/assets/` on Android, and a
  plain file:// or http:// path when a developer opens it. An absolute
  `/dds.css` resolves correctly in exactly one of those."
  []
  (page/->page
   {:title "営みフリート — cloud-itonami"
    :description "cloud-itonami の actor ディレクトリ。iOS / Android の kotoba-shell アプリが持つ画面。"
    :css ""
    :head [[:link {:rel "stylesheet" :href "dds.css"}]
           ;; An empty data: icon, because the bundle carries no favicon and
           ;; does not need one: a WKWebView and an Android WebView never ask.
           ;; A browser does, and its 404 lands in the console looking like a
           ;; missing asset of ours — which cost one pass of the browser check
           ;; before this line existed.
           [:link {:rel "icon" :href "data:,"}]]}
   [:div {:id "app"}]
   ;; `defer`, not `async`: the module mounts into #app, so it must not run
   ;; before the element it mounts into has been parsed.
   [:script {:src "js/main.js" :defer "defer"}]))

(let [check? (some #{"--check"} *command-line-args*)
      rendered (str (document) "\n")
      on-disk (when (fs/existsSync output-path) (fs/readFileSync output-path "utf8"))]
  (if check?
    (if (= rendered on-disk)
      (println "OK\t" output-path "\tmatches jp-go-dds.page")
      (do (println "STALE\t" output-path
                   "\tdiffers from what jp-go-dds.page renders; run: npm run index")
          (process/exit 1)))
    (do (fs/mkdirSync (path/dirname output-path) #js {:recursive true})
        (fs/writeFileSync output-path rendered)
        (println "WROTE\t" output-path "\t" (count rendered) "bytes"))))
