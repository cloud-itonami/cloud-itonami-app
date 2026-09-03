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
| 再現性 | 3 | launchd で server/host/tick は再現稼働。falsify-3 実測: releases/ 全 77 ツリーが対応 git commit と byte 完全一致 (欠損/改変 0)。ただし不変性は運用規約のみで OS 強制なし |
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

- リスク-1 (falsify-3 で修正): releases/<sha> の「現存する欠損/改変」は refuted
  (77/77 ツリーが git と完全一致、2026-09-03 実測)。残るリスクは、不変性が
  sha 名 + symlink 規約のみで OS 強制 (uchg / chmod -w / hash 検証) されていない
  こと。また「f9138bd で identity/authenticators.cljc が欠損」の事故記述は
  本 repo の git 履歴にそのパスが一度も存在せず裏付け不能 — 帰属先の再特定が必要
  (evidence/2026-09-03-falsify-3 参照)
- リスク-2: 本体 checkout が他 bot の WIP で dirty (7 ファイル) の間、
  Tier 1 着地が不可能 → テスト緑の定期保証がない
- リスク-3: journal が 4MiB bound の 52.3% (2026-09-03 実測 2195004/4194304 bytes、
  falsify-2 測定時は 30.3%、前々回 21%)。
  到達時 write REFUSED (仕様) — checkpoint 頻度の監視が必要

## === NEXT ===

NEXT: governor 統合軸 — 本体 checkout の dirty が解消 (dirty_files=0, HEAD
6a85048) したことで、tamaki Tier1 tick が app 本体を dirty-skip せず処理できる
はず。次回 tick の実ログを確認し、「cloud-itonami-app が dirty-skipped される」
主張を falsify-4 として実測する。
