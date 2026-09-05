# cloud-itonami-app 成熟度 (正本)

現在段階: L1 (稼働はするが、反証可能性のある品質主張が軸ごとに未整備)

測定日: 2026-09-05 (falsify-17; 初回ベースライン 2026-09-03)
測定者: itonami-maint

## 7 軸スコア (0-5)

| 軸 | score | 根拠 (測定) |
|---|---|---|
| spec/契約 | 3 | ADR 24 本 (+ ADR-2607254000 の Tier 境界)、commands.edn に 208 コマンドの解決/path-param 契約 (flags は hint で値スキーマなし — falsify-10 実測: プレースホルダ 128 すべてに `:in "path"` 宣言、欠落 0、408=208+70+130 整合)。route 再スキャン vs レジストリの機械検証テスト実在 (commands_test 16 deftest)。値スキーマ (型/必須性) の機械検証は未整備 |
| 実装 | 3 | src 231 ファイル、全主要面 (bots/webhook/hermes-compat/store) 実装済み。virtual-shell は未活性 |
| テスト | 3 | test 205 ファイル。フルスイートが異なるリビジョンで完走: falsify-6 (bde2171)、falsify-7 (2bca892、約45分)、falsify-14 (clean HEAD、1 failure = 赤-4 のみ)、falsify-15 (負荷下 2292 tests / 13880 assertions / 1 failure = 赤-4 のみ)、**falsify-16 (merged main 1905580、負荷下 2292 tests / 13929 assertions / 0 failures EXIT=0)**。決定論的赤 0、flake 修理 (赤-5、PR #280) 着地済み。3 止まりの根拠: flake リトライ機構なし、OPEN 赤-4 未解決、テスト実行がディスク飽和に脆弱 (falsify-16/17) |
| 反証 | 3 | falsify-1〜17 を evidence/ に記録。falsify-9: 赤-2「KeepAlive 欠如で silent-dead」説を反証 (主因は ops-classpath.sh が upstream の authority.scope 追加に未追従で nbb ロード即死)。falsify-10: spec 軸主張を「解決/path-param 契約 (値スキーマなし)」に範囲修正。falsify-11: 赤-2 案 A「classpath 修正で復旧」説を反証試行 — 決定論的依存連鎖を段階実測、案 A の 3 src 追加が必須十分と確認し expiry-alert.cljs rc=0 まで完全復旧を実測 → 精緻化付きで SURVIVED。検証の终点は rc=0、plist 再 bootstrap が必須条件。falsify-12: テスト軸「赤-5 flake は時間切れ型のみ」説 → survived、3 bound 非同期設計を競合窓として同定。falsify-14 (2026-09-05): リスク-2 dirty 前提を REFUTED (本体 main clean 実測)。falsify-16 (2026-09-05): 「着地後の負荷下完走で flake サイトが赤になる」説 → survived (merged main 1905580 で 0 failures 実測、OPEN 赤-5 CLOSED)。falsify-17 (2026-09-05): 「falsify-16 の cache 整理でディスク満杯は解消 (一回性)」説を REFUTED — 同日中に /System/Volumes/Data が 100% / avail 1.9Gi に再飽和を実測、ディスク飽和は再発性の構造リスクと確定 (evidence/2026-09-05-falsify-17.md) |
| 再現性 | 3 | launchd で server/host/tick は再現稼働。releases/ 全 77 ツリーが対応 git commit と byte 完全一致 (falsify-3 実測)。ただし不変性は運用規約のみで OS 強制なし |
| governor 統合 | 3 | tamaki tick は 1430 repo を 900s 間隔でスキャン継続。ただし **1559 連続 worktree-failed** (2026-08-14〜、毎 tick) — falsify-6 で原因特定済み (tick の rm -rf が git-annex read-only 残骸を取りこぼし → worktree add が永久 already exists)。修理は tamaki リポ側 (chmod -R u+wx 追加、Tier 2 で提起)。着地 0 landed は継続 |
| 運用 | 3 | falsify-6 実測: GET /health -> 200、ui-host 稼働。launchctl 実測 (2026-09-05): server 系 (local 等) 稼働継続、expiry-alert は last exit 1 / not running のまま。falsify-9/11/13 で主因確定: ops-classpath.sh が upstream の authority.scope 追加に未追従で nbb ロード即死。falsify-17 で network-awai origin/main 先端 (dd34f563) の ops-classpath.sh も authority/src を含まないことを再実測 — 帰属は upstream 先端まで不変。log は Aug 31 09:00:05 JST (mtime 1788134405) で静止、次回発火 2026-09-07 09:00 (plist Weekday=1 実測)。**新規リスク (falsify-17)**: Data volume 100% / avail 1.9Gi 再飽和 — テスト・スイート・journal 生成系すべてに再点火しうる |

## OPEN 赤

- OPEN 赤-1: ~~JVM スイートがコンパイル死~~ → falsify-5 で CLOSED (測定)。
  さらに falsify-6 でフル完走を確認。
- OPEN 赤-2: expiry-alert launchd job が last exit 1 のまま not running。
  **falsify-9 で帰属修正 (REFUTED)**: 主因は KeepAlive 欠如ではなく、
  ops-classpath.sh が upstream org-chainagnostic-cacao 83f3169 (2026-08-15)
  の authority.scope 追加に未追従 → nbb ロードが
  `Could not find namespace: authority.scope` で即死 (log と同一エラーを実再現)。
  修理案 A (主): ops-classpath.sh に authority/src + org-nist-sha2/src +
  datom-source/src を追加 (falsify-11 で expiry-alert.cljs rc=0 まで実測、3 src は必須十分)。着地には plist 再生成 + launchctl bootout/bootstrap が必須 (launchd は bootstrap 時に classpath を cache)。
  案 B (従): plist に KeepAlive 付与 — 案 A 無しでは無意味。kagi get は JVM 起動込みで遅く 1 実行 ~4 分 (falsify-11)。
  **falsify-17 で範囲拡張 (帰属不変)**: network-awai origin/main 先端
  (dd34f563) でも classpath に authority/src 無し (grep 実測 0)。
  log 静止・exit 1 も再実測。**未着地**。
  (evidence/2026-09-04-falsify-9.md / -11.md / 2026-09-05-falsify-17.md 参照)
- OPEN 赤-3: ~~launcher_test leftover-jvm-aliases-are-gone が決定論的赤~~
  → falsify-6 で CLOSED: PR #278 (e97b6ed) がテストを :launcher-known-aliases
  契約に改訂済み、フルスイートで緑を確認。
- OPEN 赤-4: launcher_test.clj:162 resident-clone-resolves-shell-from-
  workspace-root が赤。**falsify-8 で帰属修正 (REFUTED)**: 「worktree 環境
  限定の環境依存」説は反証 — ~/.hermes を使わない合成レイアウトでも
  決定論的に再現する launcher 実バグ (bin/ の 2 階層上に kotoba-lang/shell
  が存在すると repo 相対分支が WORKSPACE_ROOT を shadow)。テスト契約
  (WORKSPACE_ROOT 必勝) と launcher 実装 (repo 相対優先) は 13c45a5
  (2026-08-10, テスト初日にして矛盾) 以来の不一致で、ADR-2608272200
  (2026-08-27) も「real bug in the launcher's root resolution」と自認済み。
  修理案 2 抜 (案 A: launcher を WORKSPACE_ROOT 優先に / 案 B: テストを
  repo 相対優先契約に改訂) は bin/ または test/ への変更のため
  evidence/2026-09-04-falsify-8.md 参照。着地は kanban/human 判断 (Tier 2)。
  falsify-16 では本テストも緑になったが、/private/tmp worktree は shadow
  レイアウトを持たないためで反証ではない (帰属不変)。
- OPEN 赤-5: ~~bots_test.clj:1566 durable-goal 並行 deref が高負荷環境で
  flake~~ → **falsify-16 (2026-09-05) で CLOSED**: PR #280 は
  2026-09-05T06:52:58Z に merge 済み (mergeCommit 1905580、gh 実測)。
  merged main の detached worktree (/private/tmp/mt-merged-main、本体
  checkout 未 touch) で負荷環境下 (load avg 11-27、resident JVM 5 走行) の
  フルスイートを完走: Ran 2292 tests / 13929 assertions / **0 failures,
  0 errors** (EXIT=0)。flake サイト緑を再確認
  (evidence/2026-09-05-falsify-16.md)。修理は falsify-15 の 3 bound 整合
  (entered 5000ms > release 3000ms + invariant 機械検証 deftest)。

## 既知リスク

- リスク-1: releases/<sha> の不変性が sha 名 + symlink 規約のみで OS 強制されて
  いない (falsify-3)。欠損/改変は現時点で 0 実測。
- リスク-2: 本体 checkout dirty 時は Tier 1 着地不可能。falsify-14 で
  dirty 前提は REFUTED (09-05 時点で main clean、face-hash WIP は
  f4f2964 で着地済み) — dirty は再び生じうるので、反復ごとの porcelain
  再確認を Tier 1 着地の前提条件とする。
  (evidence/2026-09-05-falsify-14.md)
- リスク-3: journal が 4MiB bound の 34.6% (2026-09-04 実測 1451542/4194304)。
  52.3% → 0.04% → 34.6% と振動、checkpoint/rotate 挙動の観察継続。
  2026-09-05 実測: data/state.journal.edn 423784 bytes (10.1%)。
- リスク-4: falsify-6 フルスイートの所要が約66分 (負荷環境下)。
  tick の 900s 間隔内に test-baseline が収まらない可能性 → tick が
  baseline 測定を諦める構造的リスク。所要の安定実測が必要。
- リスク-5 (falsify-17 新規): ディスク飽和は再発性の構造リスク —
  falsify-16 で avail 16GB を確保した同日中に /System/Volumes/Data が
  100% / avail 1.9Gi に再飽和 (実測)。満杯時はスイートが store journal
  mismatch として異常終了する (falsify-16 で 2 回実害)。テスト実行の
  前提条件として disk-avail 確認を反復手順に必須化。増加源の同定
  (du ツリー計測) は未着手。

## === NEXT ===

1. ~~bots_test.clj:1566 を単独再実行し確定~~ → falsify-7 で CLOSED (flake 確定、
   OPEN 赤-5 として記録)。
2. ~~OPEN 赤-4 の帰属確定 (環境依存か実バグか)~~ → falsify-8 で REFUTED
   (実バグ確定、修理案 A/B 付きで Tier 2 report 完了)。
3. tick 側修理案 (chmod -R u+wx / git-annex 初期化回避) を tamaki リポに
   Tier 2 report として提起 (未着手のまま)。
4. OPEN 赤-2 (expiry-alert) の修理着地: falsify-11 で案 A が rc=0 まで
   検証済み、着地ランブック完成。falsify-17 で network-awai origin/main
   先端まで authority/src 未追加を再実測。実着地 (ops-classpath.sh 修正 +
   plist 再生成 + launchctl bootstrap) は kanban/human 判断待ち (Tier 2)。
5. OPEN 赤-4 (launcher shell-dir 優先順位) の修理案 A/B は kanban/human
   判断待ち。
6. ~~spec 軸「208 コマンドの schema」説の反証~~ → falsify-10 で survived
   (path-param 契約は完全、値スキーマは未整備 — 主張の範囲を修正済み)。
7. ~~着地後の負荷下完走で flake サイトを確認~~ → falsify-16 で完了
   (OPEN 赤-5 CLOSED)。
8. (falsify-17 新規) ディスク再飽和の増加源を同定する: du ツリー計測で
   Data volume 879Gi used の主因を特定し、テスト実行が安全に実行できる
   最小 avail 閾値を測定する (測定のみ、削除は operator 判断)。
9. (falsify-16 提案の Tier 2 runbook) test-data の git-annex read-only
   残骸への chmod -R u+wx 手順をテスト runbook に追記提案 —
   tamaki tick の worktree-failed と同一 fail モード。
