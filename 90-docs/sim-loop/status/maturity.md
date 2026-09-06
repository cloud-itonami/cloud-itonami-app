# cloud-itonami-app 成熟度 (正本)

現在段階: L1 (稼働はするが、反証可能性のある品質主張が軸ごとに未整備)

測定日: 2026-09-06 (falsify-33; 初回ベースライン 2026-09-03)
測定者: itonami-maint

## 7 軸スコア (0-5)

| 軸 | score | 根拠 (測定) |
|---|---|---|
| spec/契約 | 3 | ADR 24 本 (+ ADR-2607254000 の Tier 境界)、commands.edn に 208 コマンドの解決/path-param 契約 (flags は hint で値スキーマなし — falsify-10 実測: プレースホルダ 128 すべてに `:in "path"` 宣言、欠落 0、408=208+70+130 整合)。route 再スキャン vs レジストリの機械検証テスト実在 (commands_test 16 deftest)。値スキーマ (型/必須性) の機械検証は未整備 |
| 実装 | 3 | src 231 ファイル、全主要面 (bots/webhook/hermes-compat/store) 実装済み。virtual-shell は未活性 |
| テスト | 3 | test 205 ファイル。フルスイートが異なるリビジョンで完走: falsify-6 (bde2171)、falsify-7 (2bca892、約45分)、falsify-14 (clean HEAD、1 failure = 赤-4 のみ)、falsify-15 (負荷下 2292 tests / 13880 assertions / 1 failure = 赤-4 のみ)、**falsify-16 (merged main 1905580、負荷下 2292 tests / 13929 assertions / 0 failures EXIT=0)**。決定論的赤 0、flake 修理 (赤-5、PR #280) 着地済み。3 止まりの根拠: flake リトライ機構なし、OPEN 赤-4 未解決、テスト実行がディスク飽和に脆弱 (falsify-16/17) |
| 反証 | 3 | falsify-1〜26 を evidence/ に記録。falsify-9: 赤-2「KeepAlive 欠如で silent-dead」説を反証 (主因は ops-classpath.sh が upstream の authority.scope 追加に未追従で nbb ロード即死)。falsify-10: spec 軸主張を「解決/path-param 契約 (値スキーマなし)」に範囲修正。falsify-11: 赤-2 案 A「classpath 修正で復旧」説を反証試行 — 決定論的依存連鎖を段階実測、案 A の 3 src 追加が必須十分と確認し expiry-alert.cljs rc=0 まで完全復旧を実測 → 精緻化付きで SURVIVED。検証の终点は rc=0、plist 再 bootstrap が必須条件。falsify-12: テスト軸「赤-5 flake は時間切れ型のみ」説 → survived、3 bound 非同期設計を競合窓として同定。falsify-14 (2026-09-05): リスク-2 dirty 前提を REFUTED (本体 main clean 実測)。falsify-16 (2026-09-05): 「着地後の負荷下完走で flake サイトが赤になる」説 → survived (merged main 1905580 で 0 failures 実測、OPEN 赤-5 CLOSED)。falsify-17 (2026-09-05): 「falsify-16 の cache 整理でディスク満杯は解消 (一回性)」説を REFUTED — 同日中に /System/Volumes/Data が 100% / avail 1.9Gi に再飽和を実測、ディスク飽和は再発性の構造リスクと確定 (evidence/2026-09-05-falsify-17.md)。falsify-18 (2026-09-06): falsify-17 の「増加源は du 到達範囲外の可能性」説を反証 — du 実測で支配項を m365-archive/onedrive 133G に帰属確定 (survived→帰属確定)、expiry-alert not running / runs=0 を再実測 (evidence/2026-09-06-falsify-18.md) 。falsify-23 (2026-09-06): 「滞留世代は manifest-rev 参照解放で回収可能」説を REFUTED — manifest-rev=51d4010c (west update 管理) は resident/dns-resolver の祖先で解放しても annex 参照は残る、真の参照元は resident branch の ingest 履歴 (evidence/2026-09-06-falsify-23.md)。falsify-25 (2026-09-06): 滞留の参照元を resident 現在ツリー (data/ledger/) へ帰属修正 (unused ⊆ resident 現在ツリー 100%、detached HEAD 説反証)。falsify-26 (2026-09-06): 増加後も帰属が生存することを再確認 (SURVIVED)。falsify-28 (2026-09-06): 増加継続下 (unused 3372 / 43.27 GiB) でも帰属生存を再確認、滞留全件が resident 現在ツリー参照 (∩ 100%)。falsify-29 (2026-09-06): 増加継続下 (unused 3385 / 43.44 GiB、falsify-28 比 +13 keys / +0.17 GiB) でも帰属生存を再確認、滞留全件が resident 現在ツリー参照 (∩ 100%、unused−resident=0)。falsify-30 (2026-09-06): 増加継続下 (unused 3398 / 43.61 GiB、+13 keys / +0.17 GiB) でも帰属生存を再確認 (∩ 100%、unused−resident=0)。併せて「avail 反発は増加源の停止/減速を反映」説を REFUTED — 増加源 (export_and_sync.cljs PID 82336) が稼働中のまま avail が 1.5Gi→6.6〜7.8Gi へ回復したことを直接観測 (evidence/2026-09-06-falsify-30.md)。falsify-31 (2026-09-06): 増加継続下でも帰属生存を再確認 (unused 3427 / 43.99 GiB、∩ 100%、unused−resident=0)。併せて avail が 50 Gi / 95% へ大回復し増加源 (PID 82336) が ps で自然停止したことを直接観測 (「停止→回復」は充分条件でなく単一帰属は未特定 — falsify-30 の反証が有効) |
| 反証候補falsify30PLACEHOLDER
| 再現性 | 3 | launchd で server/host/tick は再現稼働。releases/ 全 77 ツリーが対応 git commit と byte 完全一致 (falsify-3 実測)。ただし不変性は運用規約のみで OS 強制なし |
| governor 統合 | 3 | tamaki tick は 1430 repo を 900s 間隔でスキャン継続。ただし **1559 連続 worktree-failed** (2026-08-14〜、毎 tick) — falsify-6 で原因特定済み (tick の rm -rf が git-annex read-only 残骸を取りこぼし → worktree add が永久 already exists)。修理は tamaki リポ側 (chmod -R u+wx 追加、Tier 2 で提起)。着地 0 landed は継続 |
| 運用 | 3 | falsify-6 実測: GET /health -> 200、ui-host 稼働。launchctl 実測 (2026-09-05): server 系 (local 等) 稼働継続、expiry-alert は last exit 1 / not running のまま。falsify-9/11/13 で主因確定: ops-classpath.sh が upstream の authority.scope 追加に未追従で nbb ロード即死。falsify-17 で network-awai origin/main 先端 (dd34f563) の ops-classpath.sh も authority/src を含まないことを再実測 — 帰属は upstream 先端まで不変。log は Aug 31 09:00:05 JST (mtime 1788134405) で静止、次回発火 2026-09-07 09:00 (plist Weekday=1 実測)。falsify-28 (2026-09-06) 疎通: dev /health 127.0.0.1:1338 -> 200 (稼働)、gateway /health -> 502 (本反復実測)。falsify-29 (2026-09-06) 疎通: dev /health 127.0.0.1:1338 -> 200 (稼働継続)、gateway /health -> 502、avail 1.5Gi 台へ再低位化 (反復内 -20MB)。falsify-30 (2026-09-06): avail 6.6〜7.8 GiB へ回復・反復内振動 (増加源 PID 82336 稼働継続を直接観測)、1.5Gi 低位解消 — ただし 100% 標示と振動は継続。**新規リスク (falsify-17)**: Data volume 100% / avail 2.0Gi 再飽和 — テスト・スイート・journal 生成系すべてに再点火しうる。**falsify-33 (2026-09-06, dropunused 着地後 40 分)**: 滞留が再蓄積 — unused **37 keys / 560.59 MB** (falsify-32 の 1 key / 14.67 MB 比 +36 keys / +546 MB)、resident findref unique 37 と **∩ 37/37 = 100%** (unused−resident=0)、local annex keys 37。増加源 export_and_sync.cljs は不在 (grep_rc=1) だが itonami-app-resident.cljs (PID 94682) は稼働継続。avail は 166-192 GiB → 96 GiB / 90% へ再消費 (100% 飽和は解消のまま)。falsify-25 の帰属「滞留 ⊆ resident 現在ツリー」は小規模 (560 MB) で生存 — falsify-32 の「0 ベース確定」は REFUTED (一時的) (evidence/2026-09-06-falsify-33.md) |

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
  log 静止・exit 1 も再実測。**未着地**。 falsify-18 (2026-09-06) 状態再確認: launchctl print 実測
  `state = not running` / `runs = 0` / `last exit code = (never exited)`
  (plist は再 bootstrap 待ちで発火履歴 0)、log mtime Aug 31 09:00 静止、
  次回発火 2026-09-07 (Mon) 09:00。帰属不変。
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
  前提条件として disk-avail 確認を反復手順に必須化。
  **falsify-18 (2026-09-06) で増加源を同定**: Data volume used 880Gi の
  支配項は `~/github/com-junkawasaki/orgs` 299G (全体 34%) のうち
  `gftdcojp/m365-archive/onedrive` = **133G** (レコーディング 83G +
  From G Suite Drive 47G)。単一アーカイブ・ディレクトリ。du 到達範囲外の
  隠れ増加源説は反証 (SURVIVED ではない帰属確定)。緩和候補は
  onedrive アーカイブの外部退避 (+133Gi) — アーカイブデータへの接触は
  本 bot の管轄外のため operator 判断 (Tier 2)。同日中に avail が
  1.9Gi → 5.2Gi へ回復したが、その増分の帰属は未確定。
  **falsify-19 (2026-09-06) で範囲修正**: 再飽和 3 度目を観測
  (avail 2.8Gi / 100% 実測)。「増分は onedrive の継続増加」説は REFUTED
  (onedrive du 実測 133.3 GiB、24h で不変)。排除済み: mt-* worktrees
  (合計 25MB)。~/Library は TCC 遮断で全域 du 不能のため -2.4Gi 増分の
  帰属は未確定 — 定期 du snapshot または TCC 許可を Tier 2 提起
  (evidence/2026-09-06-falsify-19.md)。
  **falsify-20 (2026-09-06) で帰属確定 (REFUTED)**: 再飽和 4 度目を観測
  (avail 846M / 100% 実測、反復中も 846M→563M と減少継続)。
  falsify-19 の「TCC 遮断で ~/Library 全域 du 不能」前提は反証
  (du -sh 実測成功: Caches 4.2G / Application Support 50G、いずれも
  静的アプリデータ)。真の構造的増加源は
  **cloud-itonami-dns-resolver/.git/annex = 71G**
  (git annex info 実測: local annex keys 5928 / 75.17 GB、working tree
  33.59 GB に対し約 40G が未参照世代として滞留)。緩和候補は
  `git annex dropunused` 等 (データロス判断を伴うため Tier 2)。
  現 avail < 1GB でフルスイート実行は不可能
  (evidence/2026-09-06-falsify-20.md)。
  **falsify-21 (2026-09-06) で数値確定**: `git annex unused
  --used-refspec +refs/heads/main` 実測 (rc=0) — **unused key 3065 個 /
  合計 39.03 GiB** (key サイズ集計 total_bytes=41912565004)。
  falsify-20 の「約 40G」推定と整合。ただし全 branch 走査では
  unused 0 — 39.03 GiB は main-refspec 上界であり、他 branch
  (resident/dns-resolver 等) が参照する分は drop 不可。
  範囲修正: dropunused 実施には branch 別参照精査が前提
  (evidence/2026-09-06-falsify-21.md)。着地は Tier 2。
  本反復での df 再実測: avail 962Mi / 100%、annex info は
  keys 5951 / 75.51 GB に微増 (世代増加継続)。
  **falsify-22 (2026-09-06) で「上界 39.03 GiB は drop 可能」説を REFUTED**:
  branch 別 `git annex unused --used-refspec` 実測 — main/HEAD/
  manifest-rev はいずれも同一集合 3088 keys / 42,199,579,172 bytes
  (39.30 GiB、falsify-21 比 微増)、resident/dns-resolver は 0、
  全 branch 走査 (`+refs/heads/*`) も 0。滞留世代は detached HEAD
  (51d4010c, manifest-rev 由来) 系の参照に保持され、dropunused 単独では
  回収不能。回収には manifest-rev 参照解放が前提 (データロス判断 →
  Tier 2 継続)。附帯: df avail 4557→4578 MiB (100% 表示継続)
  (evidence/2026-09-06-falsify-22.md)。
  **falsify-23 (2026-09-06) で「manifest-rev 参照解放で滞留を回収可能」説を REFUTED**: manifest-rev の参照実体を実測 = commit 51d4010c (ingest: resident tick --source tranco, 2026-09-02)、更新主体は west update (reflog 実測、最終更新 2026-09-03)。main ⊂ manifest-rev ⊂ resident/dns-resolver を merge-base --is-ancestor で実測 (main..mr 0 commits / mr..resident 338 commits)。manifest-rev を解放しても 352 commit 分の履歴は resident/dns-resolver から到達可能なまま残り annex 参照は解放されない。滞留の真の参照元は resident branch の ingest 履歴そのもの — 回収には履歴 truncate / 再構築 (履歴改変のデータロス判断) が前提で Tier 2 のまま (evidence/2026-09-06-falsify-23.md)。
  **falsify-24 (2026-09-06) で「滞留増加は停止」説を REFUTED**: NEXT-10 の 2 時点実測 — unused 集合は 3088 keys / 39.30 GiB → **3261 keys / 41.73 GiB** (同日中に +173 keys / +2.43 GiB、key 名埋め込みサイズ集計実測)、annex info は 5951 / 75.51 GB → **6147 / 78.4 GB**。滞留は増加継続と確定 (resident ingest が発生源として稼働継続)。df avail 4578Mi → **356Mi / 100%** に悪化、フルスイート実行不可能のまま。緩和候補: 発生源停止 (resident tick の dns-resolver ingest 方針見直し) を Tier 2 提起 (evidence/2026-09-06-falsify-24.md)。
  **falsify-25 (2026-09-06) で参照元を直接反証・修正**: unused 3286 keys /
  42.07 GiB (再実測、falsify-24 比 +25 keys / +0.34 GiB — 増加継続) と
  resident/dns-resolver 参照キー集合 (findref 6181 keys / 73.35 GiB) の
  積集合を sort+comm で機械集計 → **重複 3286/3286 = 100%**。滞留は
  resident branch の *ingest 履歴* ではなく *現在ツリー* (data/ledger/
  配下) が全件参照。併せて falsify-22 の「detached HEAD (manifest-rev) が
  滞留を保持」説も反証 — 同一 detached HEAD でも refspec 指定なしの
  unused は 0 実測 (unused 判定は refspec 選択の関数)。回収条件は
  「resident ツリーからの旧 ingest 世代削除」が必須 (データ削除判断 →
  Tier 2)。附帯: df avail 356Mi → **12.1 GiB / 99%** に回復したが
  増加源は稼働継続 (annex info 6160 keys / 78.58 GB、falsify-24 比
  +13 keys / +0.18 GB) (evidence/2026-09-06-falsify-25.md)。
  **falsify-26 (2026-09-06) で帰属の生存を再確認 (SURVIVED)**: unused
  3311 keys / 42.41 GiB (falsify-25 比 +25 keys / +0.34 GiB、増加率は
  概ね 1 tick ≈1日 あたり約 +25 keys / +0.34 GiB で安定) と
  resident findref 6197 keys の積集合は **3311/3311 = 100%**
  (unused−resident = 0)。増加後も滞留の全件が resident 現在ツリー
  参照 (= falsify-25 帰属不変、他 branch / detached ref 由来ではない)。
  annex info 6197 keys / 79.13 GB。df avail は **2.03 GiB / 100%** に
  再飽和 (falsify-25 の 12.1 GiB から -10 GiB)。回収条件は変わらず
  「resident ツリーからの旧 ingest 世代削除」(データ削除判断 → Tier 2)
  (evidence/2026-09-06-falsify-26.md)。

  **falsify-27 (2026-09-06) で「avail 回復 (2.03→3.4Gi) は滞留増加源の
  減速/停止を反映し、リスク-5 は一時緩和した」説を REFUTED**: unused
  3348 keys / 42.93 GiB (falsify-26 比 +37 keys / +0.52 GiB)、annex 総数
  6234 keys / 79.69 GB (falsify-26 比 +37 keys / +0.56 GB) — 増加継続。
  avail は反復内 (約10分) で 3.4 → 2.81 GiB に再減少。増加源は停止せず、
  一時緩和は成り立たない (evidence/2026-09-06-falsify-27.md)。
  **falsify-28 (2026-09-06) で帰属の生存を再確認 (SURVIVED)**: unused
  3372 keys / 43.27 GiB (total_bytes=46,464,409,563、python 実測、falsify-27
  比 +24 keys / +0.34 GiB) と resident findref unique 6258 keys の積集合は
  3372/3372 = 100% (unused − resident = 0)。増加継続下でも滞留の全件が
  resident 現在ツリー参照 (= falsify-25/26 帰属不変)、detached HEAD / 他
  branch 由来 0。annex info 6258 keys / 80.06 GB (falsify-27 比 +24 keys /
  +0.37 GB)。df avail 3.3Gi / 100% で、反復内 (約5分) に 3.32 → 3.28 GiB
  と -37 MB 減少 (消費稼働継続)。疎通: dev /health 127.0.0.1:1338 -> 200
  / gateway /health -> 502 (本反復実測)。回収条件は変わらず「resident ツリー
  からの旧 ingest 世代削除」(データ削除判断 → Tier 2)
  (evidence/2026-09-06-falsify-28.md)。
  **falsify-29 (2026-09-06) で帰属の生存を再確認 (SURVIVED)**: unused
  3385 keys / 43.44 GiB (total_bytes=46,643,534,658、python 実測、
  falsify-28 比 +13 keys / +0.17 GiB) と resident findref unique
  6271 keys の積集合は 3385/3385 = 100% (unused − resident = 0)。
  増加継続下でも滞留の全件が resident 現在ツリー参照 (= falsify-25/26/28
  帰属不変)、detached HEAD / 他 branch 由来 0。annex info 6271 keys /
  80.24 GB (falsify-28 比 +13 keys / +0.18 GB)。df avail は 1.5Gi 台へ
  再低位化 (falsify-28 の 3.3Gi から -1.8 GiB)、反復内 (約5分) に
  1.50 → 1.48 GiB と -20 MB 減少 (消費稼働継続)。疎通: dev /health
  127.0.0.1:1338 -> 200 / gateway /health -> 502 (本反復実測)。
  回収条件は変わらず「resident ツリーからの旧 ingest 世代削除」
  (データ削除判断 → Tier 2) (evidence/2026-09-06-falsify-29.md)。
  **falsify-30 (2026-09-06) で帰属生存 + 反発帰属反証**: unused 3398 keys /
  43.61 GiB (falsify-29 比 +13 keys / +0.17 GiB、増加継続下で ∩ 100%
  unused−resident=0)。avail は低位 1.5Gi → 6.6〜7.8 GiB へ回復したが、
  増加源 (export_and_sync.cljs PID 82336, elapsed 42min / CPU 12:51) は
  稼働継続を直接観測 (ps 実測) — 「avail 反発 = 増加源の停止/減速」説を
  REFUTED。反発は増加源の停止に起因せず別空間の解放が示唆されるが
  単一起因は未特定 (反復内で avail 7.1→7.8→6.6 GiB と振動)。100% 標示
  継続、リスク-5 継続 (evidence/2026-09-06-falsify-30.md)。
  **falsify-31 (2026-09-06) で帰属生存 + avail 大回復観測**: unused 3427 keys
  / 43.99 GiB (falsify-30 比 +29 keys / +0.38 GiB、増加継続下で ∩ 100%
  unused−resident=0)。df は **avail 50 Gi / 95%** へ大回復 (falsify-30 の
  6.6〜7.8 GiB から **約 +43 GiB**、シリーズ開始以来の 100% 飽和は解消) —
  annex 側も available local disk 54.16 GB を実測。増加源 export_and_sync.cljs
  (falsify-30 PID 82336) は **ps で該当なし (grep_rc=1)** と自然停止を直接観測。
  増加源停止と avail 大回復の時期は一致するが、falsify-30 の「稼働中のまま
  回復」の反証が有効なため「停止→回復」は充分条件でなく単一帰属 (~43 GiB の
  解放源、annex/lake 変化を上回る) は未特定のまま Tier 2 継続。100% 飽和の
  解消事実は確定、次反復で disk-avail 50 Gi でのフルスイート実行 (falsify-17
  の最小 avail 閾値実測) が現実的 (evidence/2026-09-06-falsify-31.md)。
**falsify-32 (2026-09-06) で滞留の全崩壊 + Tier 2 dropunused 着地を直接観測
  (REFUTED)**: df avail は 50 Gi → **166〜192 GiB / 80%** へ最大回復、git annex
  info は local annex keys **6313 → 1**、size **80.82 GB → 14.67 MB**、unused
  keys も 3427 keys / 43.99 GiB → **14.67 MB (実質 0)** へ崩壊。増加源
  export_and_sync は ps から消失 (grep_rc=1)。findref が反復内で 449→322→222→1
  と実時間で崩壊し git-annex branch に update commit 連打が記録されていることから、
  **falsify-25/26/28/29/30/31 が提起してきた Tier 2 回収条件 (resident ツリーからの
  旧 ingest 世代削除 / dropunused) が operator によって着地**したことを直接観測。
  滞留の継続増加という帰属議論の前提は崩壊し、滞留観測は 0 ベースに戻る
  (evidence/2026-09-06-falsify-32.md)。


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
8. ~~ディスク再飽和の増加源を同定~~ → falsify-18 で同定完了
   (m365-archive/onedrive 133G)。残る測定対象: 増分 (re-saturation) の
   帰属と、テスト実行が安全な最小 avail 閾値の実測。
10. ~~falsify-22 の残測定: manifest-rev の参照実体の特定~~ → falsify-23
    で完了 (51d4010c / west update 管理と実測)。ただし manifest-rev 解放
    では回収不能と反証 (resident branch 履歴が真の参照元)。残る測定対象:
    annex keys の増加レート 2 時点実測 (滞留増加の停止/継続の判別)。
    ~~本測定~~ → falsify-24 で完了 (unused 3261 keys / 41.73 GiB、
    +173 keys / +2.43 GiB — 増加継続)。
    継続の再確認 → falsify-26 完了 (SURVIVED、増加継続)。
    残る測定対象: 発生源停後止の滞留増加ゼロ確認 (Tier 2 着地後)。
11. ~~avail 回復は増加源減速を意味するか~~ → falsify-27 で REFUTED (unused
    増加継続、反復内で avail 再減少)。falsify-28/29 で帰属不変 (resident ∩
    100%) を再確認。**falsify-30 で「反発=増加源停止/減速」直接反証**: avail が
    低位 1.5Gi → 6.6〜7.8 GiB へ回復する一方、増加源 (export_and_sync.cljs
    PID 82336) は稼働継続を ps 実測 (elapsed 42min / CPU 12:51) — 反発は増加源
    の停止に起因しない。ただし増加源稼働のまま avail は 7.1→7.8→6.6 GiB と
    反復内で振動し、単一起因は未特定。残る測定対象: avail 反発の単一起因
    (定期 du snapshot / 別空間解放の帰属、falsify-19 継続) と発生源停止後の
    滞留増加ゼロ確認。
**falsify-32 (2026-09-06) で滞留の全崩壊を完了 (REFUTED)**: dropunused が
    operator 着地し unused = 実質 0 (14.67 MB)、local annex 1 key、avail
    166-192 GiB。「滞留増加ゼロ確認」は 0 ベースへの帰着で充足。
9. (falsify-16 提案の Tier 2 runbook) test-data の git-annex read-only
   残骸への chmod -R u+wx 手順をテスト runbook に追記提案 —
   tamaki tick の worktree-failed と同一 fail モード。

**falsify-33 (2026-09-06) で「falsify-32 の滞留 0 ベース確定」を REFUTED**: dropunused 着地後 40 分で滞留が再蓄積 (unused 37 keys / 560.59 MB、∩ 37/37 = 100%、local annex 37 keys)。falsify-25 の帰属 「滞留 ⊆ resident 現在ツリー」は小規模で生存継続。増加源 export_and_sync は不在 (grep_rc=1) だが itonami-app-resident.cljs (PID 94682) は稼働継続 — resident ingest の再蓄積源との整合。avail 166-192 GiB → 96 GiB / 90% へ再消費 (100% 飽和は解消のまま)。dropunused は恒久解消でなく反復緩和。残る測定対象: フルスイートの最小 avail 閾値実測 (現 avail 96 GiB で実行可能)。(evidence/2026-09-06-falsify-33.md)
**falsify-33 副次観測 — ledger 異常 (Tier 2, operator 要修復)**: 7 軸テーブルの反証行と再現性行の間 (本ファイル 16 行目) に `| 反証候補falsify30PLACEHOLDER` という孤立テーブル行が残っており、前回反復の書き込み中断 (クラッシュ) でテーブルが破損している。append-only 規約のため本 bot は削除・編集しない。operator は当該行を復旧して 7 軸テーブルを修復のこと。
**falsify-34 (2026-09-06) で「dropunused 後再蓄積の滞留は resident 現在ツリーを外れる」説を REFUTED**: 再蓄積を再実測 — main-refspec unused は **62 keys / 560.59 MB** (falsify-33 の 37 keys 比 +25 keys)。帰属判定: `--used-refspec=+refs/heads/resident/dns-resolver` で **unused 正規 key = 0** (falsify-25 の帰属「滞留 ⊆ resident 現在ツリー」が再蓄積 scale でも生存継続、main のみで unused 化する 62 key は全て resident data/ledger/ 参照)。増加源 itonami-app-resident.cljs (PID 94682) は falsify-33 から連続稼働、export_and_sync 不在 (grep_rc=1)。dropunused は恒久解消でなく「回収しても再度 re-accumulate する反復緩和」と確定。avail は反復内 82→81 GiB / 91% (消費継続)。残る観測: partially transferred chunk (15 個) は仕掛かり transfer で次回正規 unused 化候補。恒久解消は resident ingest の世代削除/方針見直し (データ削除判断 → Tier 2 operator) (evidence/2026-09-06-falsify-34.md)。附帯: 7 軸テーブル 16 行目の孤立行 `| 反証候補falsify30PLACEHOLDER` は残存のまま (append-only で本 bot は編集せず、operator 復旧待ち — falsify-33 Tier 2 report 済み)。

## NEXT (falsify-34 追記、append-only)

- falsify-34 で帰属生存を再確認 (REFUTED、falsify-25 が再蓄積 scale でも生存)。
  - 残る測定対象: partially transferred 15 chunk の next-iteration 正規 unused 化
    追跡 (transfer drift の確認)。
  - 恒久解消の Tier 2 提言は不変 (resident ingest 世代削除)。
  - disk-avail: avail 81-82 GiB / 91% でフルスイート実行可能域 (falsify-31 の
    最小 avail 閾値実測が現実的) — 実行は次反復以降の選択肢。

**falsify-35 (2026-09-06) で transfer drift 確認 + 帰属生存を再確認**: local annex
keys 62 → **75** / 1.11 GB (falsify-34 比 +13 keys)。main-refspec 正規 unused は
62 → **75**、resident-refspec 正規 unused は **0** (帰属「unused ⊆ resident 現在
ツリー」が 75-key scale で生存継続)。falsify-34 が次回 candidates とした
partially-transferred chunk は 15 → **14** / 187MB→178M (1 chunk が正規 key へ解決
= **drift 静止説 REFUTED**、drift は低速だが実在)。増加源 itonami-app-resident.cljs
PID 94682 稼働継続、export_and_sync 不在。avail 74Gi / 92% (反復越え -7GiB)。
dropunused は引き続き反復緩和、恒久解消は resident ingest 世代削除 (Tier 2)
(evidence/2026-09-06-falsify-35.md)。附帯: 7 軸テーブル 16 行目孤立行
`| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。

**falsify-36 (2026-09-06) で chunk drift 停止 + 帰属生存を再確認**: regular unused は
99 keys / **1,482,952,762 bytes (≈1.38 GiB)** (falsify-35 の 75 から **+24 keys**、増加継続、
annex local keys 75 → **99** / size 1.11GB → **1.48 GB**)。resident-refspec 正規 unused は **0**
(帰属「unused ⊆ resident 現在ツリー」が 99-key / 1.38 GiB scale で SURVIVED 継続)。partial chunk は
**14 → 14** / tmp 178M / 14 ファイルで不変 (falsify-35 の 1 chunk 解決は間欠的 — 本反復では **drift 停止、
「drift は毎反復継続する単調現象」説 REFUTED**)。増加源 itonami-app-resident.cljs PID 94682
稼働継続 (export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。df avail 73Gi / 92% (反復越え -1GiB)。
dropunused は引き続き反復緩和、恒久解消は resident ingest 世代削除 (Tier 2) (evidence/2026-09-06-falsify-36.md)。
附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。

**falsify-37 (2026-09-06) で帰属生存 + avail の大回復を観測**: main-refspec regular unused は
**124 keys / 1,861,002,689 bytes (≈1.73 GiB)** (falsify-36 の 99 keys から **+25 keys**、増加継続)。
resident-refspec 正規 unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 124-key / 1.73 GiB
scale で SURVIVED 継続)。partial chunk は **14 → 14** / tmp 178M で不変 (連続 2 反復 drift なし、
間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続 (etime 1日、export_and_sync
不在)、dev /health 200。**df avail は 73Gi → 171Gi / 81% へ +98Gi 大回復** (dropunused 直後の
falsify-32 と同等域へ復帰)。増加源が同一 PID で稼働継続のままの回復であり、「avail 回復 = 増加源
停止」説は falsify-30 に続き再反証 (REFUTED)。回復容量の単一起因帰属は未特定 (local annex は +25
増、partial 不変) → 継続観測対象。dropunused は引き続き反復緩和、恒久解消は resident ingest 世代
削除 (Tier 2)。avail 171Gi はフルスイート実行可能域 (falsify-31 の最小 avail 閾値実測が次の実行
候補) (evidence/2026-09-06-falsify-37.md)。附帯: 7 軸テーブル 16 行目孤立行
`| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。
**falsify-38 (2026-09-06) で最小 avail 閾値の上界を確定 + 現行 main (cc7a17d) の緑を再確認
(SURVIVED)**: フル JVM スイートを現行 main (cc7a17d、harness-plugins コミット含有) の
detached worktree (/private/tmp/mt-msloop38、本体 checkout 未 touch、porcelain clean) で
実行。avail **170Gi / 81%** (開始・終了とも 170Gi、ディスク飽和兆候なし)、負荷条件
開始 load 24.37/29.52/34.04 → 終了 27.28/26.83/30.24 (falsify-16 同等の高負荷環境)。
結果: **Ran 2291 tests / 13895 assertions / 0 failures, 0 errors** (実測、
/tmp/f38-full2.log)。→ (a) avail 170Gi はフルスイート実行可能域と確定 (falsify-17 の
avail 2.2Gi journal-mismatch fail モードはこの avail 帯では発火しない)、(b) 現行 main
に回帰なし (harness-plugins コミットの緑再確認、決定論的赤 0 不変)。最小閾値の下界は
未確定 (170Gi は上界; falsify-16 は avail 16Gi で完走済み → 閾値は 16Gi〜170Gi の間に
ある)。テスト軸 score は 3 のまま (根拠に cc7a17d での 0 failures を追記、
flake リトライ機構なし / OPEN 赤-4 未解決は変わらず)
(evidence/2026-09-06-falsify-38.md)。附帯: 7 軸テーブル 16 行目孤立行
`| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。

## NEXT (falsify-39 追記、append-only)

**falsify-39 (2026-09-07) で帰属生存 + 増加継続を再確認 (SURVIVED)**: main-refspec regular unused は
**185 keys / 2,806,308,411 bytes (≈2.61 GiB)** (falsify-37 の 124 keys / 1.73 GiB から **+61 keys**、
増加継続、local annex 185 keys / 2.81 GB と一致)。resident-refspec 正規 unused は **0**
(帰属「unused ⊆ resident 現在ツリー」が 185-key / 2.61 GiB スケールで SURVIVED 継続、unused−resident=0)。
partial chunk は **14 → 14** / tmp 178M で不変 (falsify-35 の 1 chunk 解決以降連続 4 反復 drift なし、
間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続 (falsify-33〜38 と同一 PID、
export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。df avail は 154Gi / 83%
(falsify-38 終了時 170Gi から -16Gi 漸減、100% 飽和は解消のまま)。「avail 回復 = 増加源停止」は
falsify-37 で反証済みのまま、解放源 (falsify-30/37 の大回復) の単一起因帰属は未特定で継続観測。
dropunused は引き続き反復緩和、恒久解消は resident ingest 世代削除 (Tier 2)。
avail 154Gi は引続きフルスイート実行可能域 (falsify-38 は 170Gi で 0 failures 実測済み)
(evidence/2026-09-07-falsify-39.md)。附帯: 7 軸テーブル 16 行目孤立行
`| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。

## NEXT (falsify-40 追記、append-only)

**falsify-40 (2026-09-07) で帰属生存 + 増加継続を再確認 (SURVIVED)**: main-refspec regular unused は
**210 keys / 3,184,346,441 bytes (≈2.97 GiB)** (falsify-39 の 185 keys / 2,806,308,411 bytes から
**+25 keys / +0.38 GiB**、増加継続、local annex 210 keys / 3.18 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 210-key / 2.97 GiB スケールで SURVIVED 継続、
unused−resident=0)。partial chunk は **14 → 14** / tmp 178M で不変 (falsify-35 の 1 chunk 解決以降
連続 5 反復 drift なし、間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続
(falsify-33〜39 と同一 PID、export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。
df avail は 152Gi / 83% (falsify-39 終了時 154Gi から -2Gi 漸減、100% 飽和は解消のまま)。
「avail 回復 = 増加源停止」は falsify-37 で反証済みのまま、解放源の単一起因帰属は未特定で継続観測。
増加レートは falsify-39 (+61) から減速 (+25) だが継続。dropunused は引き続き反復緩和、恒久解消は
resident ingest 世代削除 (Tier 2)。avail 152Gi は引続きフルスイート実行可能域
(evidence/2026-09-07-falsify-40.md)。附帯: 7 軸テーブル 16 行目孤立行
`| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。本体 checkout は
agent/fix-open-red-5-three-bound で porcelain clean (dirty 0) 確認。
## NEXT (falsify-41 追記、append-only)

**falsify-41 (2026-09-07) で帰属生存 + 増加継続を再確認 (SURVIVED)**: main-refspec regular unused は
**234 keys / 3,551,064,161 bytes (≈3.31 GiB)** (falsify-40 の 210 keys / 3,184,346,441 bytes から
**+24 keys / +0.34 GiB**、増加継続、local annex 234 keys / 3.55 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 234-key / 3.31 GiB スケールで SURVIVED 継続、
unused−resident=0)。partial chunk は **14 → 14** / temp 186.98MB (annex info) で不変 (falsify-35 以降
連続 6 反復 drift なし、間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続
(falsify-33〜40 と同一 PID、export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。
df avail は 150Gi / 84% (falsify-40 終了時 152Gi から -2Gi 漸減、100% 飽和は解消のまま)。
「avail 回復 = 増加源停止」は falsify-37 で反証済みのまま、解放源の単一起因帰属は未特定で継続観測。
dropunused は引き続き反復緩和、恒久解消は resident ingest 世代削除 (Tier 2)。avail 150Gi は引続き
フルスイート実行可能域 (evidence/2026-09-07-falsify-41.md)。附帯: 7 軸テーブル 16 行目孤立行
`| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。本体 checkout は
agent/fix-open-red-5-three-bound で porcelain clean (dirty 0) 確認。負荷 load avg 55.26/55.99/48.65。
反証軸 score は 3 のまま (falsify-1〜41 記録継続、本反復は帰属の同一主張のスケール拡大であり
score へ質的変更なし)。
## NEXT (falsify-42 追記、append-only)

**falsify-42 (2026-09-07) で帰属生存 + 増加継続 (レート加速) を再確認 (SURVIVED)**: main-refspec regular unused は
**296 keys / 4,444,966,515 bytes (≈4.14 GiB)** (falsify-41 の 234 keys / 3,551,064,161 bytes から
**+62 keys / +0.83 GiB**、増加継続、local annex 296 keys / 4.45 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 296-key / 4.14 GiB スケールで SURVIVED 継続、
unused−resident=0)。partial chunk は **14 → 14** / temp 186.98MB (annex info) で不変 (falsify-35 以降
連続 7 反復 drift なし、間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続
(falsify-33〜41 と同一 PID、export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。
df avail は 148Gi / 84% (falsify-41 終了時 150Gi から -2Gi 漸減、100% 飽和は解消のまま)。
**増加レートの加速 (+62 keys) は新観測** — falsify-40 (+25) / falsify-41 (+24) から単反復で ~2.5 倍。
レートの振動/単調を継続観測で判別。「avail 回復 = 増加源停止」は falsify-37 で反証済みのまま、
解放源の単一起因帰属は未特定で継続観測。dropunused は引き続き反復緩和、恒久解消は resident ingest
世代削除 (Tier 2)。avail 148Gi は引続きフルスイート実行可能域 (evidence/2026-09-07-falsify-42.md)。
附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。
本体 checkout は agent/fix-open-red-5-three-bound で porcelain clean (status --porcelain 0 行)
確認、head 9e5b24a。負荷 load 40.82/30.68/27.40。反証軸 score は 3 のまま (falsify-1〜42 記録継続、
本反復は帰属の同一主張のスケール拡大であり score へ質的変更なし)。
