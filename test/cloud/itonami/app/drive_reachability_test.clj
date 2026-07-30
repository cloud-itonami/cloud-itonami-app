(ns cloud.itonami.app.drive-reachability-test
  "Every Drive route the server answers, against what the app can reach.

  This is written because of a gap nobody was looking for: `import!` reads
  six formats — csv, xlsx, docx, pptx, md, edn — through the same validator,
  quota and versioning a save uses, the route was wired, it was tested, and
  **no part of the interface ever called it**. A person dropping a .xlsx got
  bytes with a download button, because `upload` was reachable and `import`
  was not. Nothing failed. There is no test that can fail for a feature
  nobody can get to, unless a test is looking for exactly that.

  So this reads both files and compares them. It is a text comparison, which
  is a weak instrument — the UI builds most of its URLs as `${base}/thing`,
  so a route is counted as reached when its distinctive last segment appears
  in a string in `web.clj`. That admits a coincidence and refuses to admit a
  route reached by a spelling this does not anticipate.

  It is worth having anyway, because the failure it catches is not subtle:
  the segment appears nowhere at all, which is what `import` looked like for
  as long as it existed. Anything genuinely unreachable belongs in
  `no-interface` below with a reason, so the list is the honest inventory of
  what this Drive can do that a person cannot ask for.

  Two limits, said here rather than discovered later. Routes ending in the
  same segment are one route to this test: `/documents/{id}/submissions` is
  reached and `/shared/{token}/submissions` is not, and it cannot tell them
  apart, so it reports both as reached. And a segment the UI holds in a
  variable — `act('reject')` builds `/suggestions/{id}/reject` — counts,
  because the string is there; a segment built by joining fragments would
  not."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private no-interface
  "Routes with no interface, on purpose or not yet, and why.

  Entries here are claims about the app, so each one is a thing to either
  build or delete — not a place to put a route to make a test pass."
  {"history"
   (str "The same versions the pane already shows, in an addressable form. "
        "The pane reads them from the item payload it already has, so this "
        "is a second door to a room the interface is standing in.")})

(defn- source [path]
  (slurp (io/file "src/cloud/itonami/app" path)))

(defn- drive-routes
  "The distinctive segment of every Drive route the server matches.

  `/api/workspace/drive/documents/([^/]+)/suggestions/[^/]+/accept` is
  `suggestions/accept`: the parameters are not part of what to look for, and
  the last two literal segments are what makes a route this one and not its
  neighbour."
  [server]
  (->> (re-seq #"\"(/api/workspace/drive[^\"]*)\"" server)
       (map second)
       (map (fn [route]
              ;; The character class first, or splitting on `/` splits
              ;; inside `([^/]+)` and leaves `]+)` behind as a segment.
              (->> (str/split (str/replace route "[^/]" "X") #"/")
                   (remove #(or (str/blank? %)
                                (re-find #"[(\[+\\]" %)
                                (contains? #{"api" "workspace" "drive" "documents"} %)))
                   (str/join "/"))))
       (remove str/blank?)
       distinct
       sort))

(deftest every-drive-route-is-reachable-from-the-interface
  (let [server (source "server.clj")
        web (source "web.clj")
        reached? (fn [segment]
                   ;; The last literal segment, in a string, after a slash.
                   ;; `${base}/suggestions` and `/suggestions/${id}/accept`
                   ;; both count; the word in a comment does not.
                   (let [tail (last (str/split segment #"/"))]
                     (or (str/includes? web (str "/" tail))
                         ;; The verb an event handler passes in, which is
                         ;; the whole of what puts it in the URL.
                         (str/includes? web (str "'" tail "'")))))
        routes (drive-routes server)
        unreached (remove reached? routes)]
    (is (seq routes) "the routes were found at all")
    (is (= (set (keys no-interface)) (set unreached))
        (str "unreachable now: " (pr-str unreached)
             " — recorded as unreachable: " (pr-str (sort (keys no-interface)))))
    ;; And the record does not outlive what it describes: a route that grew
    ;; an interface has to leave the list, or the list becomes a place where
    ;; finished work goes to look unfinished.
    (doseq [[segment reason] no-interface]
      (is (some #(= segment %) routes)
          (str segment " is recorded as having no interface and is not a route"))
      (is (not (str/blank? reason))))))

(deftest importing-is-reachable
  ;; The specific one this file exists for, said plainly rather than as a
  ;; set difference: the app offers to read a file in, not only to store it.
  (let [web (source "web.clj")]
    (is (str/includes? web "/api/workspace/drive/import"))
    (is (str/includes? web "drive-import-choice") "and there is somewhere to say so")
    (doseq [format ["xlsx" "docx" "pptx" "csv" "md" "edn"]]
      (is (str/includes? web (str format ":")) (str format " can be chosen")))))
