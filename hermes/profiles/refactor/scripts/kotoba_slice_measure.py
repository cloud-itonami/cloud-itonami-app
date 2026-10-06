#!/usr/bin/env python3
"""kotoba-slice-measure — cloud-itonami-app の clj/cljc → .kotoba slice 適格候補を実測。

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
"""
import json, os, re, subprocess, sys, datetime

ROOT = os.environ.get("REFACTOR_ROOT", "~/github/com-junkawasaki")
APP = os.environ.get("REFACTOR_APP",
                     os.path.join(ROOT, "orgs/cloud-itonami/cloud-itonami-app"))
HERMES_HOME = os.environ.get("HERMES_HOME", os.path.join(os.environ["HOME"], ".hermes/profiles/refactor"))
AMU = os.environ.get("AMU", os.path.join(ROOT, "orgs/kotoba-lang/amu/bin/amu"))
LEDGER = os.path.join(HERMES_HOME, "workspace", "kotoba-slice-ledger.jsonl")


def sh(cmd, cwd=ROOT):
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=120)
        return r.stdout.strip() + (r.stderr.strip() or "")
    except Exception as e:
        return "ERR:" + str(e)


def list_clj():
    # The app is a west child with its own git repo; the superproject's
    # .gitignore ignores /orgs/cloud-itonami/cloud-itonami-app/ entirely,
    # so git ls-files from ROOT always enumerates 0 files (vacuous NONE).
    # Measure against the app checkout itself (APP).
    out = sh(["git", "ls-files", "src", "resources"], cwd=APP)
    return [l for l in out.splitlines()
            if l.endswith((".clj", ".cljc", ".kotoba"))]


def ns_of(rel):
    try:
        with open(os.path.join(APP, rel), encoding="utf-8", errors="replace") as fh:
            m = re.search(r"\(ns\s+([^\s\[):]+)", fh.read())
        return m.group(1).replace("-", "_") if m else None
    except OSError:
        return None


def requires_of(rel):
    try:
        with open(os.path.join(APP, rel), encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError:
        return set()
    deps = set()
    for m in re.finditer(r"\(?\s*\[([^\s\]\[:\"]+)", text):
        t = m.group(1)
        if "." in t and not t.startswith(":"):
            deps.add(t.replace("-", "_"))
    return deps


def slice_ready(rel, text):
    """native word-typed slice (:string/:i64/:bool) に載る判定のみ適格。機構/IO/throw は不可。"""
    return not re.search(r"\(\s*:import", text) \
       and not re.search(r"[ja]?va[vsx]?\.[a-zA-Z]", text) \
       and not re.search(r"throw|ex-info|re-matches|re-find|System/getenv|slurp|spit|read-string", text)


def main():
    try:
        head = sh(["git", "rev-parse", "--short", "HEAD"], cwd=APP)
        files = list_clj()
        ns_map = {}
        for f in files:
            n = ns_of(f)
            if n:
                ns_map.setdefault(n, f)
        kotoba_ns = {n for n, f in ns_map.items() if f.endswith(".kotoba")}

        # wave-0: .clj/.cljc で、依存が kotoba 化済み or 外部（この repo に無い）
        cands = []
        for ns, f in sorted(ns_map.items()):
            if ns in kotoba_ns or not f.endswith((".clj", ".cljc")):
                continue
            try:
                with open(os.path.join(APP, f), encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
            except OSError:
                continue
            if not slice_ready(f, text):
                continue
            deps = requires_of(f)
            unmig = [d for d in deps
                     if d in ns_map and d not in kotoba_ns]
            if not unmig:
                cands.append((ns, f))

        rows = []
        for ns, f in cands:
            chk = sh([AMU, "check", os.path.join(APP, f), "--jvm-free"])
            rows.append({"ns": ns, "path": f, "amu-ok": ":ok true" in chk,
                         "amu-err-head": chk[:80] if ":ok true" not in chk else ""})

        os.makedirs(os.path.dirname(LEDGER), exist_ok=True)
        ts = datetime.datetime.now(datetime.timezone.utc).isoformat()
        with open(LEDGER, "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"as-of": ts, "head": head,
                                 "candidate-count": len(rows),
                                 "candidates": rows}) + "\n")

        if not rows:
            print(f"NONE  (0 slice-ready wave-0 candidates @ {head})")
            sys.exit(0)
        for r in rows:
            print(f"CANDIDATE | {r['ns']} : {r['path']}  amu={r['amu-ok']}")
        sys.exit(0)
    except Exception as e:
        print(f"REFUSED  {type(e).__name__}: {e}")
        sys.exit(2)


if __name__ == "__main__":
    main()