(ns cloud.itonami.app.web-script-test
  "The page's JavaScript, parsed.

  `web.clj` carries about a quarter of a megabyte of JavaScript inside a
  Clojure string, and nothing has ever checked that it is JavaScript. The
  Clojure reader catches the errors that break the *string* — an unescaped
  quote inside a JS comment ends it early and the file stops compiling, which
  has happened repeatedly and is at least loud. It cannot see a missing
  brace, an unbalanced paren or a stray `\\.` in a regexp: those compile
  fine, ship fine, and turn the whole app blank in the browser, because one
  syntax error takes the entire script with it.

  The interaction layer is a `.js` resource now rather than a string
  literal, which removes the escaping entirely — the reader no longer sees
  it, so a backslash is a backslash. That makes this check cheaper and not
  less necessary: what ships is the rendered page, and a script assembled
  correctly out of a file that does not parse is still a blank app.

  So the page is rendered and every `<script>` in it is handed to a
  JavaScript parser. `node --check` is the parser: it is the same engine the
  browser will use, it is already on any machine that builds this, and
  writing a second one here would be writing a JavaScript parser.

  When node is absent the test says so and passes. A gate that fails on a
  machine without the tool teaches people to ignore it, and a gate that goes
  quiet teaches them it ran — so it neither fails nor stays silent."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.web :as web]))

(def ^:private config
  {:routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}]})

(defn- node-version []
  (try (let [{:keys [exit out]} (shell/sh "node" "--version")]
         (when (zero? exit) (str/trim out)))
       (catch Exception _ nil)))

(deftest the-pages-javascript-parses
  (if-let [version (node-version)]
    (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
                 (web/page-html config))
          blocks (map second (re-seq #"(?s)<script(?:\s[^>]*)?>(.*?)</script>" html))]
      (is (seq blocks) "the page has script at all")
      (doseq [[index block] (map-indexed vector blocks)]
        ;; Empty ones are the module tags with a src and no body.
        (when-not (str/blank? block)
          (let [file (io/file (System/getProperty "java.io.tmpdir")
                              (str "itonami-page-script-" index ".js"))]
            (spit file block)
            (let [{:keys [exit err]} (shell/sh "node" "--check" (str file))]
              (.delete file)
              (is (zero? exit)
                  (str "script " index " (" (count block) " characters) does not parse"
                       " under node " version ":\n" err)))))))
    (println (str "web-script-test: node is not on PATH, so the page's "
                  "JavaScript was not parsed. It is not being checked "
                  "anywhere else."))))

(deftest the-interaction-layer-parses-as-the-file-it-is
  ;; The rendered page above is what ships; this is the source it is built
  ;; from, checked directly, so a failure says which file to open.
  (if-let [version (node-version)]
    (let [source (io/file "resources/cloud/itonami/app/interaction.js")]
      (is (.isFile source) "the interaction layer is a resource, not a literal")
      (let [{:keys [exit err]} (shell/sh "node" "--check" (str source))]
        (is (zero? exit) (str source " does not parse under node " version ":\n" err))))
    (println "web-script-test: node is not on PATH, so the interaction layer was not parsed.")))

(deftest a-cell-anchor-is-spelled-in-one-place
  ;; The grid writes `Sheet1!B3` onto every cell as `data-anchor`, and the
  ;; comment box reads it back to put a dot where a comment points. Two
  ;; spellings would mean a dot that never appears and nothing to say why —
  ;; the same drift `docs.model/text-spans` was pulled out to end, one file
  ;; over and in JavaScript.
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    ;; The anchor format specifically — `!${columnName(` — and not every
    ;; use of `columnName`, which the style bar also makes when it says
    ;; which cell it is acting on. The first version of this assertion
    ;; counted those too and failed on code that was right.
    (is (= 1 (count (re-seq #"!\$\{columnName\(" js)))
        "the `tab!B3` form is written in exactly one place, which is cellAnchor")
    (is (str/includes? js "const cellAnchor = (tab, row, col)"))
    (is (= 2 (count (re-seq #"cellAnchor\(" (str/replace js "const cellAnchor" ""))))
        "and both the grid and the comment box call it")))
