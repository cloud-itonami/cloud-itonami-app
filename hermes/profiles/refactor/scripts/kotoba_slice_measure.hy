#!/usr/bin/env hy
"kotoba-slice-measure — cloud-itonami-app の clj/cljc → .kotoba slice 適格候補を実測。

これは「測定」だけの script。agent に着地させない。するのは:
  1. cloud-itonami-app（west child、自身の git repo）の現 HEAD を報告
  2. `git ls-files` を app 自身の checkout で実行し src/resources 配下
     .clj/.cljc を列挙、ns + require で DAG を組みトポロジカルソート
  3. wave-0（依存が全て .kotoba 化済み or 外部）の実装ノードのうち slice 適格
     (:import / throw / regex / java.* / javax を持たない) ものを列挙
  4. 各候補を `amu check --jvm-free` で実測（推測しない）
  5. workspace/kotoba-slice-ledger.jsonl に append + 機械可読行を stdout

stdout の機械可読行:
  CANDIDATE <ns> : <path>  amu=N
  NONE  (0 candidates — 欠陥でなく、今 slice 適格な候補が無い実測)
  REFUSED <reason>        (exit 2: 測れなかった)

履歴(2026-09-08): superproject(.gitignore:246 が app 全体を ignore)で
git ls-files すると常に 0 件 → 空列挙の NONE を毎回出していた。修正:
列挙・読み取り・amu 実行を app 自身の checkout (APP) に対して行う。
"
(import json os re subprocess sys datetime)
(setv ROOT ((. os.environ get) "REFACTOR_ROOT" "~/github/com-junkawasaki"))
(setv APP
  ((. os.environ get) "REFACTOR_APP"
    ((. os.path join) ROOT "orgs/cloud-itonami/cloud-itonami-app")))
(setv HERMES_HOME
  ((. os.environ get) "HERMES_HOME"
    ((. os.path join) (get os.environ "HOME") ".hermes/profiles/refactor")))
(setv AMU
  ((. os.environ get) "AMU"
    ((. os.path join) ROOT "orgs/kotoba-lang/amu/bin/amu")))
(setv LEDGER ((. os.path join) HERMES_HOME "workspace" "kotoba-slice-ledger.jsonl"))

(defn sh [cmd [cwd ROOT]]
  (try
   (setv r
     (subprocess.run cmd :cwd cwd :capture_output True :text True :timeout 120))
   (return
     (+
       ((. r.stdout strip))
       (or ((. r.stderr strip)) "")))
   (except [e Exception] (return (+ "ERR:" (str e))))))

(defn list_clj []
  (setv out (sh ["git" "ls-files" "src" "resources"] :cwd APP))
  (return
    (lfor l (out.splitlines)
      :if (l.endswith #(".clj" ".cljc" ".kotoba"))
      l)))

(defn ns_of [rel]
  (try
   (with [fh (open ((. os.path join) APP rel) :encoding "utf-8" :errors "replace")]
     (setv m (re.search "\\(ns\\s+([^\\s\\[):]+)" (fh.read))))
   (return (if m ((. (m.group 1) replace) "-" "_") None))
   (except [OSError] (return None))))

(defn requires_of [rel]
  (try
   (with [fh (open ((. os.path join) APP rel) :encoding "utf-8" :errors "replace")]
     (setv text (fh.read)))
   (except [OSError] (return (set))))
  (setv deps (set))
  (for [m (re.finditer "\\(?\\s*\\[([^\\s\\]\\[:\\\"]+)" text)]
    (setv t (m.group 1))
    (when (and (in "." t) (not (t.startswith ":")))
      (deps.add (t.replace "-" "_"))))
  (return deps))

(defn slice_ready [rel text]
  "native word-typed slice (:string/:i64/:bool) に載る判定のみ適格。機構/IO/throw は不可。"
  (return
    (and
      (not (re.search "\\(\\s*:import" text))
      (not (re.search "[ja]?va[vsx]?\\.[a-zA-Z]" text))
      (not (re.search "throw|ex-info|re-matches|re-find|System/getenv|slurp|spit|read-string" text)))))

(defn main []
  (try
   (setv head (sh ["git" "rev-parse" "--short" "HEAD"] :cwd APP))
   (setv files (list_clj))
   (setv ns_map {})
   (for [f files]
     (setv n (ns_of f))
     (when n (ns_map.setdefault n f)))
   (setv kotoba_ns
     (sfor #(n f) (ns_map.items)
       :if (f.endswith ".kotoba")
       n))
   (setv cands [])
   (for [#(ns f) (sorted (ns_map.items))]
     (when (or (in ns kotoba_ns) (not (f.endswith #(".clj" ".cljc")))) (continue))
     (try
       (with [fh (open ((. os.path join) APP f) :encoding "utf-8" :errors "replace")]
         (setv text (fh.read)))
       (except [OSError] (continue)))
     (when (not (slice_ready f text)) (continue))
     (setv deps (requires_of f))
     (setv unmig
       (lfor d deps
         :if (and (in d ns_map) (not-in d kotoba_ns))
         d))
     (when (not unmig) (cands.append #(ns f))))
   (setv rows [])
   (for [#(ns f) cands]
     (setv chk
      (sh [AMU "check" ((. os.path join) APP f) "--jvm-free"]))
     (rows.append {"ns"           ns
                   "path"         f
                   "amu-ok"       (in ":ok true" chk)
                   "amu-err-head" (if (not-in ":ok true" chk) (cut chk 80) "")}))
   (os.makedirs ((. os.path dirname) LEDGER) :exist_ok True)
   (setv ts
     ((. ((. datetime.datetime now) (. datetime.timezone utc)) isoformat)))
   (with [fh (open LEDGER "a" :encoding "utf-8")]
     (fh.write (+
                (json.dumps {"as-of" ts  "head" head  "candidate-count" (len rows)  "candidates" rows})
                "\n")))
   (when (not rows) (print f"NONE  (0 slice-ready wave-0 candidates @ {head})") (sys.exit 0))
   (for [r rows]
     (print
      f"CANDIDATE | {(get r "ns")} : {(get r "path")}  amu={(get r "amu-ok")}"))
   (sys.exit 0)
   (except [e Exception]
     (print f"REFUSED  {(. (type e) __name__)}: {e}")
     (sys.exit 2))))

(when (= __name__ "__main__") (main))