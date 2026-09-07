
;; The repository's own slash commands: what is found, what is refused, and
;; what is sent when one is invoked.
;;
;;   nbb test/itonami_skills_nbb.cljs
;;
;;   0  all checks passed
;;   1  a check failed

(ns itonami-skills-nbb
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is run-tests]]
            [nbb.classpath :as classpath]
            [nbb.core :refer [*file*]]))

(classpath/add-classpath (path/resolve (path/dirname *file*) ".." "bin"))
(require '[itonami-skills :as sk])

(def ^:private skill-text
  "---\nname: west-pin-advance\ndescription: pin を進める。URL は https://x/y のように : を含む\n---\n\n# body\n\nline two\n")

;; ---------------------------------------------------------------------------
;; parsing
;; ---------------------------------------------------------------------------

(deftest front-matter-keeps-every-colon-after-the-first
  ;; A description is a sentence and sentences contain colons. Splitting on all
  ;; of them would cut the description at the first URL.
  (let [{:keys [front body]} (sk/parse-front-matter skill-text)]
    (is (= "west-pin-advance" (:name front)))
    (is (str/includes? (:description front) "https://x/y"))
    (is (str/starts-with? body "\n# body"))))

(deftest a-file-with-no-front-matter-is-all-body
  ;; Not an error: a command whose description this cannot state is still a
  ;; command. Refusing it would hide it.
  (let [{:keys [front body]} (sk/parse-front-matter "# just markdown\n")]
    (is (= {} front))
    (is (= "# just markdown\n" body))))

(deftest an-unterminated-fence-is-not-front-matter
  ;; Treating it as one would swallow the whole file as headers, and the body
  ;; -- the instructions -- would be empty.
  (let [{:keys [front body]} (sk/parse-front-matter "---\nname: x\n\nbody here\n")]
    (is (= {} front))
    (is (str/includes? body "body here"))))

(deftest a-skill-without-a-name-is-listed-with-a-problem
  ;; Dropping it would make "this repository has no skills" and "I could not
  ;; read its skill" the same screen.
  (let [e (sk/parse "fallback-dir" "---\ndescription: d\n---\nbody")]
    (is (= "fallback-dir" (:name e)))
    (is (some? (:problem e)))
    (is (= "d" (:description e))))
  (is (nil? (:problem (sk/parse "d" skill-text)))))

(deftest a-slash-name-is-something-a-person-can-type
  (is (= "west-pin-advance" (sk/command-name "west-pin-advance")))
  (is (= "a-b" (sk/command-name "  A  B  ")))
  (is (= "x" (sk/command-name "--x--")))
  (is (= "" (sk/command-name "///"))))

;; ---------------------------------------------------------------------------
;; discovery
;; ---------------------------------------------------------------------------

(def ^:private tree
  {"/r/.claude/skills" ["west-pin-advance" "empty-dir" "uriage"]
   "/r/.claude/commands" ["deploy.md" "notes.txt"]
   "/r/.claude/skills/west-pin-advance/SKILL.md" skill-text
   "/r/.claude/skills/uriage/SKILL.md" "---\nname: uriage\ndescription: 売上\n---\nbody"
   "/r/.claude/commands/deploy.md" "---\nname: deploy\ndescription: ship it\n---\ngo"})

(def ^:private io
  {:join (fn [& parts] (str/join "/" parts))
   :list-dir (fn [d] (get tree d))
   :read-file (fn [f] (get tree f))})

(deftest both-of-claude-codes-shapes-are-read
  ;; `.claude/skills/<name>/SKILL.md` and `.claude/commands/<name>.md`. A
  ;; repository should not have to pick one to be usable from this CLI.
  (let [es (sk/discover "/r" io)]
    (is (= ["/deploy" "/uriage" "/west-pin-advance"] (mapv :command es)))
    (is (= #{:skill :command} (set (map :kind es))))
    ;; a directory with no SKILL.md, and a non-.md file, are both skipped
    (is (not-any? #(= "empty-dir" (:name %)) es))
    (is (not-any? #(str/includes? (str (:command %)) "notes") es))))

(deftest a-repository-with-nothing-discovers-nothing
  ;; The control for the count on the start screen: zero must be reachable and
  ;; must not throw.
  (is (= [] (sk/discover "/nowhere" io))))

;; ---------------------------------------------------------------------------
;; admission
;; ---------------------------------------------------------------------------

(deftest a-project-command-never-shadows-a-built-in
  ;; `/status` meaning two things depending on which repository you are
  ;; standing in is worse than not having the project one -- and a shadow that
  ;; happens silently is worse again, so the refusal is a value, not a drop.
  (let [es (sk/discover "/r" io)
        {:keys [admitted refused]} (sk/admit es #{"/deploy" "/status"} nil)]
    (is (= ["/uriage" "/west-pin-advance"] (mapv :command admitted)))
    (is (= ["/deploy"] (mapv :command refused)))
    (is (str/includes? (:reason (first refused)) "組み込み"))))

(deftest two-project-commands-of-the-same-name-do-not-both-register
  (let [dup [{:command "/x" :bytes 1} {:command "/x" :bytes 1}]
        {:keys [admitted refused]} (sk/admit dup #{} nil)]
    (is (= 1 (count admitted)))
    (is (= 1 (count refused)))
    (is (str/includes? (:reason (first refused)) "同名"))))

(deftest a-command-too-large-to-send-is-refused-by-name-not-truncated
  ;; Truncating instructions produces a skill that is half a procedure and
  ;; says nothing about the other half.
  (let [{:keys [admitted refused]} (sk/admit [{:command "/big" :bytes 100000}] #{} 65536)]
    (is (empty? admitted))
    (is (str/includes? (:reason (first refused)) "大きすぎ"))
    (is (str/includes? (:reason (first refused)) "100000")))
  ;; and with no ceiling given, nothing is refused for size
  (is (= 1 (count (:admitted (sk/admit [{:command "/big" :bytes 100000}] #{} nil))))))

;; ---------------------------------------------------------------------------
;; what gets sent
;; ---------------------------------------------------------------------------

(deftest the-prompt-says-which-part-is-the-instruction
  (let [e (sk/parse "d" skill-text)
        p (sk/prompt-for e ["a" "b"])]
    (is (str/includes? p "west-pin-advance"))
    (is (str/includes? p "line two") "the body was not sent")
    (is (str/includes? p "依頼: a b"))
    ;; the body is delimited, so the agent can tell instructions from request
    (is (str/includes? p "--- skill: west-pin-advance ---"))
    (is (str/includes? p "--- ここまで ---")))
  ;; no arguments is stated, not left blank
  (is (str/includes? (sk/prompt-for (sk/parse "d" skill-text) []) "引数なし")))

;; `run-tests` returns nil under nbb, so the old
;;   (let [{:keys [fail error]} (run-tests 'ns)] (process.exit (if (pos? …) 1 0)))
;; destructured nil, added 0 to 0, and **exited 0 whether or not tests failed**.
;; Measured 2026-09-07 by breaking one assertion on purpose: the runner printed
;; "1 failures, 0 errors" and still exited 0. A suite that cannot report failure
;; reports that the process ended, not that the tests passed.
;;
;; cljs.test DOES hand the summary to the :end-run-tests report method.
(defmethod t/report [::t/default :end-run-tests] [m]
  (js/process.exit (if (t/successful? m) 0 1)))

(run-tests 'itonami-skills-nbb)
