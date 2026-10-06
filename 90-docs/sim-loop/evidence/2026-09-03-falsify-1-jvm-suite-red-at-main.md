# falsify-1: origin/main 先端の JVM フルスイートは赤 (コンパイル不可)

- 日時: 2026-09-03 (JST)
- 測定者: itonami-maint
- 対象: cloud-itonami-app @ origin/main 172bd46
  ("feat(cli): orgs list — tenant overview with per-organization Bot counts (#273)")
- 環境: sibling worktree maturity-falsify1 (detached HEAD 172bd46, 本体 checkout
  bot/maturity-sim-loop とは分離 / dirty WIP には touch しない)
- Clojure CLI: 1.12.5.1654

## 主張 (反証対象)

maturity.md テスト軸 score 3 の前提「main は実質的に緑であり、定期緑保証が
無いのは手続きの問題にすぎない」。

## 手順と出力 (そのまま記録)

1. `git worktree add ../maturity-falsify1 172bd46`
2. `clojure -M:test` → **EXIT 1**:

       Syntax error compiling at (cloud/itonami/app/bots.clj:1907:22).
       No such var: bot/face

   (フルレポート:
   /var/folders/31/st4xq0g12v3cn1b9yg5zcrsm0000gn/T/clojure-4462152065859668129.edn)

3. 最小 repro (1 コマンド):

       clojure -M -e "(require 'cloud.itonami.app.bots)"
       → Syntax error compiling at (cloud/itonami/app/bots.clj:1907:22).
         No such var: bot/face

4. CI-required path `bash scripts/ci-jvm-free-emit` → **EXIT 0, 緑**
   ("ci-jvm-free-emit: ok (kexe verify Linux remains HOLD; not faked)")。

## 根因 (最小解析)

- PR #273 (172bd46) が `src/cloud/itonami/app/bots.clj:1907` で
  `face-hash (Math/abs (long (.hashCode ...)))` を `bot/face (:bot/id b)` に
  置換した (git log -L 1907,1907 で確認)。
- 呼び出される `bot/face` は main 上のどこにも定義されていない
  (`grep -rn "defn face" src/` → bot.cljc に face セクション
  avatar-colors/avatar-glyphs はあるが `face` の defn は無し)。
- 呼び出し側だけが main に着地し、定義 (bot.cljc face-hash 系、他 bot の WIP
  領域) が未着地。runtime で動いている release は旧ビルドのため現行サーバは
  無傷だが、次の JVM 側デプロイは確実に落ちる。

## 結論: refuted

「main は実質的に緑」は反証された。origin/main 先端は JVM スイートが
1 テストも実行されずに死ぬ。かつ CI (ci.yml) は JVM-free emit しか required
にしていないため、PR #273 は CI 緑のまま着地した。これは手続き問題ではなく、
「CI 緑 = main 緑」という主張そのものが壊れていることの実測例。

## 修理案 (Tier 2 — 着地は人間判断)

案 A (最小): bots.clj:1907 を PR #273 直前の face-hash 式に revert し、
bot/face 定義 (bot.cljc face-hash 系 WIP 7 ファイル) の着地後に再置換する。
案 B (本質): ci.yml の required path に `clojure -M:test` (現状
leftover-jvm-tests.yml で workflow_dispatch のみ) を加えるか、JVM スイートが
コンパイル死したら required チェックが赤になる経路を用意する。
※ bot.cljc face-hash 系は他 bot の WIP 領域 — 本 bot は接触しない (Tier 3 避け)。
