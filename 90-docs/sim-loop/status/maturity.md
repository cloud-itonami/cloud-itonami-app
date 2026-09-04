# cloud-itonami-app 成熟度 (正本)

現在段階: L1 (稼働はするが、反証可能性のある品質主張が軸ごとに未整備)

測定日: 2026-09-04 (falsify-7; 初回ベースライン 2026-09-03)
測定者: itonami-maint

## 7 軸スコア (0-5)

| 軸 | score | 根拠 (測定) |
|---|---|---|
| spec/契約 | 3 | ADR 24 本 (+ ADR-2607254000 の Tier 境界)、commands.edn に 208 コマンドの schema。ただし契約→テストの機械検証リンクは一部 |
| 実装 | 3 | src 231 ファイル、全主要面 (bots/webhook/hermes-compat/store) 実装済み。virtual-shell は未活性 |
| テスト | 3 | test 205 ファイル。フルスイートが異なるリビジョンで 2 回完走: falsify-6 (bde2171) と falsify-7 (2bca892、所要約45分)。falsify-7: Ran 2291 tests / 13862 assertions / **1 failure** 0 errors。bots_test.clj:1566 は再実行で緑 → 決定論的赤ではなく高負荷 flake と確定 (REFUTED)。決定論的赤 0。残る 1 failure は launcher_test.clj:162 (OPEN 赤-4、worktree 環境限定の環境依存)。3 止まりの根拠: flake リトライ機構なし、OPEN 赤-4 未解決 |
| 反証 | 3 | falsify-1〜7 を evidence/ に記録。falsify-7: bots_test.clj:1566 決定論的赤説を反証 (再実行で緑、flake と確定)、テスト軸の帰属を修正 |
| 再現性 | 3 | launchd で server/host/tick は再現稼働。releases/ 全 77 ツリーが対応 git commit と byte 完全一致 (falsify-3 実測)。ただし不変性は運用規約のみで OS 強制なし |
| governor 統合 | 3 | tamaki tick は 1430 repo を 900s 間隔でスキャン継続。ただし **1559 連続 worktree-failed** (2026-08-14〜、毎 tick) — falsify-6 で原因特定済み (tick の rm -rf が git-annex read-only 残骸を取りこぼし → worktree add が永久 already exists)。修理は tamaki リポ側 (chmod -R u+wx 追加、Tier 2 で提起)。着地 0 landed は継続 |
| 運用 | 3 | falsify-6 実測: GET /health -> 200 (PID 2666)、ui-host 稼働。expiry-alert は依然 not running/exit 1 (KeepAlive=false silent-dead、修理未着手)。cron 側 pre-run probe の server_health 000 は実測 200 と矛盾 — probe 自体の信頼性要観察 |

## OPEN 赤

- OPEN 赤-1: ~~JVM スイートがコンパイル死~~ → falsify-5 で CLOSED (測定)。
  さらに falsify-6 でフル完走を確認。
- OPEN 赤-2: expiry-alert launchd job が last exit 1 のまま not running —
  KeepAlive=false で silent-dead。修理案: plist に KeepAlive 付与
  (evidence/2026-09-03-falsify-2 参照)。**未着手**。
- OPEN 赤-3: ~~launcher_test leftover-jvm-aliases-are-gone が決定論的赤~~
  → falsify-6 で CLOSED: PR #278 (e97b6ed) がテストを :launcher-known-aliases
  契約に改訂済み、フルスイートで緑を確認。
- OPEN 赤-4: launcher_test.clj:162 resident-clone-resolves-shell-from-
  workspace-root が worktree 環境で環境依存赤 — repo 相対解決が
  CLOUD_ITONAMI_WORKSPACE_ROOT を shadow する契約不一致。
  falsify-7 で同一環境での決定論的再現を確認 (2 回連続)。
  evidence/2026-09-04-falsify-6, -7 参照 (優先順位決定が Tier 2 判断事項)。
- OPEN 赤-5 (新): bots_test.clj:1566 durable-goal 並行 deref が高負荷環境で
  flake (deref entered 2000ms が load 85 で尽きる)。falsify-7 で低負荷時は緑。
  修理案: entered の timeout を 2000→5000ms に引き上げ (test/ への変更、
  本体 checkout dirty のため着地せず Tier 2 で記録)。

## 既知リスク

- リスク-1: releases/<sha> の不変性が sha 名 + symlink 規約のみで OS 強制されて
  いない (falsify-3)。欠損/改変は現時点で 0 実測。
- リスク-2: 本体 checkout が他 bot の WIP で dirty の間、Tier 1 着地が不可能 →
  テスト緑の定期保証がない。falsify-6 で tamaki tick 側の worktree-failed
  根因を特定済みだが、tick 修理は tamaki リポの対応待ち。
- リスク-3: journal が 4MiB bound の 34.6% (2026-09-04 実測 1451542/4194304)。
  52.3% → 0.04% → 34.6% と振動、checkpoint/rotate 挙動の観察継続。
- リスク-4 (新): falsify-6 フルスイートの所要が約66分 (負荷環境下)。
  tick の 900s 間隔内に test-baseline が収まらない可能性 → tick が
  baseline 測定を諦める構造的リスク。所要の安定実測が必要。

## === NEXT ===

1. ~~bots_test.clj:1566 を単独再実行し確定~~ → falsify-7 で CLOSED (flake 確定、
   OPEN 赤-5 として記録)。
2. tick 側修理案 (chmod -R u+wx / git-annex 初期化回避) を tamaki リポに
   Tier 2 report として提起 (未着手のまま)。
3. OPEN 赤-2 (expiry-alert KeepAlive) の修理は本体 plist に触れるため
   人間/kanban 判断待ちのまま。
4. OPEN 赤-4/赤-5 の Tier 2 提起 (launcher shell-dir 優先順位決定依頼、
   flake timeout 引き上げ案)。
