# cloud-itonami-app 成熟度 (正本)

現在段階: L1 (稼働はするが、反証可能性のある品質主張が軸ごとに未整備)

測定日: 2026-09-05 (falsify-12; 初回ベースライン 2026-09-03)
測定者: itonami-maint

## 7 軸スコア (0-5)

| 軸 | score | 根拠 (測定) |
|---|---|---|
| spec/契約 | 3 | ADR 24 本 (+ ADR-2607254000 の Tier 境界)、commands.edn に 208 コマンドの解決/path-param 契約 (flags は hint で値スキーマなし — falsify-10 実測: プレースホルダ 128 すべてに `:in "path"` 宣言、欠落 0、408=208+70+130 整合)。route 再スキャン vs レジストリの機械検証テスト実在 (commands_test 16 deftest)。値スキーマ (型/必須性) の機械検証は未整備 |
| 実装 | 3 | src 231 ファイル、全主要面 (bots/webhook/hermes-compat/store) 実装済み。virtual-shell は未活性 |
| テスト | 3 | test 205 ファイル。フルスイートが異なるリビジョンで 2 回完走: falsify-6 (bde2171) と falsify-7 (2bca892、所要約45分)。falsify-7: Ran 2291 tests / 13862 assertions / **1 failure** 0 errors。bots_test.clj:1566 は再実行で緑 → 決定論的赤ではなく高負荷 flake と確定 (REFUTED)。決定論的赤 0。残る 1 failure は launcher_test.clj:162 (OPEN 赤-4、launcher 解決順序の実バグ — falsify-8 で帰属修正済み)。3 止まりの根拠: flake リトライ機構なし、OPEN 赤-4 未解決。falsify-12 (2026-09-05, 静的解析): 赤-5 flake は時間切れ型のみで hang/deadlock 構造なし — survived。entered(2000ms)/poll(2.5s)/release(3000ms) の 3 bound が非同期に設計され最小の entered で律速することを同定 (evidence/2026-09-05-falsify-12.md) |
| 反証 | 3 | falsify-1〜12 を evidence/ に記録。falsify-9: 赤-2「KeepAlive 欠如で silent-dead」説を反証 (主因は ops-classpath.sh が upstream の authority.scope 追加に未追従で nbb ロード即死)。falsify-10: spec 軸主張を「解決/path-param 契約 (値スキーマなし)」に範囲修正。falsify-11: 赤-2 案 A「classpath 修正で復旧」説を反証試行 — authority→sha2.core→datom.source の決定論的依存連鎖が段階的に死ぬことを実測、案 A の 3 src 追加が必須十分と確認し expiry-alert.cljs rc=0 (OK 行) まで完全復旧を実測 → 精緻化付きで SURVIVED。検証の终点は :LOAD-OK でなく rc=0、plist 再 bootstrap が必須条件。falsify-12: テスト軸「赤-5 flake は時間切れ型のみで hang 構造なし」説を反証試行 → survived (hang/deadlock 構造なし、falsify-7 の帰属は修正不要)。entered/poll/release の 3 bound 非同期設計を競合窓として同定し、OPEN 赤-5 修理案に範囲修正 |
| 再現性 | 3 | launchd で server/host/tick は再現稼働。releases/ 全 77 ツリーが対応 git commit と byte 完全一致 (falsify-3 実測)。ただし不変性は運用規約のみで OS 強制なし |
| governor 統合 | 3 | tamaki tick は 1430 repo を 900s 間隔でスキャン継続。ただし **1559 連続 worktree-failed** (2026-08-14〜、毎 tick) — falsify-6 で原因特定済み (tick の rm -rf が git-annex read-only 残骸を取りこぼし → worktree add が永久 already exists)。修理は tamaki リポ側 (chmod -R u+wx 追加、Tier 2 で提起)。着地 0 landed は継続 |
| 運用 | 3 | falsify-6 実測: GET /health -> 200 (PID 2666)、ui-host 稼働。expiry-alert は not running/exit 1 — falsify-9 で主因確定: ops-classpath.sh が upstream (org-chainagnostic-cacao 83f3169, 2026-08-15) の authority.scope 追加に未追従で nbb ロード即死。鍵は vault に存在 (kagi ls 実測) ので案 A 修正で復旧見込み。KeepAlive 無しは従属リスク |

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
  (evidence/2026-09-04-falsify-9.md / -11.md 参照)。**未着地**。
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
- OPEN 赤-5: bots_test.clj:1566 durable-goal 並行 deref が高負荷環境で
  flake (deref entered 2000ms が load 85 で尽きる)。falsify-7 で低負荷時は緑。
  修理案: entered の timeout を 2000→5000ms に引き上げ (test/ への変更、
  本体 checkout dirty のため着地せず Tier 2 で記録)。
  **falsify-12 (2026-09-05) で範囲修正**: entered(2000ms)/poll(2.5s)/
  release(3000ms) の 3 bound が非同期に設計されており、許容負荷は最小の
  entered で律速される。timeout 引き上げのみでは poll と release の整合が
  未検証のまま残るため、修理は 3 点セットの整合確認で行うこと
  (evidence/2026-09-05-falsify-12.md)。hang/deadlock 構造なし — survived。

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
2. ~~OPEN 赤-4 の帰属確定 (環境依存か実バグか)~~ → falsify-8 で REFUTED
   (実バグ確定、修理案 A/B 付きで Tier 2 report 完了)。
3. tick 側修理案 (chmod -R u+wx / git-annex 初期化回避) を tamaki リポに
   Tier 2 report として提起 (未着手のまま)。
4. OPEN 赤-2 (expiry-alert) の修理: 案 A = network-awai/cloud-itonami の
   scripts/ops-classpath.sh 修正 (+ 案 B = plist KeepAlive) は
   kanban/human 判断待ち (Tier 2)。falsify-9 で :LOAD-OK、falsify-11 で expiry-alert.cljs rc=0 (OK 行、3 chain fresh) まで裏取り済み。
   2026-09-04 20:47 再確認: 同一エラー (`Could not find namespace:
   authority.scope`) で log が更新継続中 — 未修理のまま。
5. OPEN 赤-4 (launcher shell-dir 優先順位) の修理案 A/B は kanban/human
   判断待ち。OPEN 赤-5 (flake timeout 引き上げ案) も同様。
6. ~~spec 軸「208 コマンドの schema」説の反証~~ → falsify-10 で survived
   (path-param 契約は完全、値スキーマは未整備 — 主張の範囲を修正済み)。
7. falsify-11 で OPEN 赤-2 案 A 説「classpath 修正で復旧」を rc=0 まで
   完全検証 (evidence/2026-09-04-falsify-11.md)。着地時の追加条件は
   plist 再生成 + launchctl bootout/bootstrap と、kagi get の所要
   (~4 分/実行) を見込んだ手動発火確認。
8. 実行環境が取れた反復で bots_test.clj:1566 を高負荷再現条件下で
   再実行し、falsify-12 測定 3 の競合窓 (entered+poll vs release 3000ms)
   の実際の発火を確認する。
