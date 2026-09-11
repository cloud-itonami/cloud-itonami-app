#!/usr/bin/env nbb
;; itonami_skills — the repository's own slash commands (ADR-2609072600).
;;
;; Claude Code and hermes both read a repository's `.claude/` and offer what
;; they find as `/name`. itonami read nothing: it had twenty-nine slash
;; commands of its own and no way to reach the thirty-two this workspace
;; keeps in `.claude/skills/`.
;;
;; ## What is a project command
;;
;;   .claude/skills/<name>/SKILL.md   YAML front matter + a Markdown body
;;   .claude/commands/<name>.md       the same, without the directory
;;
;; Both are Claude Code's shapes and both are read here, so a repository does
;; not have to pick one to be usable from this CLI.
;;
;; ## The parsing is separate from the reading
;;
;; `parse` takes text and gives an entry; `discover` takes two functions (list
;; a directory, read a file) and gives entries. Neither touches `node:fs`, so
;; the rules -- what counts as front matter, what a missing name falls back to,
;; what happens to a file that cannot be parsed -- are testable without a
;; repository on disk.
;;
;; ## A skill that cannot be parsed is still listed
;;
;; With a `:problem` on it, and its directory name as its name. A skill dropped
;; for having bad front matter would make "this repository has no skills" and
;; "this repository has a skill I could not read" the same screen, which is the
;; failure CLAUDE.md's second question is about.

(ns itonami-skills
  (:require [kotoba.lang.text :as str]))

(def ^:private fence "---")

(defn parse-front-matter
  "The `key: value` lines between the leading `---` fences, and the body after.

  Values keep every colon after the first: a description is a sentence and
  sentences contain colons. A file with no front matter is all body, which is
  not an error -- it is a command whose description this cannot state."
  [text]
  (let [lines (str/split-lines (str text))]
    (if-not (= fence (str/trim (str (first lines))))
      {:front {} :body (str text)}
      (let [rest* (rest lines)
            end (first (keep-indexed #(when (= fence (str/trim (str %2))) %1) rest*))]
        (if-not end
          ;; An unterminated fence is not front matter; treating it as one
          ;; would swallow the whole file as headers.
          {:front {} :body (str text)}
          {:front (into {}
                        (keep (fn [l]
                                (when-let [i (str/index-of l ":")]
                                  (let [k (str/trim (subs l 0 i))
                                        v (str/trim (subs l (inc i)))]
                                    (when-not (str/blank? k)
                                      [(keyword k) v])))))
                        (take end rest*))
           :body (str/join "\n" (drop (inc end) rest*))})))))

(defn parse
  "One project command from its text. `fallback-name` is used when the front
  matter does not name it -- the directory or file name always does."
  [fallback-name text]
  (let [{:keys [front body]} (parse-front-matter text)
        name (or (not-empty (str (:name front))) fallback-name)]
    (cond-> {:name name
             :description (str (:description front))
             :body body
             :bytes (count (str text))}
      (str/blank? (str (:name front)))
      (assoc :problem "front matter に name がありません（ディレクトリ名を使います）"))))

(defn command-name
  "The slash name. Only what a person can type without quoting: anything else
  becomes `-`, and a name that reduces to nothing is refused by `discover`."
  [n]
  (-> (str n) str/trim str/lower (str/replace #"[^a-z0-9_-]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn discover
  "Every project command under `root`, sorted by name.

  `list-dir` returns the entries of a directory or nil; `read-file` returns a
  file's text or nil. Both are passed in so this namespace never reaches for
  the filesystem and the rules can be tested against a map."
  [root {:keys [list-dir read-file join]}]
  (let [join (or join (fn [& parts] (str/join "/" parts)))
        from-skills
        (keep (fn [d]
                (let [p (join root ".claude" "skills" d "SKILL.md")]
                  (when-let [t (read-file p)]
                    (assoc (parse d t) :source p :kind :skill))))
              (sort (or (list-dir (join root ".claude" "skills")) [])))
        from-commands
        (keep (fn [f]
                (when (str/ends-with? f ".md")
                  (let [p (join root ".claude" "commands" f)]
                    (when-let [t (read-file p)]
                      (assoc (parse (subs f 0 (- (count f) 3)) t)
                             :source p :kind :command)))))
              (sort (or (list-dir (join root ".claude" "commands")) [])))]
    (->> (concat from-skills from-commands)
         (keep (fn [e]
                 (let [n (command-name (:name e))]
                   (when-not (str/blank? n) (assoc e :command (str "/" n))))))
         (sort-by :command)
         vec)))

(defn admit
  "Split discovered commands into the ones that may be registered and the ones
  that may not, with a reason.

  `taken` is the set of slash names already registered. A project command NEVER
  shadows a built-in: `/status` meaning two things depending on which
  repository you are standing in is worse than not having the project one, and
  a shadow that happens silently is worse again."
  [entries taken max-bytes]
  (reduce (fn [acc {:keys [command bytes] :as e}]
            (cond
              (contains? taken command)
              (update acc :refused conj (assoc e :reason "組み込み command と同名"))

              (and max-bytes (> bytes max-bytes))
              (update acc :refused conj
                      (assoc e :reason (str "大きすぎます (" bytes " > " max-bytes " bytes)")))

              (some #(= command (:command %)) (:admitted acc))
              (update acc :refused conj (assoc e :reason "同名の project command が既にあります"))

              :else (update acc :admitted conj e)))
          {:admitted [] :refused []}
          entries))

(defn prompt-for
  "What is sent to the agent when a project command is invoked.

  The body is instructions and the arguments are the request; saying which is
  which is the difference between running a skill and pasting a document."
  [{:keys [name body]} args]
  (let [args (str/trim (str/join " " args))]
    (str "このリポジトリの skill `" name "` の手順に従って作業してください。\n\n"
         "--- skill: " name " ---\n"
         (str/trim (str body))
         "\n--- ここまで ---\n\n"
         "依頼: " (if (str/blank? args) "（引数なし。skill の既定の一反復を実行してください）" args))))

(defn plugin
  "Provides :ctx/skills. Deps: :ctx/config -- the install and, through it, the
  reader every layer shares.

  The value carries the discovered entries and the refusals, so `/help` and the
  start screen can both say what was found AND what was skipped without either
  of them repeating the discovery."
  [{:keys [root io max-bytes]}]
  {:name :itonami.skills
   :inject [:ctx/config]
   :provides :ctx/skills
   :description "project slash commands from .claude/skills and .claude/commands"
   :apply (fn [_]
            (let [entries (if root (discover root io) [])]
              {:root root
               :entries entries
               :max-bytes max-bytes
               :prompt-for prompt-for
               :admit (fn [taken] (admit entries taken max-bytes))}))})
