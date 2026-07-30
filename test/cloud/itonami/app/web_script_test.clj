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
