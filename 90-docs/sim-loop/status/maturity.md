# cloud-itonami-app 成熟度 (正本)

現在段階: L1 (稼働はするが、反証可能性のある品質主張が軸ごとに未整備)

測定日: 2026-09-03
測定者: itonami-maint (初回ベースライン)

## 7 軸スコア (0-5)

| 軸 | score | 根拠 (測定) |
|---|---|---|
| spec/契約 | 3 | ADR 22 本 (+ ADR-2607254000 の Tier 境界)、commands.edn に 208 コマンドの schema。ただし契約→テストの機械検証リンクは一部 |
| 実装 | 3 | src 221 ファイル、全主要面 (bots/webhook/hermes-compat/store) 実装済み。virtual-shell は未活性 |
| テスト | 2 | test 199 ファイル。ただし falsify-1 実測: origin/main 172bd46 で `clojure -M:test` は 1 テストも走らずコンパイル死 (`No such var: bot/face`, bots.clj:1907)。CI required path は JVM-free emit のみで JVM スイートを守らない |
| 反証 | 2 | falsify-1 (test 軸, refuted), falsify-2 (運用軸, 部分反証→範囲修正) を evidence/ に記録済み |
| 再現性 | 2 | launchd で server/host/tick は再現稼働。ただし release ツリー破損事故 (f9138fbd 欠損) が 1 回 — immutable release でない |
| governor 統合 | 3 | tamaki Tier1 tick が fleet を 1427 repo スキャン。app 本体は dirty-skipped で未統合 |
| 運用 | 3 | falsify-2 実測: app PID 45891 / ui-host PID 90738 / maturity-tick PID 15463 いずれも exit 0 稼働中、health 200。ただし expiry-alert が last exit 1 で not running (KeepAlive=false で silent-dead)。KeepAlive 再現はコア面のみ成立 |

## OPEN 赤

- OPEN 赤-1: origin/main 先端 (172bd46) で JVM スイートがコンパイル死
  (`No such var: bot/face`, bots.clj:1907)。CI required は JVM-free emit のみで
  JVM 落ちを検知しない。修理案は evidence/2026-09-03-falsify-1 参照 (案 A/B)。
- OPEN 赤-2: expiry-alert launchd job が last exit 1 のまま not running —
  KeepAlive=false で silent-dead。修理案: plist に KeepAlive 付与
  (evidence/2026-09-03-falsify-2 参照)。

## 既知リスク

- リスク-1: release ツリーが mutable (夜間 deploy が in-place 更新し、
  f9138bd で identity/authenticators.cljc が欠損 → CLI 全滅)。不変条件:
  releases/<sha> は書き換え不可であるべき
- リスク-2: 本体 checkout が他 bot の WIP で dirty (7 ファイル) の間、
  Tier 1 着地が不可能 → テスト緑の定期保証がない
- リスク-3: journal が 4MiB bound の 30.3% (falsify-2 測定時点、前回 21%)。
  到達時 write REFUSED (仕様) — checkpoint 頻度の監視が必要

## === NEXT ===

NEXT: 再現性軸の反証 — リスク-1 の主張「releases/<sha> は書き換え可能な
mutable ツリー」を実測する: 最新 release ディレクトリのファイル blob を
対応する git commit と照合し、欠損/改変が現存するか確認する (falsify-3)。
