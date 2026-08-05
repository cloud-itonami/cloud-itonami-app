(ns cloud.itonami.app.route-scan
  "Reading `server.clj`'s own `cond` to find out what this app serves.

  ## Why the routes are read rather than declared

  `handler` is one large `cond` over method and path. That is the only complete
  statement of what this app serves, and every other list of it — a CLI's
  commands, an MCP manifest, a README — is a copy that drifts the moment a route
  lands without someone remembering the copy. Measured 2026-08-05: seventeen CLI
  commands against more than two hundred routes, and nothing anywhere reported
  the difference.

  So the copy is derived from the original, and `commands-test` re-derives it and
  fails when the checked-in registry has fallen behind.

  ## Why `.cljc`

  Two callers in two runtimes: `dev/gen_commands.cljs` writes the registry under
  nbb, and `commands-test` checks it under the JVM. Written twice they would
  disagree eventually, and the disagreement would read as drift in the routes
  rather than in the two scanners.

  Everything here is pure — text in, data out. The file reading and writing
  belongs to the callers, which is what lets one implementation serve both."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; reading the cond

(defn clause-starts
  "Where each `cond` test begins. Both spellings: one method, or a set of them."
  [text]
  (->> (concat (loop [i 0 acc []]
                 (if-let [j (str/index-of text "(and (= method " i)]
                   (recur (inc j) (conj acc j)) acc))
               (loop [i 0 acc []]
                 (if-let [j (str/index-of text "(and (contains? #{\"" i)]
                   (recur (inc j) (conj acc j)) acc)))
       sort vec))

(defn form-end
  "Index just past the form opening at `start`.

  Strings, escapes and line comments are skipped rather than counted, because a
  regex route like `#\"/api/business/([^/]+)/bind\"` is full of parentheses that
  close nothing."
  [text start]
  (loop [i start depth 0 in-string? false escaped? false in-comment? false]
    (if (>= i (count text))
      i
      (let [c (nth text i)]
        (cond
          in-comment? (recur (inc i) depth in-string? false (not= c \newline))
          escaped? (recur (inc i) depth in-string? false false)
          (and in-string? (= c \\)) (recur (inc i) depth true true false)
          in-string? (recur (inc i) depth (not= c \") false false)
          (= c \") (recur (inc i) depth true false false)
          (= c \;) (recur (inc i) depth false false true)
          (= c \\) (recur (+ i 2) depth false false false)
          (= c \() (recur (inc i) (inc depth) false false false)
          (= c \)) (if (= 1 depth)
                     (inc i)
                     (recur (inc i) (dec depth) false false false))
          :else (recur (inc i) depth false false false))))))

(defn methods-of [test]
  (vec (distinct
        (concat (map second (re-seq #"\(= method \"([A-Z]+)\"\)" test))
                (some->> (re-find #"contains\? #\{([^}]+)\} method" test)
                         second
                         (re-seq #"\"([A-Z]+)\"")
                         (map second))))))

(defn paths-of
  "Every route this clause serves.

  Usually one, occasionally a family: `page-route?` answers three paths from one
  predicate — a document's pages, one page, and one image on a page. Taking only
  the longest, as this did first, dropped the other two silently, and the gate
  could not notice because the gate compares the registry against this scanner
  and both were blind in the same place.

  All of them, then, rather than a rule about which to prefer. A first version
  discarded any pattern that was a prefix of another, on the theory that a clause
  might name a cheap guard before matching properly. Measured across this file:
  exactly one clause names more than one pattern, and it is the three-route
  family above. The rule existed to solve a case that does not occur, and it
  silently deleted two real routes."
  [test]
  (let [literal (some-> (re-find #"\(= path \"([^\"]+)\"\)" test) second)]
    (if literal
      [literal]
      (vec (distinct (map second (re-seq #"#\"(/[^\"]+)\"" test)))))))

(defn path-of
  "The single route a clause serves, or the most specific when it names several."
  [test]
  (first (sort-by count > (paths-of test))))

(def ^:private pattern-definition
  #"\(def\s+(?:\^:private\s+)?([a-z][a-z0-9?*!<>=-]*)\s+#\"(/[^\"]+)\"\s*\)")

(defn expand-pattern-vars
  "Inline `(def ^:private page-pattern #\"/api/…\")` at every use.

  Routes reached through a named pattern are invisible to a scanner that only
  reads regex literals, and invisible is worse than wrong here: the clause is
  never seen, so no command is generated AND no test reports one missing.
  Measured 2026-08-05, when `/api/workspace/drive/documents/{document}/pages`
  and its two siblings landed upstream and this scanner did not notice.

  The replacement is a FUNCTION, not a string, and that is not a style choice.
  On the JVM `str/replace` reads `\\` and `$` in a replacement string as escapes,
  so a pattern containing `(\\d+)` came back as `(d+)` — while ClojureScript
  substituted it literally. The two runtimes then scanned different routes, the
  nbb generator called the registry current, and the JVM test called it stale.
  A function replacement is literal in both. Measured 2026-08-05; it is the first
  thing the shared `.cljc` caught that two hand-written scanners would have
  hidden from each other."
  [source]
  (reduce (fn [text [_ name pattern]]
            (let [literal (str "#\"" pattern "\"")]
              (str/replace text
                           (re-pattern (str "(?<![A-Za-z0-9?*!<>=-])"
                                            name
                                            "(?![A-Za-z0-9?*!<>=-])"))
                           (fn [_] literal))))
          source
          (re-seq pattern-definition source)))

(defn gate-of
  "Which session the clause demands.

  `:human` refuses an agent bearer token on purpose — funding, settlement and
  governed approval (ADR-0009). `:app` accepts one. `:none` asked for no session
  at all, which is either infrastructure or a step in a browser handshake."
  [body]
  (cond
    (str/includes? body "require-human-session!") :human
    (str/includes? body "require-app-session!") :app
    (str/includes? body "require-session!") :session
    :else :none))

(defn flags-of
  "Keys the clause pulls off the request.

  Hints for help output, not a schema: unknown flags are passed through anyway,
  because a schema derived by reading source cannot be trusted to be complete and
  would refuse calls the server would have accepted."
  [body]
  (->> (re-seq #"\(:([a-z][a-z0-9-]*) (?:request|body|params|q)\)" body)
       (map second) distinct sort vec))

;; ---------------------------------------------------------------------------
;; naming

(def segment-name-overrides
  "Where the segment before a capture group does not name what it captures.

  `/api/oauth/([^/]+)/callback` captures a provider, not an `oauth`; `readiness`
  captures the repository being asked about. Derivation is right everywhere else,
  so these are listed rather than a naming scheme invented to cover six cases."
  {"oauth" "provider"
   "connections" "provider"
   "readiness" "repo"
   "callback" "token"
   "shared" "link"
   "credentials" "index"})

(defn singular [word]
  (cond
    (str/ends-with? word "ies") (str (subs word 0 (- (count word) 3)) "y")
    (str/ends-with? word "ss") word
    (str/ends-with? word "s") (subs word 0 (dec (count word)))
    :else word))

(defn- param-name [previous-segment]
  (or (get segment-name-overrides previous-segment)
      (singular (or previous-segment "id"))))

(defn- capture? [segment]
  (or (str/starts-with? segment "(")
      (str/includes? segment "[^/]")
      (str/includes? segment "\\d")))

(defn split-segments
  "A route path into segments, splitting on `/` only outside a character class.

  `clojure.string/split` cannot be used: `/api/business/([^/]+)/bind` contains a
  slash INSIDE `[^/]`, and splitting on it turns one capture group into the two
  fragments `([^` and `]+)`. Measured — it produced commands literally named
  `business ]+) bind`."
  [path]
  (loop [i 0 depth 0 current "" acc []]
    (if (>= i (count path))
      (vec (remove str/blank? (conj acc current)))
      (let [c (nth path i)]
        (case c
          \[ (recur (inc i) (inc depth) (str current c) acc)
          \] (recur (inc i) (max 0 (dec depth)) (str current c) acc)
          \/ (if (pos? depth)
               (recur (inc i) depth (str current c) acc)
               (recur (inc i) depth "" (conj acc current)))
          (recur (inc i) depth (str current c) acc))))))

(defn parse-path
  "A route into `{:template :words :params :addresses-one?}`.

  The template carries `{name}` where the route carried a capture group, so a
  caller can fill it from a flag without ever seeing the regex."
  [path]
  (loop [[segment & more] (split-segments path)
         previous nil
         template [] words [] params []]
    (if-not segment
      {:template (str "/" (str/join "/" template))
       :words (vec (remove #{"api"} words))
       :params params
       :addresses-one? (boolean (and (seq template)
                                     (str/starts-with? (last template) "{")))}
      (if (capture? segment)
        (let [base (param-name previous)
              taken (set (map :name params))
              name (if (contains? taken base) (str base "-2") base)]
          (recur more previous
                 (conj template (str "{" name "}"))
                 words
                 (conj params {:name name :in "path" :required? true})))
        (recur more segment (conj template segment) (conj words segment) params)))))

(def collection-verbs
  {"GET" "list" "POST" "create" "DELETE" "delete" "PUT" "put"})

(def one-verbs
  {"GET" "show" "POST" "update" "DELETE" "delete" "PUT" "update"})

(defn name-commands
  "Command words for every route, disambiguated only where they would collide.

  `business bind` is one route and reads best without a verb tacked on.
  `/api/business` is two — a list and a create — and `/api/…/documents` is four
  once the single-document routes are counted, so those take the verb that says
  which. Adding a verb everywhere would make the common commands longer to solve
  a problem most of them do not have."
  [routes]
  (let [by-words (group-by :words routes)]
    (mapv (fn [{:keys [words method addresses-one?] :as route}]
            (let [siblings (get by-words words)
                  verb (if addresses-one?
                         (get one-verbs method method)
                         (get collection-verbs method method))]
              (assoc route :command
                     (if (= 1 (count siblings))
                       (if addresses-one? (conj words verb) words)
                       (conj words verb)))))
          routes)))

;; ---------------------------------------------------------------------------

(defn routes
  "Every route in `source`, in the order the `cond` tests them.

  Named patterns are inlined first, so a route reached through `page-pattern` is
  read exactly like one written as a literal."
  [source]
  (let [source (expand-pattern-vars source)
        starts (clause-starts source)]
    (->> (map vector starts (concat (rest starts) [(count source)]))
         (keep (fn [[start next-start]]
                 (let [end (form-end source start)
                       test (subs source start end)
                       body (subs source end (min next-start (count source)))
                       paths (paths-of test)
                       methods (methods-of test)]
                   (when (and (seq paths) (seq methods))
                     (for [method methods path paths]
                       (merge {:method method :route path :gate (gate-of body)
                               :flags (flags-of body)}
                              (parse-path path)))))))
         (apply concat)
         distinct
         vec)))

(defn registry
  "The routes an agent bearer session can reach, as commands.

  `:human` routes are counted rather than published: funding, settlement and
  governed approval need a WebAuthn user-verifying assertion, which no CLI and no
  agent can produce (ADR-0006). A command certain to refuse invites a caller to
  try and tells them nothing about why.

  `:none` routes are counted too — page rendering, health, the OAuth callbacks a
  provider redirects to, the passkey handshake. They are either not operations or
  not answerable outside a browser."
  [source generated-from]
  (let [all (routes source)
        reachable (->> all
                       (filter (comp #{:app :session} :gate))
                       name-commands
                       (sort-by (juxt :command :method))
                       (mapv #(select-keys % [:command :method :template :params
                                              :flags :gate :route])))]
    {:schema "cloud.itonami.app.commands.v1"
     :generated-from generated-from
     :counts {:routes (count all)
              :commands (count reachable)
              :human-only (count (filter (comp #{:human} :gate) all))
              :unauthenticated (count (filter (comp #{:none} :gate) all))}
     :commands reachable}))
