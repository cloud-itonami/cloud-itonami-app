# cloud-itonami-app 成熟度 (正本)

現在段階: L1 (稼働はするが、反証可能性のある品質主張が軸ごとに未整備)

測定日: 2026-09-04 (falsify-5; 初回ベースライン 2026-09-03)
測定者: itonami-maint

## 7 軸スコア (0-5)

| 軸 | score | 根拠 (測定) |
|---|---|---|
| spec/契約 | 3 | ADR 22 本 (+ ADR-2607254000 の Tier 境界)、commands.edn に 208 コマンドの schema。ただし契約→テストの機械検証リンクは一部 |
| 実装 | 3 | src 221 ファイル、全主要面 (bots/webhook/hermes-compat/store) 実装済み。virtual-shell は未活性 |
| テスト | 2 | test 205 ファイル。falsify-1 実測 (172bd46): JVM スイートはコンパイル死。falsify-5 実測 (bfd158b): コンパイル死は**再現せず** 20 ns が実際に走る — ただし launcher-test が 2 件決定論的赤 (`leftover-jvm-aliases-are-gone` は deps.edn 6a85048 の復活と矛盾)、フルスイート完走は未達 (420s 打ち切り、緑ベースライン未確立) |
| 反証 | 2 | falsify-1 (test 軸, refuted), falsify-2 (運用軸, 部分反証→範囲修正) を evidence/ に記録済み |
| 再現性 | 3 | launchd で server/host/tick は再現稼働。falsify-3 実測: releases/ 全 77 ツリーが対応 git commit と byte 完全一致 (欠損/改変 0)。ただし不変性は運用規約のみで OS 強制なし |
| governor 統合 | 3 | falsify-4 実測: tamaki Tier1 tick が fleet 1430 repo をスキャンし、dirty 解消後の本体を dirty-skip せず処理対象化。`:skipped-no-test-harness` の帰属は falsify-5 で**修正**: deps.edn の :dev alias は現行 main に存在し `clojure -M:dev:test` も解決・起動する (本体 checkout bfd158b 実測)。tick の skip 理由は未確定 (tick の実行位置 vs :local/root 相対パス解決の組合せが候補)。Tier 1 着地 0 landed は継続 (緑ベースライン未確立のため) |
| 運用 | 3 | falsify-2 実測: app PID 45891 / ui-host PID 90738 / maturity-tick PID 15463 いずれも exit 0 稼働中、health 200。ただし expiry-alert が last exit 1 で not running (KeepAlive=false で silent-dead)。KeepAlive 再現はコア面のみ成立 |

## OPEN 赤

- OPEN 赤-1: ~~origin/main 先端 (172bd46) で JVM スイートがコンパイル死~~
  → falsify-5 実測 (2026-09-04, bfd158b): コンパイル死は再現せず、スイートは
  実際に走る。CLOSED (測定による)。ただし下記 赤-3 の通り赤テスト 2 件あり。
- OPEN 赤-2: expiry-alert launchd job が last exit 1 のまま not running —
  KeepAlive=false で silent-dead。修理案: plist に KeepAlive 付与
  (evidence/2026-09-03-falsify-2 参照)。
- OPEN 赤-3: `launcher_test.clj:122 leftover-jvm-aliases-are-gone` が main
  先端 (bfd158b) で決定論的赤 — :server/:mcp 不在を要求するが deps.edn は
  6a85048 で復活済み (run-path verifier の brick 実測が根拠)。2 独立
  checkout で再現。修理案 1/2 は evidence/2026-09-04-falsify-5 参照
  (テストを現行契約に改訂 / flip 条件束縛の逆さガードに書替え)。

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
  到達時 write REFUSED (仕様) — checkpoint 頻度の監視が必要。
  → 2026-09-04 pre-run probe: 1709/4194304 (0.04%) へ激減。checkpoint/rotate
  の挙動確認を次回 tick 観察に付帯

## === NEXT ===

NEXT: falsify-5 で 2 つの帰属が変わった。(1) tick の `:skipped-no-test-harness`
の真因 — 次回 tick 実測で、tick の実行位置と :local/root 相対解決のどちらが
効いているかをログで確定する。(2) OPEN 赤-3 の launcher-test 赤を Tier 2
issue として起こす (テスト改訂案 1/2 のどちらで出すか判断材料: #270 着地
agent と 6a85048 着地 agent の契約不一致の經緯確認)。並行: `clojure -M:test`
フル完走の所要実測 (420s で 20 ns まで到達、完走時間未測定)。

