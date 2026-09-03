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
| 反証 | 1 | 本 ledger 設立前。falsify 記録なし (これから) |
| 再現性 | 2 | launchd で server/host/tick は再現稼働。ただし release ツリー破損事故 (f9138fbd 欠損) が 1 回 — immutable release でない |
| governor 統合 | 3 | tamaki Tier1 tick が fleet を 1427 repo スキャン。app 本体は dirty-skipped で未統合 |
| 運用 | 3 | server/UI-host/tick の launchd KeepAlive、cron 5 本、gateway.itonami.cloud ingress 疎通済み。journal 21% of bound |

## OPEN 赤

(なし — 初回ベースライン。以下は既知の未検証リスク)

- リスク-1: release ツリーが mutable (夜間 deploy が in-place 更新し、
  f9138bd で identity/authenticators.cljc が欠損 → CLI 全滅)。不変条件:
  releases/<sha> は書き換え不可であるべき
- リスク-2: 本体 checkout が他 bot の WIP で dirty (7 ファイル) の間、
  Tier 1 着地が不可能 → テスト緑の定期保証がない
- リスク-3: journal が 4MiB bound の 21%。到達時 write REFUSED (仕様) —
  checkpoint 頻度の監視が必要

## === NEXT ===

NEXT: リスク-2 の反証 — 本 worktree (bot/maturity-sim-loop) でフルテスト
スイートを実行し、origin/main 時点の緑/赤を記録する (falsify-1)。
赤なら最初の失敗テスト 1 件を最小 repro として OPEN 赤 に上げる。
