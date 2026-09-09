# cloud-itonami-app 成熟度 (正本)

現在段階: L1 (稼働はするが、反証可能性のある品質主張が軸ごとに未整備)

測定日: 2026-09-06 (falsify-33; 初回ベースライン 2026-09-03)
測定者: itonami-maint

## 7 軸スコア (0-5)

| 軸 | score | 根拠 (測定) |
|---|---|---|
| spec/契約 | 3 | ADR 24 本 (+ ADR-2607254000 の Tier 境界)、commands.edn に 208 コマンドの解決/path-param 契約 (flags は hint で値スキーマなし — falsify-10 実測: プレースホルダ 128 すべてに `:in "path"` 宣言、欠落 0、408=208+70+130 整合)。route 再スキャン vs レジストリの機械検証テスト実在 (commands_test 16 deftest)。値スキーマ (型/必須性) の機械検証は生成 registry の flags では未整備のまま (falsify-56: 123/123 plain string、値スキーマ flag 0) だが、別名レジストリ cli-aliases.edn は `:flag`/`:required?`/`:parse`/`:default` の値スキーマを持ち cli_aliases_test の `body-specs-use-known-vocabulary` 等で機械検証済み (falsify-56 で「未整備」の全面主張を REFUTED、resolver は alias 優先 — commands.cljc:334-349))。**falsify-71 (tip 679572b)**: commands.edn 208→209 (単一 params なし GET 追加 `workspace resources`、migration 変化は :counts +1 のみ、cli-aliases.edn byte 一致)、placeholder 128=128 欠落 0 で整合不変量は migration 後も生存 (falsify-47/56 の再アンカー) |
| 実装 | 3 | src 244 ファイル (head d226614、falsify-84 実測、falsify-82/83 の c792872 と同値)、全主要面 (bots/webhook/hermes-compat/store) 実装済み。virtual-shell は per-bot opt-in (デフォルト off) の完全実装の能力で、ライブディスパッチ (bots.clj:2511,3012) / write ゲート (bots.clj:2671) / describe / テスト 5 deftest 揃い、west-refactor 移行の必須前提 (cli.clj:670 `virtual-shell-ready?`) に利用 — falsify-57 で「未活性」の blanket 記述は REFUTED。本ホストは docker ABSENT のため実行時 available? は false (コード活性・実行層は本ホスト不可)。**falsify-75 (本日) で新規 build-ブレークを確定 (falsify-63..68 と独立)**: transport 移行 commit 679572b の http_client.clj:16 が kotoba.net.jvm-host を require するが、tip deps.edn に kotoba-net の git dep / :local/root / :paths が一切未宣言 (grep 実測 rc=1)。isolated worktree で updater-test 単一 ns を最小 require (-M:utonly、:test alias の test-runner main-opts 不使用) → `Could not locate kotoba/net/jvm_host` FileNotFoundException EXIT_RC=1 (identity.clj 未 touch で到達)。sibling kotoba-lang/kotoba-net/src/kotoba/net/jvm_host.clj は http-transport を実装し参照先と一致 (undeclared 依存)。修理案 Tier 2: kotoba-net を deps.edn に宣言 (git sha 或は :local/root) → updater_test 単体緑 + フル suite green (evidence/2026-09-08-falsify-75.md) |
| テスト | 3 | test 247 ファイル (head d226614、falsify-84 実測、falsify-82/83 の c792872 と同値)。フルスイートが異なるリビジョンで完走: falsify-6 (bde2171)、falsify-7 (2bca892、約45分)、falsify-14 (clean HEAD、1 failure = 赤-4 のみ)、falsify-15 (負荷下 2292 tests / 13880 assertions / 1 failure = 赤-4 のみ)、**falsify-16 (merged main 1905580、負荷下 2292 tests / 13929 assertions / 0 failures EXIT=0)**。決定論的赤 0、flake 修理 (赤-5、PR #280) 着地済み。3 止まりの根拠: flake リトライ機構なし、OPEN 赤-4 未解決、テスト実行がディスク飽和に脆弱 (falsify-16/17)。**falsify-62 で新規決定論的赤を実測**: 現 anchor head 5e3aac9 で bundle_test ×2 FAIL / graph_test 1 ERROR (published-lock が bundle 内容変化後未再発行)、falsify-38 (cc7a17d) の 0 failures からの新規出現で「決定論的赤 0」は現在形として不成立 — 詳細は falsify-62 追記。**falsify-63 (現 tip 679572b) でスイートがコンパイル不能 (より深刻)**: 679572b java.net.http→kotoba transport migration が identity.clj の ns 形式を破損 ((:import opener 削除 + 5 裸 java ベクタ) — plain require が `Syntax error macroexpanding ns ... identity.clj:1:1` (EXIT 1) で死に、identity を transitively に pull する全テストが load 段階で停止 → 現 tip では assertion を 1 つも実行できない (falsify-62 の 2f+1e 計測は migration 前 5e3aac9 で到達不能)。修理 = identity.clj ns の (:import 復元 (Tier 2 kanban/human) |
| 反証 | 3 | falsify-1〜26 を evidence/ に記録。falsify-9: 赤-2「KeepAlive 欠如で silent-dead」説を反証 (主因は ops-classpath.sh が upstream の authority.scope 追加に未追従で nbb ロード即死)。falsify-10: spec 軸主張を「解決/path-param 契約 (値スキーマなし)」に範囲修正。falsify-11: 赤-2 案 A「classpath 修正で復旧」説を反証試行 — 決定論的依存連鎖を段階実測、案 A の 3 src 追加が必須十分と確認し expiry-alert.cljs rc=0 まで完全復旧を実測 → 精緻化付きで SURVIVED。検証の终点は rc=0、plist 再 bootstrap が必須条件。falsify-12: テスト軸「赤-5 flake は時間切れ型のみ」説 → survived、3 bound 非同期設計を競合窓として同定。falsify-14 (2026-09-05): リスク-2 dirty 前提を REFUTED (本体 main clean 実測)。falsify-16 (2026-09-05): 「着地後の負荷下完走で flake サイトが赤になる」説 → survived (merged main 1905580 で 0 failures 実測、OPEN 赤-5 CLOSED)。falsify-17 (2026-09-05): 「falsify-16 の cache 整理でディスク満杯は解消 (一回性)」説を REFUTED — 同日中に /System/Volumes/Data が 100% / avail 1.9Gi に再飽和を実測、ディスク飽和は再発性の構造リスクと確定 (evidence/2026-09-05-falsify-17.md)。falsify-18 (2026-09-06): falsify-17 の「増加源は du 到達範囲外の可能性」説を反証 — du 実測で支配項を m365-archive/onedrive 133G に帰属確定 (survived→帰属確定)、expiry-alert not running / runs=0 を再実測 (evidence/2026-09-06-falsify-18.md) 。falsify-23 (2026-09-06): 「滞留世代は manifest-rev 参照解放で回収可能」説を REFUTED — manifest-rev=51d4010c (west update 管理) は resident/dns-resolver の祖先で解放しても annex 参照は残る、真の参照元は resident branch の ingest 履歴 (evidence/2026-09-06-falsify-23.md)。falsify-25 (2026-09-06): 滞留の参照元を resident 現在ツリー (data/ledger/) へ帰属修正 (unused ⊆ resident 現在ツリー 100%、detached HEAD 説反証)。falsify-26 (2026-09-06): 増加後も帰属が生存することを再確認 (SURVIVED)。falsify-28 (2026-09-06): 増加継続下 (unused 3372 / 43.27 GiB) でも帰属生存を再確認、滞留全件が resident 現在ツリー参照 (∩ 100%)。falsify-29 (2026-09-06): 増加継続下 (unused 3385 / 43.44 GiB、falsify-28 比 +13 keys / +0.17 GiB) でも帰属生存を再確認、滞留全件が resident 現在ツリー参照 (∩ 100%、unused−resident=0)。falsify-30 (2026-09-06): 増加継続下 (unused 3398 / 43.61 GiB、+13 keys / +0.17 GiB) でも帰属生存を再確認 (∩ 100%、unused−resident=0)。併せて「avail 反発は増加源の停止/減速を反映」説を REFUTED — 増加源 (export_and_sync.cljs PID 82336) が稼働中のまま avail が 1.5Gi→6.6〜7.8Gi へ回復したことを直接観測 (evidence/2026-09-06-falsify-30.md)。falsify-31 (2026-09-06): 増加継続下でも帰属生存を再確認 (unused 3427 / 43.99 GiB、∩ 100%、unused−resident=0)。併せて avail が 50 Gi / 95% へ大回復し増加源 (PID 82336) が ps で自然停止したことを直接観測 (「停止→回復」は充分条件でなく単一帰属は未特定 — falsify-30 の反証が有効) |
| 反証候補falsify30PLACEHOLDER
| 再現性 | 3 | launchd で server/host/tick は再現稼働。releases/ 全 77 ツリーが対応 git commit と byte 完全一致 (falsify-3 実測、2026-09-03) — 測定対象消滅 (falsify-50 REFUTED、~/.cloud-itonami/releases 不存在・運行は source classpath/配布は GitHub Releases ed25519 署名 manifest 検証・version 0.5.7)。ただし不変性は運用規約のみで OS 強制なし。**falsify-74 (2026-09-08) SURVIVED**: 現在形配布主張 (ed25519 署名 manifest 検証) を source 静的検証で裏取り — updater.clj:61-87 verify-manifest が schema/version/assets/Ed25519 全 fail-closed、埋め込み公開鍵資源 resources/cloud-itonami-update-public-key.b64 実在 (44byte base64)、updater_test 7 deftest が 署名 round-trip / 単一フィールド改変→"signature is invalid" / 別鍵 throw / package変更時の digest 不一致→check-and-stage! :error を機械検証。score 3 のまま (source 静的検証のみ、suite 赤のためテスト緑は falsify-63 identity.clj 修理後に再測定) |
| governor 統合 | 3 | active governor: com.gftd.fleet-ci-tip-tick (StartInterval 運用、tamaki maturity-tick は退役)。falsify-6 の「1559 連続 worktree-failed / 毎 tick / 着地 0 継続」は**現在形として falsify-54 で REFUTED** (maturity-tick.log 不存在、fleet-ci 実装 prepare-landing-worktree! --no-checkout+sparse / cleanup --force+prune で構造的に排除、fleet-ci-tick.log worktree-failed 0)。**falsify-60 (本日) で新規 2 不健全を実測**: (1) fleet 検証ステップの signer-key `~/.gftd/fleet-ci-signer-tip.pem` MISSING → 当日 `ci-verify exit 1` 14 / `no receipt` 14 / `preflight SKIP` 28 で実 gate 判定 0; (2) cloud-itonami-app は repos.edn rad-rids に RID 未登録 → gate は全 `:input-rejected` (7/7)、`:pass`/`:fail` 皆無で実 gate 結果に未解決。修理 (signer-key 配置 / RID 登録) は tamaki/fleet 側設定で Tier 2 kanban/human 判断 (evidence/2026-09-07-falsify-60.md)。**falsify-67 (本日) で 2 不健全の未解消を再確認**: signer-key は ls 実測で依然 No such file、RID 未登録 7 件不変・gate 全 :input-rejected 7/7・:pass/:fail 皆無で実 gate 判定 0 継続 (falsify-60 とは独立時点の再確認、evidence/2026-09-08-falsify-67.md)。score 3 維持 (worktree 構造的排除は維持、統合実効の不健全は継続記録) |
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
  **falsify-58 (2026-09-07) で発火実績を観測**: job は当日 09:00 に実発火し
  `runs = 1 / last exit code = 1 / not running`、log mtime Sep 7 09:00 に
  `Could not find namespace: authority.scope` を反復出力、plist classpath に
  案 A の 3 src (authority / org-nist-sha2 / datom-source) 未追加のまま、他方
  namespace ソース `authority/scope.cljc` はディスク上に存在 (classpath 経路
  漏れ単独原因)。実体は「runs=0 (発火履歴なし)」から「**毎発火ごとに失敗**」へ
  精緻化、修理案 A は Tier 2 継続。
  (evidence/2026-09-04-falsify-9.md / -11.md / 2026-09-05-falsify-17.md /
  2026-09-07-falsify-58.md 参照)
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
  **falsify-59 (2026-09-07) で repo root 起動の直接再現 (修理案 A 着地説 REFUTED)**:
  bin/cloud-itonami-app (head 9e5b24a) の shell 解決は repo 相対分支
  (`$app_dir/../../kotoba-lang/shell`) が WORKSPACE_ROOT より先 (launcher 36-42 行目、
  優先順位反転なし)。合成 root /tmp/ci-resident-root-59 に orgs/kotoba-lang/shell を
  置き CLOUD_ITONAMI_WORKSPACE_ROOT を export して repo root から
  `bash bin/cloud-itonami-app --print-shell-dir` を実行 → 出力は WORKSPACE_ROOT 配下でなく
  repo 相対の実 shell (/Users/.../orgs/kotoba-lang/shell) を返す (exit 0)。
  launcher_test の断言 (workspace shell = stdout) は赤。OPEN 赤-4 は未解決のまま、
  修理案 A/B は Tier 2 kanban/human 判断継続 (evidence/2026-09-07-falsify-59.md)。
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
## NEXT (falsify-43 追記、append-only)

**falsify-43 (2026-09-07) で帰属生存 + 増加継続 (レート減速) を再確認 (SURVIVED)**: main-refspec regular unused は
**321 keys / 4,810,182,003 bytes (≈4.48 GiB)** (falsify-42 の 296 keys / 4,444,966,515 bytes から
**+25 keys / +0.34 GiB**、増加継続、local annex 321 keys / 4.81 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 321-key / 4.48 GiB スケールで SURVIVED 継続、
unused−resident=0)。partial chunk は **14 → 14** / temp 186.98MB (annex info) で不変 (falsify-35 以降
連続 8 反復 drift なし、間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続
(falsify-33〜42 と同一 PID、export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。
df avail は 146Gi / 84% (falsify-42 終了時 148Gi から -2Gi 漸減、100% 飽和は解消のまま)。
**増加レートの減速 (+25) は新観測** — falsify-42 の加速 (+62) は単反復で終わり、falsify-40 (+25) /
falsify-41 (+24) 水準に回帰。単反復レートは ~14〜62 の間で振動し単調上昇ではない (128→153→171→185→
210→234→296→321 の実測累積)。「avail 回復 = 増加源停止」は falsify-37 で反証済みのまま、解放源の
単一起因帰属は未特定で継続観測。dropunused は引き続き反復緩和、恒久解消は resident ingest
世代削除 (Tier 2)。avail 146Gi は引続きフルスイート実行可能域 (evidence/2026-09-07-falsify-43.md)。
附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。
本体 checkout は agent/fix-open-red-5-three-bound で porcelain clean (status --porcelain 0 行)
確認、head 9e5b24a。負荷 load 38.17/40.11/38.13。反証軸 score は 3 のまま (falsify-1〜43 記録継続、
本反復は帰属の同一主張のスケール拡大であり score へ質的変更なし)。
## NEXT (falsify-44 追記、append-only)

**falsify-44 (2026-09-07) で帰属生存 + 増加継続 (レートは +24/+25 帯に安定回帰) を再確認 (SURVIVED)**: main-refspec regular unused は
**345 keys / 5,175,204,591 bytes (≈4.82 GiB)** (falsify-43 の 321 keys / 4,810,182,003 bytes から
**+24 keys / +0.34 GiB**、増加継続、local annex 345 keys / 5.18 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 345-key / 4.82 GiB スケールで SURVIVED 継続、
unused−resident=0)。partial chunk は **14 → 14** / temp 186.98MB (annex info) で不変 (falsify-35 以降
連続 9 反復 drift なし、間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続
(falsify-33〜43 と同一 PID、export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。
df avail は 145Gi / 84% (falsify-43 終了時 146Gi から -1Gi 漸減、100% 飽和は解消のまま)。
**増加レートの安定回帰 (+24) は新観測** — falsify-42 の加速 (+62) → falsify-43 (+25) → 本反復 (+24) と
2 反復連続で falsify-40 (+25) / falsify-41 (+24) 水準に回帰。単反復レートは ~14〜62 の間で振動し
単調上昇ではない (累積実測: 128→153→171→185→210→234→296→321→345)。「avail 回復 = 増加源停止」は
falsify-37 で反証済みのまま、解放源の単一起因帰属は未特定で継続観測。dropunused は引き続き反復緩和、恒久解消は resident ingest
世代削除 (Tier 2)。avail 145Gi は引続きフルスイート実行可能域 (evidence/2026-09-07-falsify-44.md)。
附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。
本体 checkout は agent/fix-open-red-5-three-bound で porcelain clean (status --porcelain 0 行)
確認、head 9e5b24a。負荷 load 42.56/41.73/44.06。反証軸 score は 3 のまま (falsify-1〜44 記録継続、
本反復は帰属の同一主張のスケール拡大であり score へ質的変更なし)。

## NEXT (falsify-45 追記、append-only)

**falsify-45 (2026-09-07) で帰属生存 + 増加継続 (レートは +24/+25 帯 3 反復連続安定) を再確認 (SURVIVED)**: main-refspec regular unused は
**370 keys / 5,557,668,058 bytes (≈5.18 GiB)** (falsify-44 の 345 keys / 5,175,204,591 bytes から
**+25 keys / +0.36 GiB**、増加継続、local annex 370 keys / 5.56 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 370-key / 5.18 GiB スケールで SURVIVED 継続、
unused−resident=0)。partial chunk は **14 → 14** / temp 186.98MB (annex info) で不変 (falsify-35 以降
連続 10 反復 drift なし、間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続
(falsify-33〜44 と同一 PID、export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。
df avail は 142Gi / 84% (falsify-44 終了時 145Gi から -3Gi 漸減、100% 飽和は解消のまま)。
**増加レートの 3 反復連続安定 (+25) は新観測** — falsify-42 の加速 (+62) → falsify-43 (+25) → falsify-44 (+24) → 本反復 (+25) と
3 反復連続で falsify-40 (+25) / falsify-41 (+24) 水準に安定。単反復レートは ~14〜62 の間で振動し
単調上昇ではない (累積実測: 128→153→171→185→210→234→296→321→345→370)。「avail 回復 = 増加源停止」は
falsify-37 で反証済みのまま、解放源 (falsify-30/37 の大回復) の単一起因帰属は未特定で継続観測。dropunused は引き続き反復緩和、恒久解消は resident ingest
世代削除 (Tier 2)。avail 142Gi は引続きフルスイート実行可能域 (evidence/2026-09-07-falsify-45.md)。
附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。
本体 checkout は agent/fix-open-red-5-three-bound で porcelain clean (status --porcelain 0 行)
確認、head 9e5b24a。負荷 load 50.52/55.64/48.37。反証軸 score は 3 のまま (falsify-1〜45 記録継続、
本反復は帰属の同一主張のスケール拡大であり score へ質的変更なし)。

## NEXT (falsify-46 追記、append-only)

**falsify-46 (2026-09-07) で帰属生存 + 増加継続 (レートは +24/+25 帯 4 反復連続安定) を再確認 (SURVIVED)**: main-refspec regular unused は
**394 keys / 5,941,365,414 bytes (≈5.53 GiB)** (falsify-45 の 370 keys / 5,557,668,058 bytes から
**+24 keys / +0.36 GiB**、増加継続、local annex 394 keys / 5.94 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 394-key / 5.53 GiB スケールで SURVIVED 継続、
unused−resident=0)。**追加ベクトル**: 全 refs 走査 (`git annex unused` refspec なし) の正規 unused も
**0** — main-refspec で unused 判定された key のうちいかなる ref からも到達不能な孤児は存在しないことを
独立検証。partial chunk は **14 → 14** / temp 186.98MB で不変 (falsify-35 以降連続 11 反復 drift なし、
間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続 (falsify-33〜45 と同一 PID、
export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測。df avail は 141Gi / 85
## NEXT (falsify-47 追記、append-only)

**falsify-47 (2026-09-07、spec/契約 軸) で falsify-10 の placeholder→path-param 整合をコマンド単位で機械検証 (SURVIVED)**: 資源 `resources/cloud-itonami-app.commands.edn` (main checkout head 9e5b24a / porcelain clean) を Python 解析し、`:command [` マーカーで段落分割して各コマンドの `:template` placeholder 集合と `:params` `:name` 集合を**コマンドごとに**突合。実測: `:command` 208 / `:template` 208 / `:in "path"` 128 / placeholder 128。placeholder を持つコマンド 113 のうち、全 128 placeholder が同一コマンドの params に `:name` 宣言あり (**欠落 0**)、全て `:in "path"` (**非 path 0**)、params 宣言が template に現れない orphan **0**。aggregate 一致 (128=128) ではなく当該段落内一致で確認した点が falsify-10 からの精緻化。spec/契約 軸 score は 3 のまま (falsify-10 にコマンド単位突合を追記、値スキーマ検証未整備は不変)。附帯: 前反復 falsify-46 本体は記録中に中断して文末が mid-sentence で truncate された (本ファイル 456 行目 `df avail は 141Gi / 85` で途切れ、falsify-33/34 の 16 行目孤立行と同種の追記中断)。append-only のため本 bot は回復せず operator 復旧待ち。7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` も残存のまま。本体 checkout は agent/fix-open-red-5-three-bound / porcelain clean (dirty 0)、head 9e5b24a (evidence/2026-09-07-falsify-47.md)。
## NEXT (falsify-48 追記、append-only)

**falsify-48 (2026-09-07、運用/滞留帰属 軸) で帰属生存 + 増加継続 (+50 keys / 2 反復跨ぎ ≈ +25/反復) を再確認 (SURVIVED)**: main-refspec regular unused は
**444 keys / 6,699,301,080 bytes (≈6.24 GiB)** (falsify-46 の 394 keys / 5,941,365,414 bytes から
**+50 keys**、増加継続、local annex 444 keys / 6.7 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 444-key / 6.24 GiB スケールで SURVIVED 継続、
unused−resident=0)。**追加ベクトル再確認**: 全 refs 走査 (refspec なし) の正規 unused も
**0** — main-refspec で unused 判定された key のうちいかなる ref からも到達不能な孤児は存在しない
(falsify-46 の独立ベクトルを独立再現)。partial chunk は **14 → 14** / temp 186.98MB で不変
(falsify-35 の 1 chunk 解決以降連続 12 反復 drift なし、間欠的のまま)。増加源 itonami-app-resident.cljs
PID 94682 稼働継続 (falsify-33〜46 と同一 PID、export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測、
gateway /health 127.0.0.1:8080 -> 404 (本反復実測。前反復記録 502 との経緯は継続観測対象)。
df avail は 140Gi / 85% (falsify-46 記録 141Gi から -1Gi、100% 飽和は解消のまま)。増加レートは
+50 keys / 2 反復跨ぎ (falsify-47 は spec 軸で retention 未測定) ≈ 反復あたり +25 で、falsify-40/41/43/44/45
の +24/+25 帯と整合 (falsify-42 の +62 加速への回帰なし、漸減継続)。「avail 回復 = 増加源停止」は
falsify-37 で反証済みのまま、解放源の単一起因帰属は未特定で継続観測。dropunused は引き続き反復緩和、恒久解消は
resident ingest 世代削除 (Tier 2)。avail 140Gi は引続きフルスイート実行可能域。
(evidence/2026-09-07-falsify-48.md)。附帯: 7 軸テーブル 16 行目孤立行
`| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。前反復 falsify-46 本体の
mid-sentence truncate (maturity.md 456 行目) は falsify-47 報告済みのまま append-only で回復せず
operator 復旧待ち。本体 checkout は agent/fix-open-red-5-three-bound / porcelain clean (dirty 0)、
head 9e5b24a。負荷 load avg 60.88/64.71/58.63 (高負荷)。反証軸 score は 3 のまま (falsify-1〜48 記録
継続、本反復は帰属の同一主張のスケール拡大であり score へ質的変更なし)。

## NEXT (falsify-49 追記、append-only)

**falsify-49 (2026-09-07、運用/滞留帰属 軸) で帰属生存 + 増加継続 (+12 keys / +0.17 GiB) を再確認 (SURVIVED)**: main-refspec regular unused は
**456 keys / 6,884,601,846 bytes (≈6.41 GiB)** (falsify-48 の 444 keys / 6,699,301,080 bytes から
**+12 keys / +0.17 GiB**、増加継続、local annex 456 keys / 6.88 GB と一致)。resident-refspec 正規
unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 456-key / 6.41 GiB スケールで SURVIVED 継続、
unused−resident=0)。**追加ベクトル再確認**: 全 refs 走査 (refspec なし) の正規 unused も
**0** — main-refspec で unused 判定された key のうちいかなる ref からも到達不能な孤児は存在しない
(falsify-46/48 の独立ベクトルを独立再現)。partial chunk は **14 → 14** / temp 186,977,095 bytes
(≈178MiB) で不変 (falsify-35 の 1 chunk 解決以降連続 13 反復 drift なし、間欠的のまま)。増加源
itonami-app-resident.cljs PID 94682 稼働継続 (etime 01-08:44:11、falsify-33〜48 と同一 PID、
export_and_sync 不在)、dev /health 127.0.0.1:1338 -> 200 実測、gateway /health 127.0.0.1:8080 -> 404
(falsify-48 と同値)。df avail は 136Gi / 85% (falsify-48 記録 140Gi から -4Gi、100% 飽和は解消のまま)。
増加レートは単反復 **+12 keys** (falsify-48 の 2 反復跨ぎ +50 ≈ +25/反復 より減速、レートは単反復
~12〜62 の間で振動し単調ではない)。「avail 回復 = 増加源停止」は falsify-37 で反証済みのまま、解放源の
単一起因帰属は未特定で継続観測。dropunused は引き続き反復緩和、恒久解消は resident ingest 世代削除
(Tier 2)。avail 136Gi は引続きフルスイート実行可能域 (evidence/2026-09-07-falsify-49.md)。附帯:
7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)。
前反復 falsify-46 の mid-sentence truncate (maturity.md 456 行目) も append-only で回復せず
operator 復旧待ち。本体 checkout は agent/fix-open-red-5-three-bound / porcelain clean (dirty 0 実測)、
head 9e5b24a。負荷 load avg 154.04/159.43/135.05 (極高負荷)。反証軸 score は 3 のまま (falsify-1〜49
記録継続、本反復は帰属の同一主張のスケール拡大であり score へ質的変更なし)。

## NEXT (falsify-50 追記、append-only)

**falsify-50 (2026-09-07、再現性/リリース展開 軸) で「releases/ 全 77 ツリー byte 完全一致」の測定基盤を反証 (REFUTED)**: falsify-3 (2026-09-03) の測定対象だった `~/.cloud-itonami/releases/` は本反復で **不存在** (`ls -ld` 0、製品本体の運行 server PID 94697 は classpath `src:resources:.cpcache/...` の source 展開、resident 増加源 `~/.gftd/bin/itonami-app-resident.cljs` も release-tree 不使用)。launcher/updater の参照は `~/.cloud-itonami/app|data` であり releases とは別、配布供給は updater.clj の GitHub Releases download + `verify-manifest` (ed25519 署名検証)、資源 version = {:version "0.5.7"} を実測。再現性行の「現在形の 77 ツリー byte 完全一致」は測定対象が消滅したため、falsify-3 を過去形実測に範囲修正し、運行 source-classpath / 配布署名 manifest 検証へ展開モデル移行として再記述した。リスク-1 (releases/<sha> 不変性の OS 強制なし) も適用対象消滅のため再帰属を Tier 2 提案。再現性軸 score は 3 のまま (根拠を範囲修正、配布 manifest の検証テスト実在は未確認で次反復候補)。附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)、falsify-46 の mid-sentence truncate も append-only で回復せず。本体 checkout は agent/fix-open-red-5-three-bound / porcelain clean (dirty 0)、head 9e5b24a。

## NEXT (falsify-51 追記、append-only)

**falsify-51 (2026-09-07、再現性/配布 軸) で falsify-50 の次反復候補「配布 manifest の ed25519 検証が実適用され、updater_test に担保がある」を実測 (SURVIVED)**: `src/cloud/itonami/app/updater.clj` を実測 — `verify-manifest` (updater.clj:62-88) はスキーマ/version 正規表現/assets shape/Ed25519 署名のいずれか失敗で ex-info throw (fail-closed)、`check!` は edn パース直後 updater.clj:158-160 で必ず `verify-manifest` を通過 (署名検証を pass しない限り manifest 受容しない)、`download!` (updater.clj:243-254) は署名済み manifest の size/sha256 と実バイトを照合し不一致で delete+throw。`updater_test.clj` は `signed-manifests-fail-closed` (正鍵受容 + 1 フィールド改変拒否 #"signature is invalid" + 別鍵拒否) / `automatic-staging-fails-closed-on-changed-package` (パッケージ改変で staging 拒否、pending.edn 不存在) / `discovery-and-download-stage-only-verified-bytes` (正鍵 E2E stage) を機械テスト実測。**現行 head 9e5b24a の detached worktree (/private/tmp/mt-msloop51、本体 checkout 未 touch) で updater-test のみを直接実行: Ran 6 tests / 19 assertions / 0 failures, 0 errors (EXIT=0、/tmp/up13.log 実測)** — 測定・実行とも捏造なし。再現性軸 score は 3 のまま (根拠に「verify-manifest fail-closed 適用確認 + updater_test 6/19/0/0」を追記)。範囲修正/Tier 2: verify-manifest の構造検証 (schema/version/asset 単独失敗経路) は `signed-manifests-fail-closed` 1 テスト内に bundle され個別機械担保は未整備、リスク-1 再帰属は operator 判断のまま。附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)、falsify-46 mid-sentence truncate も append-only で回復せず。本体 checkout は agent/fix-open-red-5-three-bound / porcelain clean (dirty 0)、head 9e5b24a (evidence/2026-09-07-falsify-51.md)。

## NEXT (falsify-52 追記、append-only)

**falsify-52 (2026-09-07、再現性/配布 軸) で falsify-51 の範囲修正「verify-manifest の構造検証 (schema/version/asset 単独失敗経路) は個別機械担保が未整備」を反証 (REFUTED)**: falsify-51 は「schema/version/asset の単独失敗経路が bundle され個別機械担保は未整備」と記録したが、本反復は `updater.clj` の `verify-manifest` を直接呼び各構造フィールドを**1 つずつ単独で壊して** 7 経路すべてが (a) fail-closed (ex-info throw) かつ (b) 正しい `:type` を返すことを機械実測。detached worktree `/private/tmp/mt-msloop52` (head 9e5b24a、本体未 touch) で probe namespace `itonami-falsify52-probe` を direct java+clojure.main 実行: schema 不一致→`:update/schema`、version 不正→`:update/version`、assets 空→`:update/assets`、asset sha256 非 hex / size 非正 / platform 非 keyword→各 `:update/asset`、無署名→`:update/signature`、**7 経路すべて個別 PASS** (実行: Ran 8 tests / 8 assertions / 0 failures, 1 error — error は probe の dummy 署名が crypto 層に到達した産物であり产品欠陥ではなく、7 構造経路の個別検証には影響なし、/tmp/f52b.log 実測)。「bundle だから機械担保が未整備」は誤り — 個別経路は本反復で独立に反証可能と確定。falsify-51 の「未整備」を「updater_test の編成は bundle だが個別経路は反証可能 (7 経路検証済み)」へ範囲修正。Tier 2 提案: updater_test の `signed-manifests-fail-closed` を schema/version/assets/asset 別 deftest に分割するのは「機械担保の欠如」でなく「テスト編成の好み」に格下げ。再現性軸 score は 3 のまま (根拠に構造検証 7 経路個別 PASS を追記)。附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)、falsify-46 mid-sentence truncate も append-only で回復せず。本体 checkout は agent/fix-open-red-5-three-bound / porcelain clean (dirty 0)、head 9e5b24a (evidence/2026-09-07-falsify-52.md)。
## NEXT (falsify-53 追記、append-only)

**falsify-53 (2026-09-07、運用/滞留帰属 軸) で帰属生存 + 増加継続 (+195 keys / 4 反復跨ぎ) を再確認 (SURVIVED)**: main-refspec regular unused は
**651 keys / 9.7 GB** (`git annex unused --used-refspec +refs/heads/main` 出力 MD5E-s... 行 651 を機械カウント、
`git annex info`: local annex keys 651 / size 9.7 GB / unused keys size 9.7 GB 実測)。falsify-49 の 456 keys / 6.41 GiB から
**+195 keys / +3.3 GiB** (再現性軸 3 反復 [50/51/52] は未測定のため分解不能、単反復レート ~+49 相当の大増加)。
resident-refspec 正規 unused は **0** (帰属「unused ⊆ resident 現在ツリー」が 651-key / 9.7 GiB スケールで SURVIVED 継続、
unused−resident=0)。partial chunk は **14 → 14** / temp 186.98MB で不変 (falsify-35 の 1 chunk 解決以降
連続 17 反復 drift なし、間欠的のまま)。増加源 itonami-app-resident.cljs PID 94682 稼働継続
(falsify-33〜52 と同一 PID、export_and_sync 不在)、dev /health 127.0.0.1:1338 -> **200** 実測、
gateway /health 127.0.0.1:8080 -> **404** (falsify-48/49 と同値)。df `/` avail **179Gi / 9%**
(falsify-49 記録 136Gi / 85% から大回復、100% 飽和は解消のまま)。負荷 load avg 33.22/27.50/33.67 (高負荷)。
dropunused は引き続き反復緩和、恒久解消は resident ingest 世代削除 (Tier 2)。
(evidence/2026-09-07-falsify-53.md)。附帯: 7 軸テーブル 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存のまま (operator 復旧待ち)、
falsify-46 mid-sentence truncate も append-only で回復せず。**状態変化 (リスク-2 再発)**: 本体 checkout は
agent/fix-open-red-5-three-bound で **falsify-52 時点の porcelain clean から dirty に変化** —
`git status --porcelain` で `?? 90-docs/adr/260907-kl-to-kotoba-migration.edn` / `?? 90-docs/kotoba-migration-progress.md`
の untracked 2 ファイルを実測 (Kotoba 移行 ADR 追記の他 bot/human WIP と推定)。SOUL 不変条件に従い本 bot は
本体 checkout を touch せず、作業は wt-msloop / bot/maturity-sim-loop で完結。head 9e5b24a。
運用軸 score は 3 のまま (falsify-1〜53 記録継続、本反復は帰属の同一主張のスケール拡大であり score へ質的変更なし)。
## NEXT (falsify-54 追記、append-only)

**falsify-54 (2026-09-07、governor 統合 軸) で falsify-6 の「1559 連続 worktree-failed、毎 tick、着地 0 landed 継続」を現在形として REFUTED**: falsify-6 の測定基盤 `~/.gftd/itonami-maturity-tick.stdout.log` は **不存在** (ls 全一致 0)、`launchctl list | grep -i maturity` も一致 0 (maturity-tick ジョブはロードされず退役)。現在活動中の governor は **com.gftd.fleet-ci-tip-tick** (PID 57431 / exit 0、StartInterval 300s、`scripts/fleet-ci/tick.cljs`) — `fleet-ci-tick.log` (mtime Sep 7 14:00) と `fleet-ci-tip-tick.stdout.log` (Sep 7 13:45) の `grep -c "worktree-failed"` は両方 **0**、cloud-itonami-app は最新 tick で実 gate 結果 (`:pass` / `:input-rejected` / `:fail`) に解決し `:worktree-failed` ではない。tick 実装は `prepare-landing-worktree!` (tick.cljs:1411-) が `git worktree add --detach --no-checkout --quiet` + `sparse-checkout --no-cone` (tick.cljs:1420-1422) で git-annex read-only残骸を現出させず、`cleanup-worktree!` (tick.cljs:1397-1407) が `worktree remove --force` + `prune --expire now` で missing metadata 即時回収 — falsify-6 の worktree-failed 誘発構造を排除。falsify-6 の「tamaki 側 stage2 chmod 案の着地」は本反復で未確認 (tamaki リポ管理外、別主張)。範囲修正: governor 行を「過去の計測 (1559 連続) → 現在は fleet-ci 実装で cloud-itonami-app 実 gate 結果解決・worktree-failed 0」に再記述。score 3 のまま (tick 稼働・実結果は維持)。附帯: dev /health 200 / gateway /health 404 (本反復実測)、df / avail 174Gi / 9## NEXT (falsify-55 追記、append-only)

**falsify-55 (2026-09-07、実装/テスト 軸) で 7 軸表の stale ファイル数「src 231 / test 205」を現在 head で反証 (REFUTED — 実測 233 / 231)**: falsify-6 (bde2171, 2026-09-04) 時点の計数が 7 軸表に残ったまま再計測されておらず、「現在の head でも src 231 / test 205」という数字主張を反証試行。本体 checkout (agent/fix-open-red-5-three-bound) の head **9e5b24a** に対し `git ls-tree -r --name-only 9e5b24a -- src` / `-- test` を機械取得し path プレフィックス行を grep 計数: **src/ 233 ファイル (差分 +2)、test/ 231 ファイル (差分 +26)** を実測 (wc を /tmp/src_count.txt に記録、pathtest bots_test.clj / launcher_test.clj 存在確認 2 件)。ls-tree はコミットスナップショットそのものなので作業ツリー dirty (untracked kotoba-migration 2) の影響を受けない。数字の drift であり score/質への反証ではない — 実装・テストは増加した (src +2 / test +26)。範囲修正: 実装行「src 231」→「src 233 (head 9e5b24a)」、テスト行「test 205」→「test 231 (head 9e5b24a)」に修正、両軸 score 3 のまま。教訓 (運用手順に反映): 7 軸表の file-count は決定論的で 1 反復で再計測可能なため 1 度 stale 化していた — 対応軸反復でカウントを随時更新する。附帯: 7 軸表 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存 (operator 復旧待ち)、falsify-46 mid-sentence truncate も回復せず。本体 checkout は agent/fix-open-red-5-three-bound / head 9e5b24a、porcelain dirty は risk-2 継続 (untracked kotoba-migration 2 件)。本 bot は touch せず、作業は wt-msloop / bot/maturity-sim-loop で完結 (evidence/2026-09-07-falsify-55.md)。

## NEXT (falsify-56 追記、append-only)

**falsify-56 (2026-09-07、spec/契約 軸) で「値スキーマ (型/必須性) の機械検証は未整備」の全面主張を REFUTED (スコープ修正)**: 生成レジストリ `commands.edn` (head 9e5b24a) を balanced-bracket スキャナで機械パース — command maps **208** (falsify-47 と一致)、flags **123/123 全 plain string**・値スキーマ (map / :type / :required?) flag **0**、params 128 全 `:required? true` 全 `:in "path"`、placeholder 128 で整合 (falsify-10/47 再確認)。一方**新事実**: 別名レジストリ `cli-aliases.edn` は flag 値スキーマ (`:flag`/`:required?`/`:parse`/`:default`/`:enrollment-key`) を明示宣言し、`cli_aliases_test.clj` の `body-specs-use-known-vocabulary` (key 集合を `#{:flag :required? :parse :default :enrollment-key}` に限定、`:parse` を `#{:long :comma-list :boolish :file-contents}` にホワイトリスト) と `every-template-parameter-has-a-source` がそのボキャブラリ/必須性/parse 型を機械検証。resolver `resolve-invocation` (commands.cljc:334-349) は **alias 優先** (両レジストリ merge、alias が生成 registry に勝つ) なので、値スキーマを持つ別名 registry はオペレータ実打コマンドの運用的契約の最上位。範囲修正: spec/契約 行を「値スキーマ機械検証は別名 registry で実施済み、生成 registry flags は値スキーマ無しのまま」に再記述、score 3 のまま (軸の質を覆す変更でない)。附帯: 7 軸表 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存 (operator 復旧待ち)、falsify-46 mid-sentence truncate も回復せず。本体 checkout は agent/fix-open-red-5-three-bound / head 9e5b24a、**porcelain clean (dirty 0) 実測 (falsify-55 の kotoba-migration untracked 2 件は事後着地/解消と推定)**、本 bot は touch せず作業は wt-msloop で完結。eval は /tmp/f56c.txt (scan) に実出力保存 (evidence/2026-09-07-falsify-56.md)。
## NEXT (falsify-58 追記、append-only)

**falsify-58 (2026-09-07、運用 軸) で OPEN 赤-2 (expiry-alert) の「09:00 発火後も未修理のまま失敗し続ける」を確認 (SURVIVED、発火実績を新規観測)**: falsify-18 (09-06) の状態「`runs=0 / never exited`、次回発火 2026-09-07 (Mon) 09:00 (Weekday=1/Hour=9/Minute=0)」に対し、本反復 (2026-09-07 18:09 JST) の
`launchctl print gui/501/com.gftdcojp.itonami.expiry-alert` 実測で **`runs = 1` / `last exit code = 1` / `state = not running`** を観測。ログ `/Users/junkawasaki/.itonami/logs/expiry-alert.log` は mtime **`Sep 7 09:00`** に更新され、内容は `Could not find namespace: authority.scope` (nbb ロード即死、falsify-9/11/17 と同一エラー) を反復出力 — **job は当日 09:00 に実発火し、未修理のまま失敗**。plist **classpath 実測**にも falsify-11 案 A の 3 src (`authority/src` / `org-nist-sha2/src` / `datom-source/src`) のいずれも含まれない (= 修理案 A 未着地)。一方、namespace ソース `orgs/kotoba-lang/authority/src/authority/scope.cljc` は**ディスク上に存在**する (`ls` 実測) — 即ち「無い機能を読んだ」のではなく「ある機能を plist classpath に入れ忘れた」classpath 経路漏れの単独原因。範囲修正: 赤-2 の実体は falsify-18 の「一度も実行されていない (runs=0)」から、本反復で「**毎発火ごとに classpath 不備で失敗 (runs=1, exit=1)**」へ精緻化。修理案 A (classpath 3 src 追加 + plist 再生成 + launchctl relaunch) は kanban/human 判断 (Tier 2) のまま未着地 (修理対象は本体 cloud-itonami-app でなく network-awai リポの plist classpath + システム launchd 改変のため Tier 1 不着地、Tier 2 kanban/human 判断)。運用軸 score は 3 のまま (根拠に「09:00 実発火・失敗の直接観測 + root cause が classpath 経路漏れと確定」を追記)。附帯: 本体 checkout は agent/fix-open-red-5-three-bound / **porcelain clean (dirty 0) 実測**、head 9e5b24a (変更前後テスト緑原理: 本反復は本体未 touch の測定のみ)。7 軸表 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存 (operator 復旧待ち、append-only で本 bot は編集せず)、falsify-46/49 mid-sentence truncate も回復せず (evidence/2026-09-07-falsify-58.md)。

## NEXT (falsify-59 追記, append-only)

**falsify-59 (2026-09-07、テスト 軸) で OPEN 赤-4 (launcher WORKSPACE_ROOT shadow) の
修理案 A 着地説を REFUTED (決定論的再現)**: head 9e5b24a の bin/cloud-itonami-app は repo
相対分支 ($app_dir/../../kotoba-lang/shell) を WORKSPACE_ROOT より先に評価するまま (launcher
36-42 行実測、優先順位反転なし)。合成 root /tmp/ci-resident-root-59 に orgs/kotoba-lang/shell
を置き CLOUD_ITONAMI_WORKSPACE_ROOT を export して repo root から
`bash bin/cloud-itonami-app --print-shell-dir` を実行 → WORKSPACE_ROOT 配下でなく repo 相対の
実 shell (/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/shell) を返す (exit 0、
falsify-8 の赤条件を完全再現)。launcher_test.clj:162 の必然断言は赤。falsify-16 の /private/tmp
緑は shadow 無しの反証外であることに加え、テスト本来の repo root 起動レイアウトでの直接再現を
新規観測。OPEN 赤-4 は未解決のまま、修理案 A (launcher を WORKSPACE_ROOT 優先に) / B は Tier 2
kanban/human 判断継続。テスト軸 score は 3 のまま (根拠に決定論的再現確認を追記、質を覆す変更
でない)。附帯: 7 軸表 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存 (operator 復旧待ち、
append-only で本 bot は編集せず)、falsify-46 mid-sentence truncate も回復せず。本体 checkout は
agent/fix-open-red-5-three-bound / porcelain clean (dirty 0 実測)、head 9e5b24a (変更前後テスト緑
原理: 本反復は本体未 touch の測定のみ)。(evidence/2026-09-07-falsify-59.md)

## NEXT (falsify-60 追記、append-only)

**falsify-60 (2026-09-07、governor 統合 軸) で「現行 governor が cloud-itonami-app を実 gate 結果に解決し統合が機能している」を REFUTED (新規 2 不健全)**: active governor com.gftd.fleet-ci-tip-tick (falsify-54 と同一、StartInterval 運用) の当日 fleet-ci-tick.log (68,738 B / mtime Sep 7 20:21) を実測。**(1)** 検証ステップ signer-key `fleet-ci-signer-tip.pem` が MISSING (`if [ -f ]` 実測 false) → `ci-verify exit 1` 14 回 / `no receipt produced` 14 回 / `preflight SKIP --signer-pem` 28 回、signer-pem 言及増加継続 (mtime 20:10→20:21) — 当日実 gate 判定 **0**。(2)**cloud-itonami-app は RID 未登録**: `no RID registered for cloud-itonami/cloud-itonami-app — skipped` 7 回、gate 解決は全 `["cloud-itonami-app" :input-rejected]` (7/7)、`:pass`/`:fail` 皆無 → falsify-54 の「実 gate 結果に解決」は cloud-itonami-app について成立せず範囲修正。worktree-failed は依然 0 (falsify-54 生存部分維持)。7-軸 governor 行の現在形「1559 連続 worktree-failed / 着地 0 継続」は falsify-54 の修正が行に未反映で陳腐化していた — 本反復で行を最新実測へ再記述。score 3 のまま (worktree 排除維持、新不健全は記録のみ)。修理 2 件 (signer-key 配置 / cloud-itonami-app RID 登録) は tamaki/fleet 側設定で Tier 2 kanban/human 判断、本体 checkout は一切 touch せず。附帯: 本体 checkout agent/fix-open-red-5-three-bound / porcelain clean (dirty 0)、head 9e5b24a。dev /health 200。7 軸表 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存、falsify-46 mid-sentence truncate も回復せず (いずれも operator 復旧待ち、本 bot は append-only で編集せず)。(evidence/2026-09-07-falsify-60.md)

## NEXT (falsify-61 追記、append-only)

**falsify-61 (2026-09-07、実装/テスト 軸) で 7-軸表の file-count「src 233 / test 231 (head 9e5b24a)」の現在値としての適用を REFUTED (HEAD 前進で stale、再アンカー)**: 本体 main checkout の HEAD が workforce 系 merge で 9e5b24a から **5e3aac9** へ前進 (rev-parse 実測、detached HEAD / porcelain clean) したため、falsify-55 が 9e5b24a にアンカーした src 233 / test 231 は現在値ではなくなった。`git ls-tree -r --name-only HEAD -- src|test | wc -l` で **src 237 / test 240** を再実測 (本体 checkout porcelain clean 実測)。増分を `git diff --diff-filter=A 9e5b24a..5e3aac9` で決定論的に特定: 追加 src 4 (bot_bounds.cljc / esign/retention_attestation_core.kotoba / pure_head_probe.kotoba / repo_profile.cljc)、追加 test 9 (bot_bounds_test / esign_retention_kotoba_parity_test / pure_head_probe_test / pure_head_zeroarg_probe_test / repo_profile_test + 4 nbb_cljs)、削除 0。falsify-55 (anchor 9e5b24a) 自体は当時の現在値として正しく、anchor が HEAD でなくなっただけ — 質の変化でないので score は実装・テストとも 3 のまま。範囲修正: 実装行「src 233 (head 9e5b24a)」→「src 237 (head 5e3aac9、falsify-61 実測)」、テスト行「test 231 (head 9e5b24a)」→「test 240 (head 5e3aac9、falsify-61 実測)」に追従更新 (falsify-53 教訓どおり HEAD 前進に応じて file-count を随時再アンカー)。次反復候補: 検証済み current HEAD で決定論的テスト実行を再観測 (加点点が green 保持か)。附帯: 本体 checkout detached は 9e5b24a→5e3aac9、他 bot (codex/emotional-bots / jvm-host-transport 等) の worktree 混在。7-軸表 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER` 残存 (operator 復旧待ち)、falsify-46 mid-sentence truncate も回復せず。本 bot は本体 checkout に一切 touch せず wt-msloop で完結 (evidence/2026-09-07-falsify-61.md)。

## NEXT (falsify-62 追記、append-only)

**falsify-62 (2026-09-07、テスト 軸) で現 anchor head 5e3aac9 の全スイート決定論的実行を実測 —
加加点 5 ns は green (SURVIVED)、bundle_test ×2 FAIL / graph_test 1 ERROR の新規決定論的赤 (「決定論的赤 0」REFUTED)**: 本体から独立した detached worktree
`/private/tmp/mt-msloop62` (head 5e3aac9、本体 checkout 未 touch) で `clojure -M:test`
(:test alias → cloud.itonami.app.test-runner 全 240 ns) を実行。実測 `Ran 2342 tests
containing 14116 assertions. 2 failures, 1 errors. exit=1` (log /tmp/f62.log)。**加加点
5 ns (bot-bounds / pure-head-probe / pure-head-zeroarg / repo-profile /
esign-retention-kotoba-parity) は全て FAIL/ERROR なしで green** (狭義・falsify-61 次
アクションは SURVIVED)。失敗 2 + error 1 は workforce 加加点でない既存 ns —
bundle_test.clj:45/47 `the-published-lock-matches-the-current-document` (生成 bundle の
chain CID `bafkreiabj4eq...sn2ry` vs published-lock `bafkreiabxxp...gbny` 不一致 ×2) と
graph_test.clj:117 `publish-points-at-the-chain-cid` (manifest `:bundle-cid-embed-url-mismatch`
ERROR)。`git merge-base --is-ancestor cc7a17d 5e3aac9` = rc 0 で、falsify-38 (cc7a17d,
0 failures) から cc7a17d..5e3aac9 間 (Kotoba pure-head / compiler advance / esign
retention / wave の bundle 内容変化) で新規出現した決定論的赤。テスト軸表の「決定論的赤 0」
は現在形として不成立 → 範囲修正、新規 OPEN 赤候補 (Tier 2): bundle published-lock の
再発行 (bundle_test / graph_test の赤解消、着地は kanban/human)。score 3 のまま。
附帯 (falsify-53 教訓の再適用): 反復中に本体 HEAD が 5e3aac9 → **679572b** へ前進、
`git ls-tree -r --name-only 679572b -- src|test | wc -l` = **src 240 / test 243**
(5e3aac9 の 237/240 比 +3/+3) — 7-軸表の file-count を tip 679572b へ再アンカー。
実行 worktree の target/test-data に annex read-only 残骸 (falsify-16 NEXT-9 の既知
fail モードの実例) が残り `git worktree remove` が Permission denied — 提案済み手順
(chmod -R u+wx) で退避・削除し worktree 登録も消滅確認。本体 checkout は反復中も
porcelain clean (dirty 0)、本 bot は touch せず wt-msloop のみで完結。7-軸表 16 行目
孤立行 `| 反証候補falsify30PLACEHOLDER` と falsify-46 mid-sentence cut は残存のまま
(operator 復旧待ち、append-only)。(evidence/2026-09-07-falsify-62.md)


## NEXT (falsify-63 追記、append-only)

**falsify-63 (2026-09-08、テスト/実装 軸) で「current HEAD (tip) はビルド可能でスイートを
実行・計測できる」を REFUTED — 現 tip 679572b は identity.clj の ns 形式破損で全テストが
コンパイル段階で停止**: 本体から独立した detached worktree `/private/tmp/mt-msloop63`
(head 679572b、本体 checkout 未 touch) で `clojure -M:test -e "(println :env-ok)"` /
`-e "(require 'cloud.itonami.app.bundle)...)"` / plain `clojure -e "(require
'cloud.itonami.app.identity)...)"` を実行 → 全て **`Syntax error macroexpanding
clojure.core/ns at (cloud/itonami/app/identity.clj:1:1)` / `java.nio.charset - failed:
#{:refer-clojure ...}` で EXIT_RC=1** (:test 設定に依存せず、ソースファイル自体の問題)。
根因: 679572b java.net.http→kotoba transport migration (35 files) が identity.clj の
ns から `(:import` opener と最初の 2 行 (`[java.net URI URLEncoder]` /
`[java.net.http ...]`) を削除する際、残り 5 本の `[java.* ...]` import ベクタ
(java.nio.charset / java.security / java.time / java.util / java.util.concurrent) を
**裸 (ns 直下の clause として) に取り残した** (`git show 5e3aac9` では正しい
`(:import ...)` 形式、`git show 679572b` では裸ベクタ 5 本で `:ns-clauses` spec fail)。
migration 対象 36 ファイルの機械走査で **identity.clj のみ** が `NO-IMPORT bare=5
MALFORMED` (他 35 ファイルは `:import` 形式維持)。影響: identity を直接 require する
src 24 ファイル + test-runner が transitively に pull し、**現 tip では assertion が
1 つも実行できない** — falsify-62 の「2f + 1e」計測 (5e3aac9, migration 前、2342 tests
完走) は現 tip では到達不能、falsify-61/62 の加加点 green 判定も測定不能。テスト軸・
実装軸の score は 3 のまま (重大な新規赤の記録であり質的反証。file-count src 240 /
test 243 (tip 679572b) 自体は正しいが「決定論的赤 0」「suite 実行可」は現 tip で不成立)。
新規 OPEN 赤候補 (Tier 2, severity 高): identity.clj の ns 修復 — 5 ベクタを
`(:import ...)` で wrap (`[java.nio.charset StandardCharsets]` の直前に `(:import` 復元、
`[java.net ...]` 2 行は migration 目的に沿って生かさない)。着地は本体 checkout 編集 +
テスト緑確認が必要で kanban/human 判断 (Tier 2)。修理後の次測定は falsify-62 の
bundle/graph 赤 (bundle published-lock 再発行) の解消確認。附帯: 本体 checkout は反復中
porcelain clean (dirty 0)、本 bot は touch せず wt-msloop のみで完結、実行 worktree は
削除・worktree list から消滅確認。7-軸表 16 行目孤立行 `| 反証候補falsify30PLACEHOLDER`
と falsify-46 mid-sentence truncate は残存のまま (operator 復旧待ち、append-only)。
(evidence/2026-09-08-falsify-63.md)

## NEXT (falsify-64 追記、append-only)

**falsify-64 (2026-09-08、テスト/実装 軸) で「現 tip のビルド不能状態は解決されている
(identity.clj ns は有効な (:import) を持ちスイートは再コンパイルできる)」を REFUTED —
falsify-63 の build-broken current tip が現 HEAD で未着地のまま生存**: HEAD =
origin/HEAD = **679572b** (rev-parse 実測、falsify-63 と同一、`679572b..origin/HEAD` = 0
commits で修理 commit 不存在)。`git show HEAD:src/cloud/itonami/app/identity.clj` で
`(:import` opener **0** / 裸 `[java.*` ベクタ **5** (30-34 行、`:require [...oauth])` 直後に
wrap されず残存) — falsify-63 の malformation と git object レベルで完全一致。本体から独立
した detached worktree `/private/tmp/mt-msloop64` (head 679572b、本体 checkout 未 touch) で
`clojure -M:test -e "(require 'cloud.itonami.app.identity)...")` → **`Syntax error
macroexpanding clojure.core/ns at (cloud/itonami/app/identity.clj:1:1)` /
`java.nio.charset - failed: #{:import}` EXIT_RC=1** (falsify-63 と同一エラーを完全再現)。
副次: origin/jvm-host-transport (00622eb) も裸ベクタ 5 (migration 流に破損)、
origin/agent/fix-open-red-5-three-bound (9e5b24a, migration 前) は `(:import` 付き正当 6 本。
テスト軸・実装軸の score は 3 のまま (重大な新規赤の継続 = 質的反証、修理未着地で
「決定論的赤 0」「suite 実行可」は現 tip で依然不成立。file-count src 240 / test 243
(tip 679572b, falsify-62) は HEAD 前進なしで不変)。新規 OPEN 赤候補 (Tier 2, severity 高、
falsify-63 のまま) : identity.clj ns の 5 ベクタを `(:import ...)` で wrap する修理
(`[java.nio.charset StandardCharsets]` の直前に `(:import` 復元、`[java.net ...]` 2 行は
migration 目的に沿って生かさない)。着地は本体 checkout 編集 + テスト緑確認で
kanban/human 判断 (Tier 2)、本 bot は本体を touch しない。修理後の次測定は falsify-62 の
bundle/graph 赤 (bundle published-lock 再発行) の解消確認。附帯: 本体 checkout は反復中
porcelain clean (dirty 0)、本 bot は touch せず wt-msloop のみで完結。実行 worktree
/tmp/mt-msloop64 は削除・worktree list から消滅確認 (falsify-62 の annex read-only 残骸は
本 head では発生せず remove --force で即時削除)。7-軸表 16 行目孤立行
`| 反証候補falsify30PLACEHOLDER` と falsify-46 mid-sentence truncate は残存のまま
(operator 復旧待ち、append-only)。(evidence/2026-09-08-falsify-64.md)

## NEXT (falsify-65 追記、append-only)

**falsify-65 (2026-09-08、テスト/実装 軸) で「falsify-64 以降に修理が着地し現 tip は再び
コンパイルできる」を REFUTED — build-broken current tip は継続**: HEAD = origin/HEAD =
**679572b** (falsify-63/64 と同一、`679572b..origin/HEAD` = 0 commits = 修理 commit なし)、
`git show HEAD:.../identity.clj` で `(:import` opener **0** / 裸 `[java.*` ベクタ **5** の
malformation が git object レベルで残存 (falsify-63/64 と一致)。HEAD が同一 SHA のため
falsify-63/64 が実測した compile 失敗 (EXIT_RC=1 / Syntax error macroexpanding ns /
java.nio.charset - failed: #{:import}) は決定論的に継続 (compile 再実行は冗長のため本反復では
未実施、同一バイト列からの推移的帰結として記録 — 捏造なし)。テスト軸・実装軸の score は 3 のまま (重大な新規赤の継続であり質的反証。file-count src 240 /
test 243 (tip 679572b, falsify-62 実測) は HEAD 前進なしで不変)。修理案 (identity.clj ns で
`[java.nio.charset StandardCharsets]` の直前に `(:import` を復元し 5 ベクタを wrap) は
Tier 2 kanban/human 判断のまま未着地 (着地には本体 checkout 編集 + テスト緑確認が必要、本 bot
は本体を touch しない)。修理後の次測定は falsify-62 の bundle/graph 赤 (bundle
published-lock 再発行) の解消確認。附帯: 本体 checkout は反復中 porcelain clean (dirty 0)、
本 bot は touch せず wt-msloop のみで完結。7-軸表 16 行目孤立行 placeholder と falsify-46
mid-sentence cut は残存のまま (operator 復旧待ち、本 bot は編集しない)。
(evidence/2026-09-08-falsify-65.md)
## NEXT (falsify-66 追記、append-only)

**falsify-66 (2026-09-08、テスト/実装 軸) で「falsify-65 以降に修理が着地し現 tip は再び
コンパイルできる」を REFUTED — build-broken current tip は継続**: HEAD = origin/HEAD =
**679572b** (falsify-63/64/65 と同一、`679572b..origin/HEAD` = 0 commits = 修理 commit なし、
porcelain clean dirty 0 実測)。`git show HEAD:.../identity.clj` で `(:import` opener **0** /
裸 `[java.*` ベクタ **5** (ファイル行 29-33、`:require [...oauth])` 直後に wrap されず残存)
の malformation が git object レベルで残存 (falsify-63/64/65 と一致)。HEAD が同一 SHA のため
falsify-63/64/65 が実測した compile 失敗 (EXIT_RC=1 / Syntax error macroexpanding ns /
java.nio.charset - failed: #{:import}) は決定論的に継続 (compile 再実行は冗長のため本反復では
未実施、同一バイト列からの推移的帰結として記録 — 捏造なし)。テスト軸・実装軸の score は 3 のまま
(重大な新規赤の継続であり質的反証。file-count src 240 / test 243 (tip 679572b, falsify-62 実測)
は HEAD 前進なしで不変)。修理案 (identity.clj ns で `[java.nio.charset StandardCharsets]` の
直前に `(:import` を復元し 5 ベクタを wrap、`[java.net ...]` 2 行は migration 目的に沿って
生かさない) は Tier 2 kanban/human 判断のまま未着地 (着地には本体 checkout 編集 + テスト緑
確認が必要、本 bot は本体を手に触れない)。修理後の次測定は falsify-62 の bundle/graph 赤
(bundle published-lock 再発行) の解消確認。附帯: 本体 checkout は反復中 porcelain clean
(dirty 0)、本 bot は touch せず wt-msloop のみで完結。プローブ訂正: runbook と同型の stale
orphan worktree (`.../cloud-itonami-app/.git/worktrees/worktree`, git fatal) を踏み、正体
(orgs/cloud-itonami/cloud-itonami-app) に対して測定。7-軸表 16 行目孤立行 placeholder と
falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、本 bot は編集しない)。
(evidence/2026-09-08-falsify-66.md)

## NEXT (falsify-67 追記、append-only)

**falsify-67 (2026-09-08、governor 統合 軸) で「falsify-60 以降に signer-key 配置 / RID 登録の
Tier 2 修理が着地し cloud-itonami-app が実 gate 判定を受ける」を REFUTED — 修理は両方とも未着地**:
active governor com.gftd.fleet-ci-tip-tick (falsify-54/60 と同一、launchctl list で稼働確認) の
当日 fleet-ci-tick.log (mtime Sep 8 03:25 JST / 79,826 B 実測) とファイル存在を実測。**(1) signer-key
`~/.gftd/fleet-ci-signer-tip.pem` は本反復の `ls -ld` で依然 No such file (MISSING)**、preflight
SKIP が 09-07 18:25Z まで無間隔で連続継続 (fallback ci-verify exit 1、実 gate 判定への到達なし)。
(2) cloud-itonami-app は **RID 未登録のまま 7 件不変**、gate 解決は全
`["cloud-itonami-app" :input-rejected]` 7/7、`:pass`/`:fail` は皆無 (実 gate 判定 0)。
worktree-failed は 0 維持 (falsify-54/60 の生存部分)。falsify-60 の発見 (統合実効の不健全 2 件) の
生存を、真逆の主張 (修理着地済み) を falsify-60 から独立の時点 (翌日) で再確認したもの。governor 統合
score は 3 のまま (worktree 構造的排除は維持、実効不健全は未解消のまま記録継続)。修理 2 件は
tamaki/fleet 側設定の Tier 2 kanban/human 判断のまま未着地、着地は cloud-itonami-app 本体外のため
Tier 1 不着地。附帯: 本体 checkout は agent/fix-open-red-5-three-bound 相当の detached HEAD
679572b / porcelain clean (dirty 0)、head 679572b (HEAD=origin/HEAD、679572b..origin/HEAD=0)、
src 240 / test 243 不変 (HEAD 前進なし)、identity.clj malformation (falsify-63〜66 の build-broken
tip) は git object に残存のまま (本反復の対象外、既知赤)。7-軸表 16 行目孤立行 placeholder と
falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、本 bot は編集しない)。
(evidence/2026-09-08-falsify-67.md)
## NEXT (falsify-68 追記、append-only)

**falsify-68 (2026-09-08、テスト/実装 軸) で「falsify-66 以降に修理が着地し現 tip は再びコンパイルできる」を REFUTED — build-broken current tip の継続を一次 compile 実測で独立再確認**: HEAD = origin/HEAD = origin/main = **679572b** (rev-parse 実測、falsify-63/64/65/66 と同一、`679572b..origin/HEAD` = 0 / `679572b..origin/main` = 0 commits = 修理 commit なし、porcelain clean dirty 0)。`git show HEAD:.../identity.clj` で `(:import` opener **0** / 裸 `[java.*` ベクタ **5** (行 29-33) の malformation が git object レベルで残存 (falsify-63/64/65/66 と完全一致)。**falsify-66 が未実施 (遷移的帰結のみ) とした一次 compile 実測を本反復で実行**: 本体から独立した detached worktree `/private/tmp/mt-msloop68` (head 679572b、本体 checkout 未 touch) で `clojure -M:test -e "(require 'cloud.itonami.app.identity)(println :f68-ok)"` → **`Syntax error macroexpanding clojure.core/ns at (cloud/itonami/app/identity.clj:1:1)` / `java.nio.charset - failed: #{:refer-clojure}` で EXIT_RC=1** — falsify-63 と同一の ns spec エラーを独立・直接再現 (falsify-66 の「同一 SHA → 同一バイト → 同一失敗」の遷移が実測で裏付けられた)。実行 worktree は remove --force / prune 済みで worktree list に残存せず。テスト軸・実装軸の score は 3 のまま (重大な新規赤の継続であり質的反証。file-count src 240 / test 243 (tip 679572b, falsify-62 実測) は HEAD 前進なしで不変)。修理案 (identity.clj ns で `[java.nio.charset StandardCharsets]` の直前に `(:import` を復元し 5 ベクタを wrap) は Tier 2 kanban/human 判断のまま未着地 (着地には本体 checkout 編集 + テスト緑確認が必要、本 bot は本体を touch しない)。修理着地は (1) origin/main への HEAD 前進 (2) identity.clj の (:import 復元 (git object) (3) detached worktree の一次 compile EXIT_RC=0 の 3 点で判定する。修理後の次測定は falsify-62 の bundle/graph 赤 (bundle published-lock 再発行) の解消確認。附帯: 本体 checkout は反復中 porcelain clean (dirty 0)、本 bot は touch せず wt-msloop のみで完結。7-軸表 16 行目孤立行 placeholder と falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、本 bot は編集しない)。(evidence/2026-09-08-falsify-68.md)
## NEXT (falsify-69 追記、append-only)

**falsify-69 (2026-09-08、運用 軸) で OPEN 赤-2 (expiry-alert) の falsify-58「単独原因 authority.scope」を REFUTED (複数 load 失敗連鎖へスコープ修正)**: 本体 checkout は HEAD=origin/main=**679572b** (falsify-63..68 と同一、porcelain clean)、identity.clj (:import 0 / bare-java 5) は既知 build-broken (本書対象外)。`launchctl print` 実測で expiry-alert は `state = not running / runs = 1 / last exit code = 1` (falsify-58 の独立時点再確認)。**ログ全文 (109 行, mtime Sep 7 09:00, 8547 B) 実測**で、成功 1 行 (2026-07-14 OK) の後に**複数の異なる load 失敗連鎖**を確認: `Could not find namespace: kotoba-rad.cacao-delegate` ×2 / `Protocol not found: IEquiv` @ ipld/core.cljc:36 + ipld/link.cljc:11 (deftype Link) / `Could not find namespace: datalog.index` ×1 / `Could not find namespace: authority.scope` ×3 (**終端**)。インストール済み plist classpath は bonsai/nekko/arrangement/prolly-tree/io-ipld/io-multiformats/chain/org-ietf-cbor/org-ietf-ed25519/org-chainagnostic-cacao/mail/mailer/datalog の src を含む一方、**falsify-11 案 A の 3 src (authority/src / org-nist-sha2/src / datom-source/src) は全て grep -c = 0 で不在**。依存 ns はディスク上実在 (nekko/cacao_delegate.cljc, ipld/core|link.cljc, authority/scope.cljc, datalog/index.cljc) だが `kotoba-lang/kotoba-rad` checkout は不在 (ls 実測)。→ **「単独原因 authority.scope」は REFUTED (スコープ修正)**: 同一ログ内で authority.scope の前段に ipld deftype `Protocol not found: IEquiv` と cacao-delegate / datalog.index の異なる失敗クラスが実在し、authority.scope は load 連鎖の終端。plist classpath は既に nekko/io-ipld/cacao/datalog を含むが同一で失敗しており、**修理案 A の 3 src 追加だけでは ipld IEquiv (load 段階) と cacao-delegate (ネームスペース解決) に触れないため「唯一かつ十分」でない**。score 3 のまま (root cause を単独→連鎖へ範囲修正、job は未修理・未着地のまま)。修理案 A は継続 Tier 2、附帯 Tier 2 提案: 「load 連鎖全体 (ipld IEquiv reader 条件 / cacao-delegate / datalog.index) の精査と nbb + Node v26.0.0 互換性再確認」。次の測定: nbb が Node v26 下で ipld の `#?@(:clj ...)` reader 条件 / IEquiv protocol 解決に失敗する機構の個別再現。附帯: 本体 checkout は反復中 porcelain clean (dirty 0)、本 bot は touch せず wt-msloop のみで完結、修理対象は本体外 (network-awai plist classpath / expiry-alert 依存) のため Tier 1 不着地。7-軸表 16 行目孤立行 placeholder と falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、本 bot は編集しない)。(evidence/2026-09-08-falsify-69.md)
## NEXT (falsify-70 追記、append-only)

**falsify-70 (2026-09-08、運用 軸) で falsify-69 の「修理案 A は唯一かつ十分でない（ipld deftype
IEquiv / cacao-delegate / datalog.index が前段に残る）」を REFUTED — 現行ツリーは案 A 追加だけで
全 load 連鎖が解決する**: 本体 checkout HEAD=origin/main=**679572b**/porcelain clean、
expiry-alert state=not running / runs=1 / last exit code=1 (falsify-58/69 と同値再確認)、
nbb v1.4.208 / Node v26.0.0 実測。**(a) 現行ツリーの plist と同一 classpath (15 エントリ、案 A
3 src 不在を launchctl print 実測) で scripts/expiry-alert.cljs を nbb 実行 → 唯一
`Could not find namespace: authority.scope` の単独終端 (EXIT_RC=1) のみ** — Sep-7 ログの
`deftype IEquiv` (ipld/core.cljc:36, ipld/link.cljc:11)・`kotoba-rad.cacao-delegate`・
`datalog.index` は現行ツリーで**再現しない** (/tmp/f70_repro2.txt)。**(b) 現行
io-ipld/src/ipld/link.cljc は `(defrecord Link [cid] ILink ...)` (deftype なし)、git blame
で `defrecord Link` = commit 309db3f (2026-08-11, "fix Link equality across nbb runtimes") —
deftype IEquiv 失敗は 2026-08-11 より前の旧 io-ipld リビジョン由来で既解決、`nekko/
cacao_delegate.cljc` はディスク上実在 (`(ns nekko.cacao-delegate ...)`)、kotoba-rad
checkout 不在 (ls rc=1) = 旧ログの cacao-delegate は kotoba-rad→nekko 移行前の失敗。
**(c) 案 A 3 src (authority/src / org-nist-sha2/src / datom-source/src、全てディスク上存在)
を先行追加した classpath で require 連鎖を直接実行 `(require 'cloud-itonami.ops-keys
'authority.scope 'ipld.core 'ipld.link 'nekko.cacao-delegate 'datalog.index)` → `:f70-load-ok`
/ EXIT_RC=0** (kagi/vault 非接触、/tmp/f70_probeB.txt)。falsify-69 の「案 A は前段の ipld
IEquiv / cacao-delegate に触れず不十分」は旧依存リビジョン前提で、現行ツリーでは案 A が
**唯一かつ十分の範囲へ復帰**。但し「plist 未追記・実 job 毎発火失敗・未着地・修理 Tier 2
(ops-classpath.sh 修正 + plist 再生成 + launchctl relaunch、本体外・システム変更で Tier 1
不着地)」は不変。運用軸 score は 3 のまま (root cause を 単独→連鎖→「連鎖は依存リビジョン
硬化で縮退、案 A 唯一十分へ復帰」と再範囲修正、job 未修理のまま記録継続)。次の 1 アクション:
OPEN 赤-2 を「現行ツリーの失敗は authority.scope 単独終端 / 案 A 追加で require 連鎖 EXIT_RC=0」
へ再範囲修正し、Tier 2 report で案 A 着地ランブックを再提示。附帯: 本体 checkout は反復中
porcelain clean (dirty 0)、本 bot は touch せず wt-msloop のみで完結。7-軸表 16 行目孤立行
placeholder と falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、append-only のため
本 bot は編集しない)。falsify-69 の next「nbb が Node v26 下で ipld #?@(:clj ...) reader 条件 /
IEquiv 解決に失敗する機構の個別再現」は本反復 (b) で「失敗は旧リビジョン由来で現行ツリーの
再現対象ではない」として決着。identity.clj malformation (falsify-63..68 build-broken tip) は
既知赤として対象外。※ falsify-70 では案 A の着地可否そのものは judge せず (kanban/human 判断)。
## NEXT (falsify-71 追記、append-only)

**falsify-71 (2026-09-08、spec/契約 軸) で「transport migration がコマンド契約表面を変更し placeholder<->params 整合不変量を破る」を SURVIVED (整合生存、再アンカー)**: HEAD = origin/main = 679572b (rev-parse 実測、falsify-63..70 と同一 SHA)。commands.edn は 9e5b24a(72c4dd2)→679572b(c44cc35) と変化したが、diff (1 ins / 1 del、tr 分割) の実体は **:counts {routes 408→409 :commands 208→209} + 単一 params なし GET コマンド `workspace resources` の追加のみ**。cli-aliases.edn は byte 完全一致 (5301368 両 SHA)。tip 679572b の機械解析: templates 209 / placeholder-bearing 113 / placeholder tokens 128 / 宣言 params 128 / 欠落 **0** / schema "cloud.itonami.app.commands.v1" 不変 — falsify-47/56 (head 9e5b24a、208 コマンド) の整合主張が migration 後も生存。spec 軸 根拠 を 208→209 に再アンカー、score 3 のまま (質を覆す変更なし)。附帯 Tier 2: transport migration と `workspace resources` コマンド追加が同一コミットに混在 (git history 分離 / リリース差分の因果特定性)。次アクション: workspace resources の gate :app 契約 vs server.clj ルート (routes 408→409) 突合、または OPEN 赤-2 案 A 着地後 rc=0 再確認。附帯: 本体 checkout porcelain clean (dirty 0) 実測、本 bot は touch せず wt-msloop のみで完結。identity.clj malformation (falsify-63..68) は origin SHA 不変ゆえ存続、対象外。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存 (operator 復旧待ち、append-only で本 bot は編集しない)。(evidence/2026-09-08-falsify-71.md)
## NEXT (falsify-72 追記、append-only)

**falsify-72 (2026-09-08、spec/契約 軸) で「追加コマンド workspace resources の :gate :app 契約が server.clj ルート (routes 408→409) と一致する」を SURVIVED (5 層突合完了)**: HEAD = origin/main = 679572b (falsify-63..71 と同一 SHA)、本体 porcelain clean (dirty 0)。① `git diff 9e5b24a 679572b -- server.clj` は単一 hunk `@@ -4390,6 +4390,17 @@` のみ = **workspace/resources ルート (GET /api/workspace/resources、require-app-session!、bots/resources) が 408→409 の +1 ルート**と確定。② route_scan.cljc の gate-of 機械導出を実測: require-app-session!→`:app` (131-145)、routes が各ルートに :gate 付与 (282)、registry は :app/:session のみ公開 (303)、:counts {:routes (count all) ...} (310-313)。③ commands.edn (tip c44cc35) の workspace resources エントリ = `{:command ["workspace" "resources"], :method "GET", :template "/api/workspace/resources", :params [], :flags [], :gate :app, :route "/api/workspace/resources"}` が**ちょうど 1 件**。④ 実行可能検証 (detached worktree /private/tmp/mt-msloop72、head 679572b、本体未 touch): scan/registry 評価 → :counts {routes 409, commands 209, human-only 70, unauthenticated 130}、workspace-resources-cmd gate :app、scan/routes の同ルート gate :app (EXIT_RC=0)。⑤ fresh (scan/registry (slurp server.clj)) とチェックイン済み commands.edn を比較 → `:equal true` / 両 gate :app — commands-test fails-when-no-longer-matches の不変量が現 head で成立。spec/契約 軸 score は 3 のまま (falsify-56/71 の範囲修正に本突合を追記、質を覆す変更なし)。注記 (捏造なし・既知赤): `commands_test.clj:39-47 the-registry-matches-the-routes` が設計上の機械ガードだが、現 tip は identity.clj build-broken (falsify-63..68) で :test フルスイート green 実行不可 — 本反復は同一 scanner を直接実行して ④⑤ で不変量を実測 (route-scan は clojure.string のみ require、identity 非依存で単独起動可)。次アクション: OPEN 赤-2 案 A 着地後 rc=0 再確認、または falsify-62 の bundle/graph 赤 (published-lock 再発行) 解消確認 (identity.clj 修理後に実行可能)。附帯: 本体 checkout porcelain clean (dirty 0)、touch せず wt-msloop のみで完結、実行 worktree mt-msloop72 は remove --force / prune 済みで工作ツリー list に残存せず。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、append-only で本 bot は編集しない)。identity.clj malformation は origin SHA 不変ゆえ存続、対象外 (evidence/2026-09-08-falsify-72.md)。

## NEXT (falsify-73 追記、append-only)

**falsify-73 (2026-09-08、運用 軸) で「修理案 A の 3 src (authority/src + org-nist-sha2/src +
datom-source/src) がインストール済み/ロード済み launchd classpath に追加済み → 次回
(2026-09-14 Mon 09:00) 発火で expiry-alert が rc=0 に復旧する (案 A 着地済み)」を
REFUTED (未着地を確定)**: 本体 checkout HEAD=origin/main=**679572b**/porcelain clean。
`launchctl list` 実測 `- 1 com.gftdcojp.itonami.expiry-alert`、`launchctl print` 実測
`state = not running / runs = 1 / last exit code = 1` (event trigger Weekday=1 Hour=9
Minute=0 = 毎月曜 09:00) で、**ロード済み classpath (15 エントリ) とインストール済み
plist (mtime Aug 13) いずれも 3 src 全欠** (plist `grep -c 'authority|nist-sha2|datom-source'`
= **0** / rc=1)。前回発火 Sep-7 09:00 のログ tail 実測は `Error: Could not find namespace:
authority.scope` (Node v26.0.0) で、falsify-70 の単独終端と同一 — 次回 Mon 09:00 も同一
失敗見込み (確認は着地後の rc)。**範囲修正 (新情報)**: `git ls-tree -r 679572b` grep
`expiry|ops-classpath` = ヒット 0 で、修理対象 `scripts/expiry-alert.cljs` も
`scripts/ops-classpath.sh` も **cloud-itonami-app に存在せず**、job の WorkingDirectory /
classpath は別リポジトリ **`network-awai/cloud-itonami`** を指す — 修理は対象リポジトリ
本体外 (sibling repo) に完結し、本 bot の Tier 1 着地範囲外 (Tier 2 kanban/human)。Tier 2
report で案 A 着地ランブック (network-awai/cloud-itonami 側 ops-classpath.sh に 3 src 追加
→ plist 再生成 → launchctl bootout/bootstrap) を再提示、着地後の rc=0 再確認が終点判定
(falsify-11 継承)。運用 軸 score は **3 のまま** (root cause・案 A の十分性は falsify-70
で確定済み、本反復は未着地確定 + 修理範囲の sibling 帰属のみで green 化測定なし)。附帯:
本体 checkout porcelain clean (dirty 0)、本 bot は touch せず wt-msloop のみで完結。修理
対象は本体外のため Tier 1 不着地。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-
sentence cut は残存 (operator 復旧待ち、append-only で本 bot は編集しない)。identity.clj
malformation (falsify-63..68) は対象外 (evidence/2026-09-08-falsify-73.md)
## NEXT (falsify-75 追記、append-only)

**falsify-75 (2026-09-08、実装 軸) で「falsify-74 境界 (a): identity.clj 修理後は
updater_test 単体動行で verify-manifest の緑を再確認できる」を REFUTED —
第二の独立 build-ブレーク (kotoba-net 未宣言依存) を同一 tip に確定**: HEAD =
origin/main = **679572b** (rev-parse 実測、falsify-63..74 と同一 SHA)、本体 porcelain
clean (dirty 0)、src 240 / test 243 不変。detached worktree /private/tmp/mt-msloop75
(head 679572b、本体未 touch) で、:test alias の :main-opts (test-runner、全 suite 強制
require) を使わず `clojure -Sdeps '{:aliases {:utonly {:extra-paths ["test"]}}}' -M:utonly
-e "(require 'cloud.itonami.app.updater-test)(run-tests ...)"` で updater-test **単一 ns**
のみを require → **`Could not locate kotoba/net/jvm_host__init.class ... on classpath`
FileNotFoundException (EXIT_RC=1)**。依存連鎖を source で確定:
`updater.clj:13 → [cloud.itonami.app.http-client :as http]` →
`http_client.clj:16 (:require [kotoba.net.jvm-host :as jvm-host])` →
`http_client.clj:22 (jvm-host/http-transport {...})`。`git show 679572b:deps.edn` 全文 grep
で `kotoba-net` ヒット **0** (:deps git dep なし、:paths ["src" "resources"] に含まず、
:dev 含む全 :local/root ~30 件にもなし)。migration commit 679572b は 36 ファイル
(updater.clj/identity.clj/http_client.clj 等) を書き換えつつ **deps.edn を変更しておらず**
(git show --name-only に deps.edn なし)、`git grep jvm-host 679572b -- src test deps.edn`
は http_client.clj の 3 箇所のみ — つまり transport 移行が依存先 kotoba.net.jvm-host を
参照しつつ提供元 kotoba-net の宣言を欠く。sibling `../../kotoba-lang/kotoba-net/src/kotoba/
net/jvm_host.clj` (branch jvm-host-transport) は `(defn http-transport ...)` を実装し
呼び出し形と一致 (undeclared 依存)。→ falsify-63..68 の「identity.clj 唯一 build-ブレーク」
範囲を拡張する**新規・独立の発見** (実装 軸: 宣言整合性)。実装 軸 score は **3 のまま**
(新規 build-ブレーク継続を確定したのみ、green 化測定なし)。修理案 Tier 2
(kanban/human、本体 checkout 編集を要し Tier 1 不着地): deps.edn に
`io.github.kotoba-lang/kotoba-net` を git sha 或は `:local/root` で (transport 移行同
commit 或は直後の修正 commit として) 宣言 → detached worktree で updater_test (7 deftest)
単体動行緑 + 可能ならフル suite green を終点判定に。テスト 軸へ波及: falsify-62 の
"決定論的赤 0 は current で不成立" + falsify-63 の "suite コンパイル不能" に本発見
(build-ブレークは identity.clj だけではない) を追記。次の 1 アクション: Tier 2 report
で「transport 移行 commit の未完了依存 (kotoba-net 未宣言)」を提出し、kotoba-net 宣言 +
updater_test 単体緑確認を終点判定に。附帯: 本体 checkout は反復中 porcelain clean
(dirty 0)、本 bot は touch せず wt-msloop のみで完結、実行 worktree mt-msloop75 は
remove --force / prune 済みで worktree list に残存せず。7-軸表 16 行目孤立行 placeholder
/ falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、append-only で本 bot は
編集しない)。identity.clj malformation (falsify-63..68) は既知赤として継続、本発見は
それとは独立の新規 build-ブレーク (evidence/2026-09-08-falsify-75.md)

## NEXT (falsify-76 追記、append-only)

**falsify-76 (2026-09-08、実装 軸) で falsify-75 修理案第 1 歩 (kotoba-net :local/root 宣言) のみで
updater_test 単一 ns が jvm-host 未解決を越えて green 完走と実測 — claim SURVIVED、範囲精緻化**:
HEAD = origin/main = **679572b** (falsify-63..75 と同一 SHA)、本体 porcelain clean (dirty 0)、
git ls-tree 実測 src 240 / test 243 (falsify-75 と一致)。detached worktree `/private/tmp/mt-msloop76`
(head 679572b、本体未 touch) で、falsify-75 の「:main-opts 全 suite 強制 require を避けた単一 ns 実行」を、
`-Sdeps` で kotoba-net `{:local/root "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/
kotoba-net"}` を追加して実施 → identity.clj に一切 touch せず
`Ran 6 tests containing 19 assertions / 0 failures, 0 errors / EXIT_RC=0` (/tmp/f76.log 実測)。
falsify-75 が同一連鎖で `Could not locate kotoba/net/jvm_host__init.class` (rc=1) に停止したのに対し、
kotoba-net 宣言単独で undeclared 依存は解消。load path を source で確定: updater_test.clj → updater.clj:13
(config + http-client) → http_client.clj:16 (kotoba.net.jvm-host) + config.clj (edn/io/config-policy/str/
policy — identity 非関与)。**範囲精緻化**: falsify-74 境界 (a) / falsify-75 の暗黙前提「updater_test 緑に
identity.clj 修理が前提」は不成立 — updater_test の load path は identity.clj に到達せず、2 つの
build-break は独立かつ異なるテスト面に影響する (A) kotoba-net 未宣言 = http-client transitively pull 面
(updater_test 等)、(B) identity.clj malformation = identity transitively pull 面 (suite 大部分)。実装 軸
score は **3 のまま** (フル suite は identity.clj により依然コンパイル不能、一 hermetic suite の unblock
確認のみで suite 全体 green 化測定なし)。修理案 Tier 2 (kanban/human、本体 checkout 編集を要し Tier 1
不着地): (A) は deps.edn への kotoba-net 宣言 (:git/sha 或は :local/root) のみで updater_test 単体緑が EOF
(本反復で実測済み)、(B) は falsify-68 の identity.clj (:import 復元) が EOF (detached worktree 一次 compile
rc=0 + フル suite green)。両着地後のフル suite green を最終終点に、falsify-62 の bundle/graph 赤
(published-lock 再発行) もその後再確認。テスト 軸に波及: falsify-75 の「build-break は identity.clj だけ
ではない」に「updater_test 面は kotoba-net 宣言が必須十分、identity.clj はこの面に非関与」を精緻化追記。
附帯: 本体 checkout は反復中 porcelain clean (dirty 0)、本 bot は touch せず wt-msloop のみで完結、
実行 worktree mt-msloop76 は remove --force / prune 済みで worktree list に残存せず。7-軸表 16 行目孤立行
placeholder / falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、append-only で本 bot は編集
しない)。identity.clj malformation (falsify-63..68) は既知赤として継続、本反復は独立の build-break の
一方 (kotoba-net) を unblock 検証 (evidence/2026-09-08-falsify-76.md)


## NEXT (falsify-77 追記、append-only)

**falsify-77 (2026-09-08、実装/テスト 軸) で「HEAD 前進に伴い falsify-68 の修理が着地し
現 tip は再びコンパイルできる」を REFUTED — build-broken は新 HEAD e2e3bd8 で生存継続**:
HEAD = origin/main = origin/HEAD = **e2e3bd8** (rev-parse 実測、falsify-63..76 の 679572b から
bot_bounds/bot_slo の `.kotoba` 抽出 4 commit 分前進、`git log 679572b..HEAD` = e2e3bd8/
b83e85b/365745d/f1c7290)、本体 porcelain clean (dirty 0)、src 242 / test 245 (git ls-tree
実測、240/243 から +2/+2 再アンカー)。`git show HEAD:.../identity.clj` で `(:import` opener
**0** / 裸 `[java.*` ベクタ **5** (29-33 行) の malformation が git object レベルで残存
(falsify-63..76 と完全一致、新 HEAD でも修理 commit 無し)。deps.edn `grep -c kotoba-net` =
**0** (falsify-75/76 の undeclared dependency も残存)。detached worktree /private/tmp/
mt-msloop77 (head e2e3bd8、本体未 touch) で一次 compile
`-e "(require 'cloud.itonami.app.identity)"` → **`Syntax error macroexpanding
clojure.core/ns at (cloud/itonami/app/identity.clj:1:1)` / `java.nio.charset - failed:
#{:import}` EXIT_RC=1** — falsify-63/64/68 と同一エラーを新 HEAD で独立・直接再現。falsify-68
の修理着地判定 3 条件は 1/3 充足 (HEAD 前進のみ、(2)(3) 未達で build-break 生存)。テスト軸・
実装軸 score は **3 のまま** (重要新規赤の継続であり質的反証。新 HEAD でも「決定論的赤 0」「suite
実行可」は不成立)。修理案 Tier 2 (kanban/human、本体 checkout 編集を要し Tier 1 不着地) は
falsify-68/75/76 と不変: (B) identity.clj の 5 裸ベクタを `(:import ...)` で wrap、(A) deps.edn
への kotoba-net 宣言 (:git/sha 或は :local/root) → updater_test 単体緑 (falsify-76 実測済み)。
両着地後のフル suite green を最終終点に、falsify-62 の bundle/graph 赤 (published-lock 再発行)
もその後再確認。附帯: 本体 checkout は反復中 porcelain clean (dirty 0)、本 bot は touch せず
wt-msloop のみで完結、実行 worktree mt-msloop77 は remove --force / prune 済みで worktree list
に残存せず (grep -c = 0)。bot_bounds / bot_slo 抽出 commit 自体の質は本反復の対象外 (次反復候補:
`.kotoba` 抽出の構文/往来整合)。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut
は残存のまま (operator 復旧待ち、append-only で本 bot は編集しない)。
(evidence/2026-09-08-falsify-77.md)


## NEXT (falsify-78 追記、append-only)

**falsify-78 (2026-09-08、実装 軸) で「新 HEAD 前進 (679572b→e2e3bd8→a97bf59) の .kotoba 抽出は
全て decision authority が .kotoba へ移動済み (oracle 登録 + .kir.edn 配布 + host oracle/call 実行)」
を REFUTED — 抽出 wiring がコアごとに不統一、bot_bounds / bot_slo は「未配布 replica」のまま**: 
HEAD = origin/main = origin/HEAD = **a97bf59** (rev-parse 実測、falsify-77 の e2e3bd8 から経済 4 commit
前進: a97bf59 codex/kotoba-topo-record merge / 150d4cb usdc-business-funding / 5911e4c provider-retry
output-budget-exhausted 抽出 / e61d929 business capital views)、本体 porcelain clean (dirty 0)、
git ls-tree 実測 **src 243 / test 246** (falsify-77 の e2e3bd8 242/245 から +1/+1、provider-retry コア
+ parity test 相当)。**.kotoba 抽出の wiring を oracle で実測** — (1) provider-retry-core は
kotoba_oracle.cljc cores マップ登録済み + 配布 resources/cloud/itonami/app/oracle/
provider-retry-core.kir.edn 実在 + host provider_retry.cljc:172 `(oracle/call :provider-retry-core ...)`
と **3 点揃う authority 移動済み**; (2) bot_bounds_core / bot_slo_latency_core / bot_slo_recovery_core /
bot_slo_stability_parts_core は src に .kotoba + parity test のみで、oracle cores 未登録 (grep
`bounds|bot-slo|slo` ヒット無し) + oracle/ 配布 .kir.edn 全 19 本に該当なし + host
bot_bounds.cljc / bot_slo.clj の oracle 参照 **0** 行 = parity 付 checked replica のまま authority は
host に残存。同一抽出キャンペーン内で wiring 水準が不揃いで、blanket 主張は偽 → REFUTED、正確な
範囲は「provider-retry のみ移動、bot_bounds/bot_slo は replica 段階」に精緻化。**build-break は新
HEAD でも生存確認** (identity.clj (:import = 0 / 裸 [java.* 5 本 29-33 行、kotoba-net deps.edn = 0、
grep 実測 — falsify-77 の結論を 1 HEAD 前進後に再実測)。実装 軸 score は **3 のまま** (質的反証 +
範囲精緻化のみ、green 化測定は両 build-break 修理後)。**file-count 再アンカー**: 実装行「src 242
(head e2e3bd8)」→ **243 (head a97bf59)**、テスト行「test 245 (e2e3bd8)」→ **246 (head a97bf59)**。
次の 1 アクション: Tier 2 report で「bot_bounds / bot_slo 各コアの oracle 登録 + .kir.edn 配布 +
host oracle/call の 3 点欠如 (replica のまま)」を提出。builder (a)(b)(c) 3 値 wiring 記録方式、
build-break 修理 ((B) identity.clj :import 復元 + (A) deps.edn kotoba-net 宣言) 着地後には oracle/call
が実コアを実行するかを runtime で検証。附帯: 本体 checkout は反復中 porcelain clean (dirty 0)、本 bot
は touch せず wt-msloop のみで完結、compile は build-break のため実行せず git object 静的検証のみ。
**HEAD 解決の注意点 (falsify-78 判明)**: wt-msloop の HEAD は ledger branch (bot/maturity-sim-loop =
3f48fd9) 自身であり、本体 src/test の計数・tree 参照は **origin/main (a97bf59) で行うこと** (HEAD で
引くと非正確)。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま
(operator 復旧待ち、append-only で本 bot は編集しない)。
(evidence/2026-09-08-falsify-78.md)
## NEXT (falsify-79 追記、append-only)

**falsify-79 (2026-09-08、実装 軸) で「bot_bounds / bot_slo の 4 抽出コア全ての parity テストが host の実行実装へ結合する checked replica で、結合の質がコア間で均一」を REFUTED — parity 結合水準は 3 段に不揃い (latency/recovery = live host var 結合 / bounds = test-local re-spec とのみ比較 / parts = host 結合なし)**:
HEAD = origin/main = origin/HEAD = **d5d6e60** (rev-parse 実測、falsify-78 の a97bf59 から PR #289 codex/mobile-capital-ux merge 1 本前進。git diff --stat a97bf59..d5d6e60 は shared/bots-ui の 3 cljs/cljc のみ変更で、本反復の測定面 bot_bounds/bot_slo/kotoba の src/test は不変。src 243 / test 246 は falsify-78 実測と同値で git ls-tree 再アンカー)。本体 porcelain clean (dirty 0)。**4 抽出コアの parity テストの host 結合を test/cloud/itonami/app の実パスで git show 実測** (falsify-78 の next action「bot_bounds / bot_slo 各コアの oracle 登録/.kir.edn 配布/host oracle/call の 3 値記録」を parity 結合水準の次元まで精緻化して執行):
- **bot_slo_latency**: parity テストは (:require に cloud.itonami.app.bot-slo :as slo、host 参照 = `(#'…/latency-points p90)`) — **live host var を #' deref で機械結合**し、.kotoba guest と同一 corpus を比較。host の boundary が drift すれば決定論的に赤くなる真の双実装 parity として健在。
- **bot_slo_recovery**: 同様に `(#'…/recovery-points stale)` へ live #' 結合。
- **bot_bounds**: parity テストの :require は clojure.string / clojure.test / kotoba.compiler.core / kotoba.kir のみで **bot-bounds 非要求**、host 側は test ローカルに再記述した `host-code` の cond (has-budget/spent/budget の 3 スカラー)。実行中の bot_bounds.cljc/admit-spend ([b history] 入力、window/unreported を含む spent 計算) へ一切結合せず、docstring の「against the host's rule」は test 内再記述と現実が乖離 — host の '< vs <=' 境界や spent/window 意味論が drift しても、.kotoba と test-local re-spec が一致し続ける限り parity は PASS を返し続け drift 無検出。
- **bot_slo_parts**: docstring 自認どおり host に私有 fn なし (evaluate に inline) で host var へ結合不能。.kotoba limbs を test 内公式 (availability 18/10、completion 0.3*rate、observability 0.07*cov) に pin する guest-only — host の evaluate の inline 数式が drift しても parts parity は検出しない (完全無検出ではない: 専用 bot_slo_test が assembled stability を覆う)。

よって falsify-78 の「bot_bounds / bot_slo は parity 付 checked replica (authority は host)」の blanket 記述のうち parity の**結合質**は均一でなく、正確な範囲は「latency/recovery: live #' 結合の checked replica / parts: host 結合なき guest-only formula pin / bounds: test-local re-spec とのみ比較 (host 実装 drift を検出不能)」に精緻化される。score は **3 のまま** (質的反証 + 結合水準の次元追加のみ。green 化測定は両 build-break 修理後のため不可、score 変動なし)。**次の 1 アクション / Tier 2 report (kanban/human、本体 checkout 編集を要し Tier 1 不着地)**: bot_bounds parity テストを test-local host-code 再記述から live host fn 駆動へ張り替える修理案 — `(#'bounds/admit-spend b h)` を小さな adapter (:reason → 0/1/2 変換) で .kotoba の :i64 結果と等値比較するよう改め、host の boundary/spent/window 意味論が drift した時に parity が決定論的に赤くなるようにする。parts も同趣旨: evaluate の inline limbs を host 私有 fn へ抽出して #' 結合可能にすると host 側 drift も検出可。実装 軸の .kotoba 抽出評価に「parity 結合水準 (d)」を第 4 次元として追記する方式を提案 ((a) oracle 登録 (b) .kir.edn 配布 (c) host oracle/call に加え、(d) parity テストの live #' 結合 / test-local re-spec / host 結合なし の 3 値)。build-break 修理 ((B) identity.clj :import 復元 + (A) deps.edn kotoba-net 宣言) 着地後、上記 parity 結合の runtime 実行 (compile + テスト緑) を検証可能。附帯: 本体 checkout は反復中 porcelain clean (dirty 0)、本 bot は touch せず wt-msloop / bot/maturity-sim-loop で完結。compile は build-break のため実行せず git object 静的検証のみ。HEAD 解決は falsify-78 判明どおり origin/main (d5d6e60) で計数。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、append-only で本 bot は編集しない)。identity.clj malformation / kotoba-net 未宣言は falsify-63..78 と同一で継続。
(evidence/2026-09-08-falsify-79.md)## NEXT (falsify-80 追記、append-only)

**falsify-80 (2026-09-08、実装 軸) で「PR #289..#291 の funding/yield merge 前進 (d5d6e60→
2588c51) により (i) falsify-79 の .kotoba parity 結合水準 3 段が変化、または (ii) falsify-63..
79 の 2 build-break が修理着地」を REFUTED (ii) — 但し SURVIVED (i)**: HEAD = origin/main =
origin/HEAD = **2588c51** (rev-parse 実測、falsify-79 の d5d6e60 から PR #290 funding-filter +
PR #291 yield-funded-rounds の 2 merge 前進、本体 checkout は 65d3e98 (#290) に detach)。
**(i) 測定面不変 (SURVIVED)**: `git diff d5d6e60..2588c51 --name-only` は bots-ui 3 ファイル
(capital_client.cljs / capital_ui.cljc / public_bots.cljc) のみで、実装軸の .kotoba parity 測定面
(bot_bounds/slo 4 core + parity test) は byte 無変化 → falsify-79 の結合水準 3 値 (latency/
recovery = live #' / bounds = test-local re-spec / parts = host 結合なし) は現 HEAD でも生存。
**(ii) build-break 生存 (REFUTED)**: `git show origin/main:.../identity.clj` で `(:import` opener **0**
/ 裸 `[java.*` ベクタ **5** (行 29-33) 残存 (falsify-63..79 と完全一致)、deps.edn `kotoba-net` grep **0**
/ `jvm-host` **0**、http_client.clj:16 が `kotoba.net.jvm-host` を require 継続。**一次 compile を
独立 worktree /private/tmp/mt-msloop80 (head 2588c51) で直接実行** → `Syntax error macroexpanding
clojure.core/ns at (cloud/itonami/app/identity.clj:1:1)` / `java.nio.charset - failed: #{:refer-clojure}`
EXIT_RC=1 (falsify-63/64/68/77 と同一エラーを現 HEAD で独立再現)。**file-count 再アンカー**: 実装行
「src 243 (head a97bf59)」→ **243 (head 2588c51)**、テスト行「test 246 (head a97bf59)」→ **246
(head 2588c51)** (git ls-tree 実測)。実装軸 score は **3 のまま** (funding merges は測定面非接触で
score 変動なし、build-break 継続は既知赤の再確認。green 化測定は両 build-break 修理後のため不可)。
修理案 Tier 2 (kanban/human、falsify-68/75/76/77/78/79 と不変): (B) identity.clj の 5 裸ベクタを
`(:import ...)` で wrap + (A) deps.edn への kotoba-net 宣言 → 両着地後のフル suite green を最終
終点に、falsify-62 の bundle/graph 赤 (published-lock 再発行) もその後再確認。次反復候補: spec 軸
で funding/yield merge の commands.edn 契約突合、または falsify-79 の Tier 2 report (bot_bounds
parity 張り替え案) を kanban/human へ正式提出。附帯: 本体 checkout は反復中 porcelain clean (dirty
0)、dev /health 127.0.0.1:1338 -> 200 実測、本 bot は touch せず wt-msloop / bot/maturity-sim-loop
で完結、実行 worktree mt-msloop80 は remove --force / prune 済みで `git worktree list` に残存せず。
7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、
append-only で本 bot は編集しない)。identity.clj malformation / kotoba-net 未宣言は falsify-63..79
と同一で継続。(evidence/2026-09-08-falsify-80.md)
**falsify-82 (2026-09-08、実装 軸) で「merge 前進 6473f6b→e2b39ba (PR #293 safe-capital +
HumanWork page-title .kotoba 移行 2 merge) が (1) 新規 .kotoba 抽出を falsify-78/79 の枠組みに沿い
oracle 未登録・.kir.edn 無し・host oracle-ref 0 のまま (bot_slo 型 blanket 拡張)、または (2) 2
build-break を修理着地」を REFUTED (1-α と 2) / 但し SURVIVED (1-β と既存結合面)**: HEAD =
origin/main = **e2b39ba** (git fetch 実測 `6473f6b..e2b39ba main`、`git rev-parse origin/main =
e2b39ba9dea6e18d29b1f7e659923c3d0d58446a`)。**(1-β, SURVIVED = 質のデータ点)**: 新規抽出
human-work-marketplace-core は **oracle 配線済み** — kotoba_oracle.cljc の decision-core-src に
「:human-work-marketplace-core」追加 / `resources/.../oracle/human-work-marketplace-core.kir.edn`
**同梱** / host `human_work_marketplace.clj` が `(oracle/call :human-work-marketplace-core
'pick-brand-name …)` と `(needs-escape? …)` の **2 本 host oracle 呼び出し** (host oracle-ref > 0)
/ parity テスト 116 行 (6 deftest) が or-default・escape-map 突合 + **同梱 KIR を end-to-end で
通す** deftest + nil 契約行 + native/portable 全 compile 担保 → **provider-retry-core に続く 2
個目の正しく配線された新規抽出**で、falsify-78/79 の「結合一様でない」枠組みは生存しむしろ
強化。(1-α, REFUTED): 「新規抽出 = host-authority blanket」は不成立。**(2, REFUTED)**:
identity.clj (:import 0 / 裸 [java.* 5 ベクタ 行 29-33) と deps.edn kotoba-net (grep 0) が現 HEAD
git object でも残存、falsify-68 の修理着地判定 3 条件未達。**file-count 再アンカー**: 実装行
「src 243 (head 6473f6b、falsify-81)」→ **244 (head e2b39ba、falsify-82)** (+1 = new core)、
テスト行「test 246」→ **247** (+1 = new parity test) (git ls-tree 実測)。実装軸 score は **3 の
まま** (build-break 継続で green 化測定不可、新規抽出の oracle 配線は質的肯定だが score は
green 化でしか上がらない)。修理案 Tier 2 (kanban/human、falsify-68/75/76/77/78/79/80/81 と不変):
(B) identity.clj の 5 裸ベクタを (:import …) で wrap / ns 形式復元、(A) deps.edn への kotoba-net
宣言 → 両着地後のフル suite green を最終終点に。次反復候補: bot_bounds / bot_slo を
provider-retry / human-work と同水準 (shipped .kir.edn + oracle 登録 + host call) へ引き上げる
Tier 2 修理案を kanban/human へ正式提出、又は origin/main 前進継続確認。附帯: 本体 checkout
(worktree list `/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami/cloud-itonami-app`)
は detached HEAD 65d3e98 (#290)、`git status --porcelain` 0 行 = porcelain clean、dev /health
127.0.0.1:1338 -> **200** 実測。本 bot は touch せず wt-msloop / bot/maturity-sim-loop で完結。
7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま (append-only 規約で
本 bot は編集しない)。identity.clj malformation / kotoba-net 未宣言は falsify-63..81 と同一で
継続。(evidence/2026-09-08-falsify-82.md)

## NEXT (falsify-83 追記、append-only)

**falsify-83 (2026-09-08、実装 軸) で「merge 前進 e2b39ba→c792872 (PR #295 initial-launch-grant + #296 wallet-deposits-independent-of-safe 2 merge) が (1) .kotoba parity 測定面に接触し falsify-78/79/82 の結合水準/配線記録を変化、または (2) 2 build-break を修理着地」を REFUTED (claim 2) / SURVIVED (claim 1)**: HEAD = origin/main = **c792872** (git fetch 実測 `e2b39ba..c792872 main`、`git rev-parse origin/main = c792872f5c6856359821447fcf64f33e714cbae1`)。**(1 変化ファイル 4 本は全て shared/bots-ui**: capital_client.cljs / capital_ui.cljc / public_bots.cljc + 新規 funding_role_test.cljs)。src/・test/・shared/bots-core は `git diff --stat` 空 (byte 無変化)、**新規 .kotoba 抽出・.kir.edn 配布なし** (`git diff -- '*.kotoba' '*.kir.edn'` 空) → falsify-78/79 の 3 値結合水準 (latency/recovery = live #' / bounds = test-local re-spec / parts = host 結合なし) と falsify-82 の配線済み抽出 2 個は現 HEAD でも生存 (SURVIVED)。**(2 build-break 未着地 (REFUTED)**: identity.clj `:import` opener **0** / 裸 `[java.*` ベクタ **5** (行 29-33)、byte 完全同一 (rev-parse e2b39ba:cvd 同 `3d90156e`)、deps.edn kotoba-net **0** (byte 完全同一 `5a36739b`)が git object で残存。**一次 compile を独立 worktree /private/tmp/mt-msloop83 (head c792872、本体未 touch) で直接実行** → `Syntax error macroexpanding clojure.core/ns at (cloud/itonami/app/identity.clj:1:1)` / `java.nio.charset - failed: #{:refer-clojure ... :import}` **EXIT_RC=1** — falsify-63/64/68/77/80 と同一エラーを新 HEAD で独立再現 (falsify-68 修理着地判定 3 条件は 1/3 充足)。**file-count 再アンカー**: src **244 / test 247 (head c792872、falsify-83 実測)** — falsify-82 (e2b39ba) と同値、bots-ui 新テストは repo-root src/・test/ 計数外。実装軸 score は **3 のまま** (build-break 継続で green 化測定不可不変、測定面非接触で score 変動なし)。修理案 Tier 2 (kanban/human、falsify-68/75/76/77/78/79/80/81/82 と不変): (B) identity.clj 5 裸ベクタを (:import …) で wrap、(A) deps.edn への kotoba-net 宣言 → 両着地後フル suite green を最終終点に、falsify-62 の bundle/graph 赤もその後再確認。次反復候補: origin/main 前進継続確認、又は bot_bounds / bot_slo を provider-retry / human-work と同水準 (.kir.edn + oracle 登録 + host call) へ引き上げる Tier 2 修理案を kanban/human へ正式提出。附帯: 本体 checkout (worktree list `/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami/cloud-itonami-app`) は detached HEAD 65d3e98 (#290、origin/main c792872 より 2 merge 遅れ)、porcelain clean (dirty 0)、dev /health 127.0.0.1:1338 -> **200** 実測。本 bot は touch せず wt-msloop / bot/maturity-sim-loop で完結。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま (append-only 規約で本 bot は編集しない)。(evidence/2026-09-08-falsify-83.md)

## NEXT (falsify-84 追記、append-only)

**falsify-84 (2026-09-08、実装 軸) で「merge 前進 c792872→d226614 (PR #297 fix: synchronize native datetime input events、単一 merge) が (1) .kotoba parity 測定面に接触し falsify-78/79/82/83 の結合水準/配線記録を変化、または (2) 2 build-break を修理着地」を REFUTED (claim 2) / SURVIVED (claim 1)**: HEAD = origin/main = **d226614** (git fetch 実測 `c792872..d226614 main`、`git rev-parse origin/main = d2266149f9b4b350a2caba3ab6717fce3281b3c4`、`git rev-list --count c792872..origin/main` = 1)。**(1 変化ファイル 1 本は shared/bots-ui/capital_ui.cljc のみ** (`git diff --name-only c792872 d226614` = 単一 cljc)。src/・test/・shared/bots-core は無変化、**新規 .kotoba 抽出・.kir.edn 配布なし** → falsify-78/79 の 3 値結合水準 (latency/recovery = live #' / bounds = test-local re-spec / parts = host 結合なし) と falsify-82 の配線済み抽出 2 個、falsify-83 の「bots-ui 表示面のみ非接触」実測は現 HEAD d226614 でも生存 (SURVIVED)。**(2 build-break 未着地 (REFUTED)**: identity.clj `:import` opener **0** / 裸 `[java.*` ベクタ **5** (行 29-33)、blob SHA 完全同一 (rev-parse c792872 と d226614 両 `3d90156e4944...`、`git diff --stat c792872 d226614 -- identity.clj deps.edn` 空)、deps.edn kotoba-net **0** (blob 両 `5a36739b76a6...` byte 同一) → 2 build-break は git object レベルで残存。一次 compile は falsify-83 が c792872 で独立 EXIT_RC=1 (`Syntax error macroexpanding ns ... java.nio.charset :import`) を実測済み、identity.clj blob が d226614 と byte 完全同一ゆえ同 failure は新 HEAD でも決定的に保存される (git object 同一性からの静的帰結、再 compile 不要、falsify-68 修理着地判定 3 条件は 1/3 充足)。**file-count 再アンカー**: src **244 / test 247 (head d226614、falsify-84 実測)** — falsify-83 (c792872) と同値、bots-ui 単一 cljc は repo-root src/・test/ 計数外。実装軸 score は **3 のまま** (build-break 継続で green 化測定不可不変、測定面非接触で score 変動なし)。修理案 Tier 2 (kanban/human、falsify-68/75/76/77/78/79/80/81/82/83 と不変): (B) identity.clj 5 裸ベクタを (:import …) で wrap、(A) deps.edn への kotoba-net 宣言 → 両着地後フル suite green を最終終点に、falsify-62 の bundle/graph 赤もその後再確認。次反復候補: origin/main 前進継続確認 (falsify-76..84 と同型)、又は bot_bounds / bot_slo を provider-retry / human-work と同水準 (.kir.edn + oracle 登録 + host call) へ引き上げる Tier 2 修理案の kanban/human 正式提出。附帯: 本体 checkout (worktree list `/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami/cloud-itonami-app`) は detached HEAD 65d3e98 (#290、origin/main d226614 より遅れ)、`git status --porcelain` 0 行 = porcelain clean (dirty 0、mainrc=0 実測)。作業は wt-msloop / bot/maturity-sim-loop で完結し、本 bot は本体 checkout を一切 touch せず。identity.clj malformation / kotoba-net 未宣言は falsify-63..84 と同一で継続。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま (append-only 規約で本 bot は編集しない)。(evidence/2026-09-08-falsify-84.md)## NEXT (falsify-85 追記、append-only)

**falsify-85 (2026-09-08、運用/滞留 軸) で falsify-33..53 の「滞留は反復ごとに再蓄積する
継続現在形」を REFUTED — main/full/resident 全 refspec の regular unused は 0 へ回帰
(falsify-32 型の 0 ベースと同型)**: origin/main は本反復で前進 0 (git fetch 実測 d226614
不変)、実装軸に新規シグナルが無いため運用軸の滞留/annex 帰属を測定対象に選択。
主 anchor falsify-53 (2026-09-07: main-refspec unused **651 keys / 9.7 GB**) から超越
期間 (実装/governor/spec 軸 3..8 反復) 後の独立時点で `git annex unused` を
cloud-itonami-dns-resolver (4 branch = main / manifest-rev / resident/dns-resolver /
git-annex 実測) で実行 — **3 ベクトル (--used-refspec +refs/heads/main / refspec なし
全 refs / --used-refspec +refs/heads/resident/dns-resolver) いずれも正規 unused **0 keys**、
partial transfer 残骸 14 chunk (186.98 MiB、falsify-35 以降不変) のみ**。local annex keys
は 651 → **786 (+135)** / 11.68 GB へ増加しているのに unused 判定 0 = 増加分は全て参照に
解決し未参照残留が無い。増加源 itonami-app-resident.cljs は **PID 94682 → 70643 へ再起動**
(ps 実測 Mon03PM 起動・稼働継続)、git-annex branch 先端 a925e057f は KIR chunk log 12 件
update で書き込みアクティブ継続、export_and_sync 不在。df `/` avail **163 Gi / 9%** (飽和
なし・100% 標示なし)。**範囲修正 (留保)**: falsify-32→33 が示したとおり 0 ベースは恒久解消
でなく点時刻回帰であり、増加源 (PID 70643) 稼働継続ゆえ再蓄積の可能性は残る — 本 REFUTED
は「継続再蓄積という現在形が常に成り立つ」を点時刻で否定したもの。リスク-5 を「0 ベース回帰
だが増加源稼働継続ゆえ再蓄積の可能性残存」へ再アンカー。運用 軸 score は 3 のまま
(滞留語調の反証 + 0 ベース回帰記録のみ、実緑測定なし)。次の 1 アクション: 次反復で同 3 ベクトル
+ local annex keys + avail を再実測し、0 ベース維持 or 再蓄積再開を判別。附帯 Tier 2
(kanban/human): resident 再起動 (94682→70643) の契機と滞留回収の帰属 (operator 手動
dropunused/世代削除 vs resident 挙動変化) の精査。附帯: 本体 checkout
(/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami/cloud-itonami-app) は
detached HEAD 65d3e98 (#290) / porcelain clean (dirty 0) 実測、本 bot は touch せず
wt-msloop / bot/maturity-sim-loop で完結。identity.clj malformation / kotoba-net 未宣言
(falsify-63..84) は対象外・既知赤継続。7-軸表 16 行目孤立行 placeholder / falsify-46
mid-sentence cut は残存のまま (append-only 規約で本 bot は編集しない)。
(evidence/2026-09-08-falsify-85.md)## NEXT (falsify-86 追記、append-only)

**falsify-86 (2026-09-08、運用/滞留 軸) で falsify-85 の次の 1 アクションを執行し「0 ベースは
falsify-32→33 と同型で 1 反復以内に再蓄積する一時状態」を REFUTED — 0 ベースは少なくとも 1
反復跨ぎで維持 (unused 0 継続・keys 786 不動・avail 163Gi 不変)**: origin/main は本反復で
d226614 のまま前進 0 (git fetch 実測)`、実装軸に新規シグナルが無いため運用軸の falsify-85
次の 1 アクション (「同 3 ベクトル + local annex keys + avail を再実測し 0 ベース維持 or
再蓄積再開を判別」) を測定対象に選択。cloud-itonami-dns-resolver で実測 — 3 ベクトル
(`--used-refspec +refs/heads/main` / refspec なし全 refs / `--used-refspec
+refs/heads/resident/dns-resolver`) いずれも**正規 unused 0 keys** (partial partial 14 chunk
186.98 MiB のみ、falsify-35 以降不変)、ok / EXIT_RC=0 各実測。local annex keys は **786 →
786 (増加 0)** / 11.68 GB 不変 — 滞留蓄積期 (falsify-39..49) は反復あたり +24/+25 で単調増加
したのに対し増分ゼロ、再蓄積を示すいかなる増加も無い。増加源 itonami-app-resident.cljs は
**PID 70643 稼働継続** (falsify-85 と同一、export_and_sync 不在)、df `/` avail **163 Gi / 9%**
(falsify-85 と同値・飽和なし) を実測。falsify-32→33 の exemplar (dropunused 着地後 ~40 分で
+36 keys 再蓄積) とは対照的に、本反復では再蓄積の兆候が厳密にゼロ — 「0 ベース = 短時間で
再蓄積する一時状態」は成り立たず、0 ベースは少なくとも本反復まで維持された実績として生存。
**範囲修正 (留保)**: 0 ベースの恒久性は保証されておらず (増加源 PID 70643 稼働継続)、本
REFUTED は「never re-accumulate」を主張しない — 次反復以降も同ベクトルを再実測し、0 ベース
維持の複数反復持続 (維持なら「回収は恒久化傾向へ」精緻化) or 再蓄積再開 (falsify-33..53 語調へ
復帰) を判別。リスク-5 を「0 ベースへ回帰し少なくとも 1 反復跨ぎで維持、増加源稼働継続ゆえ
再蓄積の可能性残留」へ再アンカー。運用 軸 score は **3 のまま** (滞留の一時状態説を点時刻で
反証 + 0 ベース維持の記録のみ、実緑測定なし — score は実緑測定でのみ上昇する規約)。附帯
Tier 2 (kanban/human、falsify-85 と不変): resident 再起動 (94682→70643) の契機と滞留回収の
帰属 (operator 手動 dropunused/世代削除 vs resident 挙動変化) の精査継続。附帯: 本体 checkout
(orgs/cloud-itonami/cloud-itonami-app) は detached HEAD 65d3e98 (#290) / porcelain clean
(dirty 0) 実測、本 bot は touch せず wt-msloop / bot/maturity-sim-loop で完結。identity.clj
malformation / kotoba-net 未宣言 (falsify-63..84) は対象外・既知赤継続。7-軸表 16 行目孤立行
placeholder / falsify-46 mid-sentence cut は残存のまま (append-only 規約で本 bot は編集しない)。
(evidence/2026-09-08-falsify-86.md)## NEXT (falsify-87 追記、append-only)

**falsify-87 (2026-09-08) で 0 ベースの 2 反復連続維持を確認 (SURVIVED)**: 3 ベクトル
(main / 全 refs / resident-refspec) の正規 unused は **全て 0 のまま** (partial 14 chunk のみ、
falsify-35 以降不変)、local annex keys は **786 → 786 (増加 0、falsify-85→86→87 の 3 反復連続
維持)**、size 11.68 GB 不変、df avail は **163Gi → 162Gi / 10%** (概ね不変、飽和なし)。
増加源 itonami-app-resident.cljs **PID 70643 稼働継続** (falsify-85/86 と同一 PID、再起動なし)、
export_and_sync 不在。「0 ベースは any-iteration で再蓄積する一時状態」説は生存せず (SURVIVED)、
falsify-86 が指定した「複数反復維持なら恒久化傾向へ精緻化」の条件を満たす — 回収は
恒久化傾向へ精緻化。ただし増加源稼働継続のため恒久性は保証されず、次反復で 3 反復超の
連続維持 (恒久化の兆し) or 再蓄積再開を判別。附帯: origin/main は d226614 のまま前進 0
(実装軸 新規シグナルなし)、本体 checkout detached 65d3e98 porcelain clean (dirty 0)、
identity.clj/kotoba-net 既知赤継続、7-軸表 16 行目孤立行 placeholder 残存
(evidence/2026-09-08-falsify-87.md)。
## NEXT (falsify-88 追記、append-only)

**falsify-88 (2026-09-08、運用/滞留 軸) で falsify-87 の次の 1 アクションを執行し「0 ベースは
3 反復を跨ぐと再蓄積する一時状態 (falsify-32→33 exemplar)」を REFUTED → SURVIVED — 0 ベース
は 3 反復連続維持 (keys 786 不動・新增加 0) で恒久化の兆しへ精緻化**: origin/main は本反復で
d226614 → **b5df93e** (PR #299 operator-launch-flow、変化 1 本 = shared/bots-ui/capital_ui.cljc
+5 行のみ、repo-root src/・test/・shared/bots-core 非接触) に前進した。しかし実装軸の .kotoba
parity 測定面も build-break (identity.clj / kotoba-net) も本 merge に非接触 (git show --stat
実測) のため、新規シグナルは運用軸の falsify-87 次の 1 アクション (「0 ベースが 3 反復超の連続
で維持されるか」判定) を測定対象に選択。cloud-itonami-dns-resolver で実測 — 3 ベクトル
(`--used-refspec +refs/heads/main` / refspec なし全 refs / `--used-refspec
+refs/heads/resident/dns-resolver`) いずれも**正規 unused 0 keys** (partial 14 chunk / 186.98
MiB のみ、falsify-35 以降不変、`ok`/EXIT_RC=0 各実測)。local annex keys は **786 → 786 (増加
0)** / 11.68 GB 不変 — 滞留蓄積期 (falsify-39..49、反復あたり +24/+25 単調増加) と対照的に増分
ゼロの状態が **falsify-85→86→87→88 の 4 反復観測 / 3 反復連続維持**で続く。増加源
itonami-app-resident.cljs は **PID 70643 稼働継続** (falsify-85/86/87 と同一、export_and_sync
不在)、df `/` avail **156 Gi / 10%** (falsify-87 の 162Gi から -6Gi 微減、飽和なし)。falsify-87
指定の閾値「3 反復超の連続維持 → 恒久化の兆しへ精緻化」を本反復が充足 → 回収は恒久化の兆し
ステージへ進んだ (ただし増加源稼働継続ゆえ恒久性は保証されず「never re-accumulate」は主張
しない)。運用 軸 score は **3 のまま** (0 ベース維持の継続化を記録、実緑測定なし)。次の 1
アクション: 次反復で同ベクトル + keys + avail を再実測し 0 ベースの **4 反復超の連続持続** or
再蓄積再開を判別、あわせて avail 微減 (-6Gi) の帰属を観測。附帯 (実装軸 次反復シグナル):
origin/main 前進 b5df93e を次反復で実装軸として再確認 (parity 測定面 / build-break 生存は本
反復で非接触を確認済み)。附帯 Tier 2 (falsify-85/86/87 と不変): resident 再起動 (94682→70643)
の契機と滞留回収の帰属の精査は kanban/human 提出継続。附帯: 本体 checkout は detached HEAD
65d3e98 (#290) / porcelain clean (dirty 0)、本 bot は touch せず wt-msloop / bot/maturity-sim-loop
で完結。identity.clj malformation / kotoba-net 未宣言 (falsify-63..84) は b5df93e に非接触で
既知赤継続。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま
(operator 復旧待ち、本 bot は編集しない)。(evidence/2026-09-08-falsify-88.md)

## NEXT (falsify-89 追記、append-only)

**falsify-89 (2026-09-08、運用/滞留 軸) で falsify-88 の次の 1 アクションを執行し「0 ベースは
4 反復を跨ぐと再蓄積する一時状態 (falsify-32→33 exemplar)」を REFUTED → 0 ベースは
4 反復超の連続維持 (5 時点観測) で「恒久化傾向」へ精緻化**: origin/main は本反復で
b5df93e → **52c156c** (migration vf-calibrate bucket decision -> vf_calibrate_core.kotoba、
ADR-2609081400、変化 7 本 / +299) へ前進した。この merge は falsify-88 の bots-ui 単一 cljc
と対照的に **repo-root src/・test/・.kotoba parity 測定面を直接接触**する (新規
vf_calibrate_core.kotoba 54 行・.kir.edn 79 行・vf_calibrate_kotoba_parity_test.clj 新設
105 行、repo-root src 3 本変更)。実装軸への新規シグナルが生じたため、本反復の主要測定は
運用軸の falsify-88 次の 1 アクション (「0 ベースが 4 反復超の連続で維持されるか」判定・
恒久化傾向への精緻化) を選択し、実装軸シグナル (52c156c の parity 測定面・build-break) は
次反復の検証対象として附帯記録した。cloud-itonami-dns-resolver で実測 — 3 ベクトル
(--used-refspec +refs/heads/main / refspec なし全 refs / --used-refspec
+refs/heads/resident/dns-resolver) いずれも**正規 unused 0 keys** (partial 14 chunk /
186.98 MiB のみ、falsify-35 以降不変、ok/EXIT_RC=0 各実測)。local annex keys は **786 →
786 (増加 0)** / 11.68 GB 不変 — 滞留蓄積期 (falsify-39..49、反復あたり +24/+25 単調増加) と
対照的に増分ゼロの状態が **falsify-85→89 の 5 時点観測 / 4 反復連続維持**で続く。増加源
itonami-app-resident.cljs は **PID 70643 稼働継続** (falsify-85/86/87/88 と同一 PID、
export_and_sync 不在)、df / avail **142 Gi / 11%** (falsify-88 の 156Gi から -14Gi 減少、
annex available local disk 167.66→152.87 GB と同方向、飽和なし)。falsify-88 指定の閾値
「4 反復超の連続維持 → 恒久化傾向へ精緻化」を本反復が充足 → 回収は恒久化傾向ステージへ
進んだ (ただし増加源稼働継続ゆえ恒久性は保証されず「never re-accumulate」は主張しない)。
運用 軸 score は **3 のまま** (0 ベース維持の継続化・恒久化傾向への精緻化を記録、実緑測定
なし)。次の 1 アクション: 次反復で同ベクトル + keys + avail を再実測し 0 ベースの **5 反復超
の連続持続** or 再蓄積再開を判別、あわせて avail の 2 連続減少 (162→156→142 Gi / annex
167.66→152.87 GB) の帰属を観測。附帯 (実装軸 次反復シグナル): **52c156c は parity 測定面を
直接接触する merge** — 次反復で実装軸として、52c156c の .kotoba parity 測定面 (falsify-78/79/
82/83 の 3 値結合水準・配線記録) の変化と 2 build-break (identity.clj / kotoba-net) の生存を
再確認する (前反復までと異なり非接触ではない)。附帯 Tier 2 (falsify-85/86/87/88 と不変):
resident 再起動 (94682→70643) の契機と滞留回収の帰属の精査は kanban/human 提出継続。附帯:
本体 checkout は detached HEAD 65d3e98 (#290) / porcelain clean (dirty 0)、本 bot は touch
せず wt-msloop / bot/maturity-sim-loop で完結。identity.clj malformation / kotoba-net 未宣言
(falsify-63..84) は既知赤継続 (52c156c の build-break 接触は本反復の運用軸測定の範囲外・
次反復の実装軸再確認事項)。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut は
残存のまま (operator 復旧待ち、本 bot は編集しない)。(evidence/2026-09-08-falsify-89.md)

## NEXT (falsify-90 追記、append-only)

**falsify-90 (2026-09-08、運用/滞留 軸) で falsify-89 の次の 1 アクションを執行し「0 ベースは
5 反復を跨ぐと再蓄積する / avail は 3 連続で減少する」を REFUTED → 0 ベースは 5 反復超の
連続維持 (6 時点観測) で恒久化傾向を更に精緻化・avail は 142→159 Gi へ反発 (減少トレンド不成立
)**: origin/main は本反復で 52c156c → **e91bf0f** (migration vf-authority plane admission
decision -> vf_authority_core.kotoba、ADR-2609081600、変化 7 本 / +310) へ再前進した。これは
falsify-89 が実装軸シグナルとした 52c156c (vf-calibrate bucket -> .kotoba) に続く **同種 kotoba
parity 測定面 migration の 1 反復内再発** (新規 vf_authority_core.kotoba 50 行・
vf-authority-plane-admission-core.edn 82 行・vf_authority_kotoba_parity_test.clj 新設 101 行、
repo-root src 3 本変更) — .kotoba・parity_test 分量が反復ごとに増えている。本反復の主要測定は
運用軸の falsify-89 次の 1 アクション (「0 ベースが 5 反復超の連続で維持されるか」判定・恒久化
傾向の更なる精緻化+avail 減少トレンドの帰属) を選択し、実装軸シグナル (e91bf0f の parity 測定
面・build-break) は次反復の検証対象として附帯記録した。cloud-itonami-dns-resolver で実測 — 3
ベクトル (--used-refspec +refs/heads/main / refspec なし全 refs / --used-refspec
+refs/heads/resident/dns-resolver) いずれも**正規 unused 0 keys** (partial 14 chunk / 186.98
MiB のみ、falsify-35 以降不変、ok/EXIT_RC=0 各実測)。local annex keys は **786 → 786 (増加 0)**
/ 11.68 GB 不変 — 滞留蓄積期 (falsify-39..49) と対照的に増分ゼロの状態が **falsify-85→90 の 6
時点観測 / 5 反復連続維持**で続く。増加源 itonami-app-resident.cljs は **PID 70643 稼働継続**
(falsify-85..89 と同一 PID、export_and_sync 不在)、df / avail **159 Gi / 10%** (falsify-89 の
142Gi から +17Gi 反発、annex available local disk 152.87→170.11 GB と同方向、飽和なし —
falsify-89 の 2 連続減少 162→156→142 Gi はトレンドではなかった (162→156→142→159 の振動))。
falsify-89 指定の閾値「5 反復超の連続維持 → 恒久化傾向の更なる精緻化」を本反復が充足 (ただし
増加源稼働継続ゆえ恒久性は保証されず「never re-accumulate」は主張しない)。avail の 2 連続減少
トレンドは REFUTED — 滞留量 (keys 786) 不変下の ±17Gi 振動は annex 滞留ではなく外部書き込みに
帰属するのが整合的。運用 軸 score は **3 のまま** (0 ベース維持の継続化・恒久化傾向の精緻化を
記録、実緑測定なし)。次の 1 アクション: 次反復で同ベクトル + keys + avail を再実測し 0 ベースの
**6 反復超の連続持続** or 再蓄積再開を判別、あわせて avail 振動 (162→156→142→159 Gi) の帰属
(滞留不変下の外部書き込み源) を観測。附帯 (実装軸 次反復シグナル): **e91bf0f は 52c156c に続く
2 本目の parity 測定面直接接触 merge** — 次反復で実装軸として、e91bf0f の .kotoba parity 測定面
(falsify-78/79/82/83 の 3 値結合水準・配線記録) の変化と 2 build-break (identity.clj / kotoba-net)
の生存を再確認する。附帯 Tier 2 (falsify-85/86/87/88/89 と不変): resident 再起動 (94682→70643)
の契機と滞留回収の帰属の精査は kanban/human 提出継続。附帯: 本体 checkout は detached HEAD
65d3e98 (#290) / porcelain clean (dirty 0)、本 bot は touch せず wt-msloop / bot/maturity-sim-loop
で完結。identity.clj malformation / kotoba-net 未宣言 (falsify-63..84) は既知赤継続 (e91bf0f の
build-break 接触は本反復の運用軸測定の範囲外・次反復の実装軸再確認事項)。7-軸表 16 行目孤立行
placeholder / falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、本 bot は編集しない)。
(evidence/2026-09-08-falsify-90.md)

## NEXT (falsify-91 追記、append-only)

**falsify-91 (2026-09-08、運用/滞留 軸) で falsify-90 の次の 1 アクションを執行し「0 ベースは
6 反復を跨ぐと再蓄積する / avail は 150 Gi 未満へ続落する」を REFUTED → 0 ベースは 6 反復超の
連続維持 (7 時点観測) で恒久化傾向を更に精緻化・avail は 159 Gi で安定**: origin/main は本反復で
前進 0 (e91bf0f のまま、falsify-90 と同一 SHA) のため、実装軸に新規シグナルが無く、運用軸の
falsify-90 次の 1 アクション (「0 ベースが 6 反復超の連続で維持されるか」判定・恒久化傾向の更なる
精緻化+avail 振動の帰属) を測定対象に選択し、実装軸シグナル (e91bf0f の parity 測定面・
build-break 生存) は次反復の検証対象として附帯記録した (falsify-90 附帯の指定を繰り越し)。
cloud-itonami-dns-resolver で実測 — 3 ベクトル (--used-refspec +refs/heads/main / refspec なし
全 refs / --used-refspec +refs/heads/resident/dns-resolver) いずれも**正規 unused 0 keys**
(partial 14 chunk / 186.98 MiB のみ、falsify-35 以降不変、ok/EXIT_RC=0 各実測)。local annex keys
は **786 → 786 (増加 0)** / 11.68 GB 不変 — 滞留蓄積期 (falsify-39..49) と対照的に増分ゼロの
状態が **falsify-85→91 の 7 時点観測 / 6 反復連続維持**で続く。増加源 itonami-app-resident.cljs
は **PID 70643 稼働継続** (falsify-85..90 と同一 PID、export_and_sync 不在)、df / avail
**159 Gi / 10%** (falsify-90 の 159 Gi と同値・安定・飽和なし、annex available 170.11→170.65 GB
微増)。falsify-90 指定の閾値「6 反復超の連続維持 → 恒久化傾向の更なる精緻化」を本反復が充足
(ただし増加源稼働継続ゆえ恒久性は保証されず「never re-accumulate」は主張しない)。avail の続落
は不成立 (159 Gi で安定、falsify-89 の 162→156→142 減少トレンドは falsify-90 と同様に REFUTED
維持、滞留不変下の外部書き込み振動の帰属が整合的)。運用 軸 score は **3 のまま** (0 ベース維持
の恒久化傾向の更なる精緻化を記録、実緑測定なし)。次の 1 アクション: 次反復で同 vector + keys +
avail を再実測し 0 ベースの **7 反復超の連続持続** or 再蓄積再開を判別 (7 反復超維持なら恒久化
傾向を更に精緻化、再開なら falsify-33..53 語調へ復帰)、あわせて avail (159 Gi 安定) の帰属 —
滞留 (keys 786) 不変下の外部書き込み源を観測。附帯 (実装軸 次反復シグナル): origin/main は
e91bf0f のまま前進 0 — falsify-90 附帯が指定した「e91bf0f の .kotoba parity 測定面 (falsify-78/79/
82/83 の 3 値結合水準・配線記録) の変化と 2 build-break (identity.clj / kotoba-net) の生存再確認」
を次回の実装軸反復で執行する。附帯 Tier 2 (falsify-85..90 と不変): resident 再起動 (94682→70643)
の契機と滞留回収の帰属の精査は kanban/human 透過提出継続。附帯: 本体 checkout は detached HEAD
65d3e98 (#290) / porcelain clean (dirty 0)、本 bot は touch せず wt-msloop / bot/maturity-sim-loop
で完結。identity.clj malformation / kotoba-net 未宣言 (falsify-63..84) は既知赤継続。7-軸表
16 行目孤立行 placeholder / falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、本 bot
は編集しない)。(evidence/2026-09-08-falsify-91.md)
MD
echo "append rc=$?" > /tmp/f91append.txt; wc -l "$F" >> /tmp/f91append.txt

## NEXT (falsify-92 追記、append-only)

**falsify-92 (2026-09-08、運用/滞留 軸) で falsify-91 の次の 1 アクションを執行し「0 ベースは
連続維持 (8 時点観測) で恒久化傾向を更に精緻化・avail は 158 Gi で安定」**: origin/main は本反復で
e91bf0f → d3ce2cd へ再前進 (connectors scope-implication -> connectors_core.kotoba、
ADR-2609081800、kotoba parity 測定面 migration の 3 反復連続再発、parity_test 134 行) のため、実装軸
シグナルを附帯記録し、運用軸の falsify-91 次の 1 アクション (「0 ベースが 7 反復超の連続で維持されるか」
判定・恒久化傾向の更なる精緻化+avail 帰属) を測定対象に選択。cloud-itonami-dns-resolver で実測 —
3 ベクトル (--used-refspec +refs/heads/main / refspec なし全 refs / --used-refspec
+refs/heads/resident/dns-resolver) いずれも**正規 unused 0 keys** (partial 14 chunk / 186.98 MiB のみ、
falsify-35 以降不変、ok/EXIT_RC=0 各実測)。local annex keys は **786 → 786 (増加 0)** / 11.68 GB 不変
— 滞留蓄積期 (falsify-39..49) と対照的に増分ゼロの状態が **falsify-85→92 の 8 時点観測 / 7 反復連続維持**
で続く (falsify-91 の 7 時点を 1 時点上回る)。増加源 itonami-app-resident.cljs は **PID 70643 稼働継続**
(falsify-85..91 と同一 PID、export_and_sync 不在)、df / avail **158 Gi / 10%** (falsify-91 の 159 Gi から
-1 Gi 極小、飽和なし、annex available 170.65→169.95 GB 極小減)。falsify-91 指定の閾値「7 反復超の連続
持続 → 恒久化傾向の更なる精緻化」を本反復が充足 (ただし増加源稼働継続ゆえ恒久性は保証されず「never
re-accumulate」は主張しない)。avail の続落は不成立 (158 Gi で安定、150 Gi 未満への構造的減少なし、
falsify-89 の 162→156→142 減少トレンドは REFUTED 維持、滞留不変下の外部書き込み振動の帰属が整合的)。
運用 軸 score は **3 のまま** (0 ベース維持の恒久化傾向の更なる精緻化を記録、実緑測定なし)。次の 1
アクション: 次反復で同 vector + keys + avail を再実測し 0 ベースの **8 反復超の連続持続** or 再蓄積再開
を判別 (8 反復超維持なら恒久化傾向を更に精緻化、再開なら falsify-33..53 語調へ復帰)、あわせて avail
(158 Gi 安定) の帰属 — 滞留 (keys 786) 不変下の外部書き込み源を観測。附帯 (実装軸 次反復シグナル):
origin/main は **e91bf0f → d3ce2cd へ再前進** (connectors scope-implication -> connectors_core.kotoba) —
falsify-89/90 に続く同種 kotoba parity 測定面 migration の 3 反復連続再発、parity_test 134 行 (e91bf0f の
101 行より増)。次の実装軸反復で d3ce2cd の parity 測定面 (3 値結合水準・配線記録) の変化と 2 build-break
(identity.clj / kotoba-net) の生存を実測する。附帯 Tier 2 (falsify-85..91 と不変): resident 再起動
(94682→70643) の契機と滞留回収の帰属の精査は kanban/human 透過提出継続。附帯: 本体 checkout は detached
HEAD b5df93e (#299) / porcelain clean (dirty 0)、本 bot は touch せず wt-msloop / bot/maturity-sim-loop
で完結。identity.clj malformation / kotoba-net 未宣言 (falsify-63..84) は既知赤継続。7-軸表 16 行目孤立行
placeholder / falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、本 bot は編集しない)。
**付記 (append-only 規約): maturity.md 末尾 (falsify-91 追記の後) に falsify-91 追記コマンドの残骸と見られる
2 行 (`MD` / `echo "append rc=$?" ...`) が混入 — wc 実測 1238 行、行 1237-1238 が残骸。本 bot は削除・編集
せずその後に追記し、ガバナンス報告対象として kanban/human へ透過報告する。** (evidence/2026-09-08-falsify-92.md)

## NEXT (falsify-93 追記、append-only)

**falsify-93 (2026-09-08、運用/滞留 軸) で falsify-92 の次の 1 アクションを執行し「0 ベースは
連続維持 (9 時点観測) で恒久化傾向を更に精緻化・avail は 160 Gi で安定」**: 運用軸の falsify-92
次の 1 アクション (「0 ベースが 8 反復超の連続で維持されるか」判定・恒久化傾向の更なる精緻化+avail
帰属) を測定対象に選択。cloud-itonami-dns-resolver で実測 — 3 ベクトル (--used-refspec
+refs/heads/main / refspec なし全 refs / --used-refspec +refs/heads/resident/dns-resolver)
いずれも**正規 unused 0 keys** (partial 14 chunk / 186.98 MiB のみ、falsify-35 以降不変、
ok/EXIT_RC=0 各実測)。local annex keys は **786 → 786 (増加 0)** / 11.68 GB 不変 — 滞留蓄積期
(falsify-39..49) と対照的に増分ゼロの状態が **falsify-85→93 の 9 時点観測 / 8 反復連続維持**で
続く (falsify-92 の 8 時点を 1 時点上回る)。増加源 itonami-app-resident.cljs は **PID 70643 稼働
継続** (falsify-85..92 と同一 PID、export_and_sync 不在)、df / avail **160 Gi / 10%** (falsify-92
の 158 Gi から +2 Gi 上方振動、飽和なし、annex available 169.95→171.88 GB 極小増)。falsify-92 指定
の閾値「8 反復超の連続持続 → 恒久化傾向の更なる精緻化」を本反復が充足 (ただし増加源稼働継続ゆえ
恒久性は保証されず「never re-accumulate」は主張しない)。avail の続落は不成立 (160 Gi で安定、150 Gi
未満への構造的減少なし、falsify-89 の 162→156→142 減少トレンドは REFUTED 維持、滞留不変下の外部
書き込み振動の帰属が整合的)。運用 軸 score は **3 のまま** (0 ベース維持の恒久化傾向の更なる精緻化
を記録、実緑測定なし)。次の 1 アクション: 次反復で同 vector + keys + avail を再実測し 0 ベースの
**9 反復超の連続持続** or 再蓄積再開を判別 (9 反復超維持なら恒久化傾向を更に精緻化、再開なら
falsify-33..53 語調へ復帰)、あわせて avail (160 Gi 安定) の帰属 — 滞留 (keys 786) 不変下の外部
書き込み源を観測。附帯 (実装軸 次反復シグナル): origin/main は **d3ce2cd → c618ef3 へ再前進**
(appearance_core.kotoba、ADR-2609082000) — falsify-89/90/92 に続く同種 kotoba parity 測定面
migration の 4 反復連続再発、parity_test 174 行 (d3ce2cd の 134 行より更に増)。次の実装軸反復で
c618ef3 の parity 測定面 (3 値結合水準・配線記録) の変化と 2 build-break (identity.clj / kotoba-net)
の生存を実測する。附帯 Tier 2 (falsify-85..92 と不変): resident 再起動 (94682→70643) の契機と滞留
回収の帰属の精査は kanban/human 透過提出継続。附帯: 本体 checkout は detached HEAD b5df93e (#299) /
porcelain clean (dirty 0)、本 bot は touch せず wt-msloop / bot/maturity-sim-loop で完結。
identity.clj malformation / kotoba-net 未宣言 (falsify-63..84) は既知赤継続。7-軸表 16 行目孤立行
placeholder / falsify-46 mid-sentence cut は残存のまま (operator 復旧待ち、本 bot は編集しない)。
**付記 (append-only 規約): 本反復 wc 実測で maturity.md は行 1272 まで。falsify-92 追記は
(evidence/2026-09-08-falsify-92.md) で終わり、新規残骸行は混入していない。falsify-91 追記コマンドの
残骸 (行 1237-1238: `MD` / `echo "append rc=$?" ...`) は残存のまま (本 bot は編集しない)。**
(evidence/2026-09-08-falsify-93.md)

## NEXT (falsify-94 追記、append-only)

**falsify-94 (2026-09-08、運用/滞留 軸) で falsify-93 の次の 1 アクションを執行し「0 ベースは
10 時点観測 / 9 反復連続維持で恒久化傾向を更に精緻化、ただし avail は 150 Gi 未満へ低下し
falsify-93 の『160 Gi 安定』説を REFUTED」**: 運用軸の falsify-93 次の 1 アクション (「0 ベース
が 9 反復超の連続で維持されるか」判定・恒久化傾向の更なる精緻化+avail 帰属) を測定対象に選択。
cloud-itonami-dns-resolver で実測 — 3 ベクトル (--used-refspec +refs/heads/main / refspec なし
全 refs / --used-refspec +refs/heads/resident/dns-resolver) いずれも**正規 unused 0 keys**
(partial 14 chunk / 186.98 MiB のみ、falsify-35 以降不変、ok/EXIT_RC=0 各実測)。local annex
keys は **786 → 786 (増加 0)** / 11.68 GB 不変 — 滞留蓄積期 (falsify-39..49) と対照的に増分
ゼロの状態が **falsify-85→94 の 10 時点観測 / 9 反復連続維持**で続く (falsify-93 指定の閾値
「9 反復超」を充足)。増加源 itonami-app-resident.cljs は **PID 70643 稼働継続** (falsify-85..93
と同一 PID、export_and_sync 不在)。**avail は新規シグナル**: `df -h /` **160 Gi → 149 Gi
(約 -11 Gi、2 回独立実測で安定)**、annex available local disk space **171.88 → 159.81 GB
(約 -12 GB)** — **150 Gi 未満へ初めて交差して低下 (falsify-93 の「150 Gi 未満への構造的減少
なし」帰属は REFUTED)**。consume は keys 786 / 11.68 GB 不変ゆえ annex 外部の書き込み源に帰属
するが、TCC 遮断 (onedrive / ~/.gftd du 不能) で具体源は特定不能 (operator の du 調査を Tier 2
継続)。低下幅 (-11 Gi) は falsify-90..93 の ±1-2 Gi 振動より大きいため、一過性低位振動
(falsify-88 の 142 Gi 先例) か構造的減少への転じの判別は次反復の再実測を要する。運用 軸 score
は **3 のまま** (0 ベース恒久化傾向の更なる精緻化を記録、avail 低下は監視シグナルとして記録、
実緑測定なし)。次の 1 アクション: 次反復で同 vector + keys + avail を再実測し 0 ベースの
**10 反復超の連続持続** or 再蓄積再開を判別 (10 反復超維持なら恒久化傾向を更に精緻化、再開
なら falsify-33..53 語調へ復帰)、あわせて **avail 149 Gi の続落 (150 Gi 未満への継続低下) vs
回復**を観測 — 低下継続なら structural-decline と確定し risk-5 を強化、回復なら一過性低位振動
と確定。附帯 (実装軸 次反復シグナル): origin/main は **c618ef3 のまま** (falsify-93 から新規
前進なし)。次の実装軸反復では、本反復では対象外とした c618ef3 の parity 測定面 (3 値結合水準・
配線記録) の変化を実測し、2 build-break (identity.clj / kotoba-net) の生存を再確認する。附帯
Tier 2 (falsify-85..93 と不変): resident 再起動 (94682→70643) の契機と滞留回収の帰属、および
本反復の avail -11 Gi 低下の具体源特定 (TCC 遮断) は kanban/human 透過提出継続。附帯: 本体
checkout は detached HEAD b5df93e (#299) / porcelain clean (dirty 0)、本 bot は touch せず
wt-msloop / bot/maturity-sim-loop で完結。identity.clj malformation / kotoba-net 未宣言
(falsify-63..84) は既知赤継続。7-軸表 16 行目孤立行 placeholder / falsify-46 mid-sentence cut
は残存のまま (operator 復旧待ち、本 bot は編集しない)。
**付記 (append-only 規約): 本反復 wc 実測で maturity.md は行 1306 まで。falsify-93 追記は
(evidence/2026-09-08-falsify-93.md) で終わり、新規残骸行は混入していない。falsify-91 追記
コマンドの残骸 (行 1237-1238: `MD` / `echo "append rc=$?" ...`) は残存のまま (本 bot は編集
しない)。**
(evidence/2026-09-08-falsify-94.md)# falsify-95 (2026-09-09, 運用/滞留 軸 — 0 ベースの 10 反復超連続維持 (11 時点観測) で恒久化傾向を更に精緻化、avail の 150 Gi 未満継続で「回復→一過性低位」仮説は REFUTED)

測定日: 2026-09-09 (cron 反復) / 測定者: itonami-maint
作業は sibling worktree (wt-msloop / bot/maturity-sim-loop) で完結。本体 checkout
(orgs/cloud-itonami/cloud-itonami-app) は読み取り専用参照のみ、一切 touch せず。

## 背景 / 測定対象

falsify-94 は 3 ベクトル (main / 全 refs / resident-refspec) の正規 unused 0・local annex
keys 786 不動を falsify-85→94 の 10 時点観測 / 9 反復連続維持で再実測し、0 ベースを
「恒久化傾向の更なる精緻化」へ更新した。その次の 1 アクションは「次反復で同 vector + keys +
avail を再実測し、0 ベース維持の **10 反復超の連続持続** or 再蓄積再開を判別 (10 反復超維持
なら恒久化傾向を更に精緻化、再開なら falsify-33..53 語調へ復帰)、あわせて **avail 149 Gi の
続落 (150 Gi 未満への継続低下) vs 回復**を観測 — 低下継続なら structural-decline と確定し
risk-5 を強化、回復なら一過性低位振動と確定」。あわせて実装軸附帯で「c618ef3 (appearance_core
.kotoba) の parity 測定面の変化と 2 build-break (identity.clj / kotoba-net) の生存の実測」が
指定されていた。

本反復 (falsify-95) はその次の 1 アクションを執行し、falsify-94 が指定した閾値「0 ベースの
**10 反復超の連続持続 → 恒久化傾向の更なる精緻化**」および「avail 149 Gi の続落 vs 回復」
を falsify する。

**主張**: 本反復の再実測で (a) 3 ベクトルのいずれかに正規 unused が再出現する、または
(b) local annex keys が 786 から増加する (10 反復超への持続は成立せず残留再蓄積を開始する)、
または (c) avail が 159-163 Gi 帯へ回復する (falsify-94 の「150 Gi 未満継続」判定が
不成立、一過性低位振動と確定)。

## 実測 (実コマンド出力をリダイレクト、捏造なし)

測定リポジトリ: cloud-itonami-dns-resolver
(`/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami/cloud-itonami-dns-resolver`、
HEAD detached 566928f67、resident branch は `resident/dns-resolver`)。

### 1. main-refspec regular unused

`git annex unused --used-refspec +refs/heads/main` → 正規 unused **0 keys** (出力は partial
transfer 残骸 14 chunk のみ、NUMBER 1..14 の MD5E-s... 14 行)。`ok` / EXIT_RC=0。

### 2. resident-refspec (帰属ベクトル)

`git annex unused --used-refspec +refs/heads/resident/dns-resolver` → 同様に正規 unused
**0 keys**、partial 14 chunk のみ。`ok` / EXIT_RC=0。

### 3. 全 refs 走査 (孤児ベクトル)

`git annex unused` (refspec 指定なし) → 同様に正規 unused **0 keys**、partial 14 chunk
のみ。いかなる ref からも到達不能な孤児 key は存在しない。`ok` / EXIT_RC=0。

### 4. git annex info

```
local annex keys: 786        (falsify-95: 786 — 変化 0、11 時点観測 / 10 反復連続維持)
local annex size: 11.68 gigabytes   (不変)
available local disk space: 158.81 gigabytes (+100 megabytes reserved) (falsify-94: 159.81
                              GB — 約 -1.00 GB 微減、150 Gi 未満のまま)
temporary object directory size: 186.98 megabytes (partial chunk 14、不変)
annexed files in working tree: 7143 / 93.02 gigabytes ([b2] 側、不変)
combined annex size of all repositories: 111.12 gigabytes (不変)
```

3 ベクトルが全て正規 unused 0 のまま。local annex keys は **786 → 786 (増加 0)** — 滞留
蓄積期 (falsify-39..49) の反復あたり +24/+25 単調増加とは対照的に、増分ゼロの状態が
**falsify-85→95 の 11 時点観測 / 10 反復連続維持**で続いている (falsify-94 の 10 時点を
1 時点上回る、falsify-94 指定の閾値「10 反復超」を充足)。

### 5. df avail (実測)

`df -h /` → avail **148 Gi / 10%** (falsify-94 の 149 Gi から **約 -1 Gi 微減、150 Gi 未満
のまま**)。annex available local disk space は 158.81 GB (falsify-94 の 159.81 GB 比
約 -1.00 GB、df と同方向・微減)。**avail は falsify-94 に続き 2 反復連続で 150 Gi 未満
(149 → 148 Gi)、159-163 Gi 帯への回復は認められない** — 主張 (c) の「回復→一過性低位
振動」仮説は本反復で成立せず (主張 (c) REFUTED)。観測系列は falsify-85 (163) → 88 (142) →
90 (159) → 91 (159) → 92 (158) → 93 (160) → 94 (149) → 本反復 (**148**) と、falsify-94 の
-11 Gi ステップダウン (160→149) の後、150 Gi 未満で微減を続けている。ただし反復間の低下幅
は -1 Gi と小さく (falsify-90..93 の ±1-2 Gi 振動帯内)、150 Gi 未満が構造的低位の定着なのか
継続低下中なのかは次反復で更に再実測を要する。

### 6. 増加源プロセス / 帰属

`ps aux | grep -E 'itonami-app-resident|export_and_sync'` → 増加源 `node nbb
~/.gftd/bin/itonami-app-resident.cljs` **PID 70643 稼働継続** (falsify-85..94 と同一 PID の
まま、連続稼働、export_and_sync は不在)。local annex keys 786 / 11.68 GB 不変ゆえ **±1-2 Gi
級の avail 変動・微減は local annex に帰属しない** — annex 外部の書き込み源に起因 (falsify-94
と同所見)。TCC 遮断 (onedrive / ~/.gftd du 不能) のため具体源特定は引き続き本 bot では不能
(operator の du 調査を Tier 2 継続)。

### 7. origin/main (実装軸 附帯)

`git fetch origin` → origin/main は **c618ef3 のまま** (falsify-93/94 から連続 3 反復にわたり
新規前進なし)。`rev-parse origin/main = c618ef3...ca73`。本体 checkout は detached HEAD
b5df93e (#299) / porcelain clean (dirty 0)。

## 判定

**SURVIVED (主張 a/b) / REFUTED (主張 c — avail 回復)**: falsify-94 から本反復への独立時点で
3 ベクトル (main / 全 refs / resident-refspec) の正規 unused は **全て 0 のまま**、local annex
keys は **786 → 786 (増加 0, falsify-85→95 の 11 時点観測 / 10 反復連続維持)**、既知 partial
14 chunk のみ — 主張 (a) の再出現も (b) の再蓄積も検出されず、falsify-94 指定の閾値「10 反復超
の連続持続」を充足し 0 ベース恒久化傾向を**更に精緻化**した。

他方、**avail は df 実測 149 → 148 Gi (約 -1 Gi)、annex available 159.81 → 158.81 GB
(約 -1 GB) と 2 反復連続で 150 Gi 未満を維持し、159-163 Gi 帯への回復は認められない** —
主張 (c) の「回復→一過性低位振動」仮説は本反復で不成立 (主張 (c) REFUTED)。falsify-94 の
-11 Gi ステップダウン (160→149) は一過性の低位振動ではなく、150 Gi 未満が少なくとも 2 反復
続く定着状態へ移行したことを示す。滞留 (keys 786)・11.68 GB は不変ゆえ±1-2 Gi 級の変動は
annex 外部の書き込み源に帰属するが、TCC 遮断により具体源は特定不能。低下幅は -1 Gi と小さく、
150 Gi 未満の定着 (低位プラトー) なのか継続低下中なのかの判別は次反復の再実測を要する。

運用/滞留 軸 score は **3 のまま** (0 ベース恒久化傾向の更なる精緻化を記録する一方で、avail
の 150 Gi 未満 2 反復継続は新規監視シグナルとして記録 — score は実緑測定でのみ上昇する規約、
低下ペナルティも本反復では付さず経過観測)。

## スコープ修正 / 次の 1 アクション

- risk-5 の記述を「滞留は 0 ベースへ回帰し **falsify-85→95 の 11 時点観測 / 10 反復連続維持**
  (unused 0・keys 786 不動・partial 14 chunk のみ) — **恒久化傾向の更なる精緻化継続**。
  ただし増加源 (resident ingest, PID 70643) 稼働継続ゆえ再蓄積の可能性は残る」と再アンカー。
- risk-5 の avail 監視シグナルを更新: df avail は **149 → 148 Gi と 2 反復連続で 150 Gi 未満**
  (annex available 159.81 → 158.81 GB 約 -1 GB も同方向)。falsify-94 の -11 Gi ステップダウン
  (160→149) は一過性低位ではなく **150 Gi 未満の定着へ移行した** (回復仮説 REFUTED) が、低下幅
  -1 Gi は振動帯内のため、低位プラトー vs 継続低下の判別を次反復で再実測。consume は annex
  外部 (keys 786 / 11.68 GB 不変) に帰属、TCC 遮断で特定不能 (operator の du 調査を Tier 2
  継続)。
- 次の 1 アクション: 次反復で同 vector + keys + avail を再実測し、0 ベース維持の**11 反復超の
  連続持続** or 再蓄積再開を判別 (11 反復超維持なら恒久化傾向を更に精緻化、再開なら
  falsify-33..53 語調へ復帰)。あわせて **avail 150 Gi 未満の 3 反復目継続 (148 Gi の低位プラトー
  定着 vs 継続低下)** を観測 — 定着なら structural-decline-monitor (risk-5) を強化継続、後退
  (159-163 Gi 帯へ回復) なら一過性低位振動へ再分類。TCC 遮断下の帰属は operator の du 調査を
  Tier 2 として要請継続。
- 附帯 (実装軸 次反復シグナル): origin/main は **c618ef3 のまま** (falsify-93/94 から連続
  3 反復、新規前進なし)。次の実装軸反復では、本反復では対象外とした c618ef3 の parity 測定面
  (3 値結合水準・配線記録) の変化を実測し、2 build-break (identity.clj / kotoba-net) の生存を
  再確認する。
- 附帯 Tier 2: falsify-85..94 と不変 — resident プロセス再起動 (94682→70643) の契機と滞留回収
  の帰属、および falsify-94 以降の avail 150 Gi 未満滞在の具体源特定 (TCC 遮断) は kanban/human
  へ透過提出。

## 付記 (append-only 規約): falsify-94 追記末尾に新規残骸なし

本反復の wc 実測で maturity.md は行 1344 まで。falsify-94 追記は
`(evidence/2026-09-08-falsify-94.md)` (行 1345) で終わり、新規残骸行は混入していない。
falsify-91 追記コマンドの残骸 (行 1237-1238: `MD` / `echo "append rc=$?" ...`) は append-only
規約により本 bot は編集せず残存のまま。本反復追記はその後に追加する。

## 附帯

本体 CHECKOUT (worktree list 先頭
`/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami/cloud-itonami-app`) は
detached HEAD b5df93e (#299)、`git status --porcelain` 0 行 = porcelain clean (dirty 0)。
origin/main は本反復で **c618ef3 のまま (falsify-93/94 から連続 3 反復、新規前進なし)**。
本反復は運用軸の falsify-94 次の 1 アクションを測定対象に選択し、作業は wt-msloop /
bot/maturity-sim-loop で完結、本体 checkout を一切 touch せず。identity.clj malformation /
kotoba-net 未宣言 (falsify-63..84) は対象外・既知赤が継続。7-軸表 16 行目孤立行 placeholder /
falsify-46 mid-sentence cut / falsify-91 末尾残骸 (本付記参照) は残存のまま (append-only
規約で本 bot は編集しない)。
(evidence/2026-09-09-falsify-95.md)
**falsify-96 (2026-09-09, 運用/滞留 軸)**: 0 ベースの **12 時点観測 / 11 反復連続維持**を再実測
(unused 0・keys 786 不動・partial 14 chunk のみ、falsify-85→96) — 主張 (a) 再出現も (b) 再蓄積も
検出されず SURVIVED、falsify-95 指定の閾値「11 反復超」を充足し恒久化傾向を**更に精緻化**。
**avail は 148 → 159 Gi (df)、annex 158.81 → 170.18 GB (約 +11 Gi/+11.37 GB) と 150 Gi を大きく
上回って 159-163 Gi 帯へ回復** — 主張 (c)「150 Gi 未満の低位プラトー定着」仮説 REFUTED (evidence/
2026-09-09-falsify-96.md)。falsify-94/95 の 150 Gi 未満滞在 (149→148 Gi) は**一過性の低位振動**と
確定、structural-decline-monitor 強化は撤回し risk-5 を「avail は 148-163 Gi 帯を振動」へ再アンカー。
増加源 PID 70643 が不変のままの回復ゆえ、消費は稼働自体ではなく一時的書き込みバースト/外部解放に帰属
(TCC 遮断で具体源特定不能、operator の du 調査を Tier 2 継続)。score 3 のまま (0 ベース恒久化傾向の
更なる精緻化、実緑測定でのみ上昇する規約)。次の 1 アクション: 同 vector + keys + avail を再実測し
**12 反復超の連続持続** or 再蓄積再開を判別、avail の 159 Gi 定着 vs 再度の 150 Gi 未満ドロップを観測
(再ドロップなら 148-163 Gi 帯内振動の周期性を検証)。附帯 (実装軸): origin/main は c618ef3 のまま
(falsify-93/94/95 から連続 4 反復、新規前進なし)。

**falsify-97 (2026-09-09, 運用/滞留 軸)**: 0 ベースの **13 時点観測 / 12 反復連続維持**を再実測
(unused 0・keys 786 不動・partial 14 chunk のみ、falsify-85→97) — 主張 (a) 再出現も (b) 再蓄積も
検出されず SURVIVED、falsify-96 指定の閾値「12 反復超の連続持続」を充足し恒久化傾向を**更に精緻化**。
**avail は df 実測 159 → 159 Gi (不変)、annex available 170.18 → 170.25 GB (約 +0.07 GB) と
159-163 Gi 帯内で 2 反復連続の定着を維持** — 主張 (c)「再度の 150 Gi 未満ドロップ (148-163 Gi 帯内
周期性振動)」仮説 REFUTED (evidence/2026-09-09-falsify-97.md)。falsify-94/95 の一時的低位 (149/148
Gi) からの復帰 (159 Gi, falsify-96) が本反復で持続、150 Gi 未満への再ドロップなし、148-163 Gi 帯内の
周期性振動は 2 時点で未検出。増加源 PID 70643 不変のままの安定ゆえ、消費は稼働自体ではなく一時的書き込み
バースト/外部解放に帰属継続 (TCC 遮断で具体源特定不能、operator の du 調査を Tier 2 継続)。score 3 の
まま (0 ベース恒久化傾向の更なる精緻化、実緑測定でのみ上昇する規約)。次の 1 アクション: 同 vector + keys
+ avail を再実測し **13 反復超の連続持続** or 再蓄積再開を判別、avail の 159 Gi 定着の持続 (3 時点目) vs
再度の 150 Gi 未満ドロップを観測 (再ドロップなら 148-163 Gi 帯内振動の周期性を本格検証)。附帯 (実装軸):
origin/main は c618ef3 のまま (falsify-93/94/95/96 から連続 5 反復、新規前進なし)。

**falsify-98 (2026-09-09, 運用/滞留 軸)**: 3 ベクトル (main / 全 refs / resident-refspec) の正規 unused は全て 0 のまま (partial 14 chunk のみ)、local annex keys は 786 → 786 (増加 0, falsify-85→98 の 14 時点観測 / 13 反復連続維持) — 主張 (a) の再出現も (b) の再蓄積も検出されず、falsify-97 指定の閾値「13 反復超の連続持続」を充足し 0 ベース恒久化傾向を更に精緻化 (SURVIVED)。avail は df 実測 159 → 158 Gi (約 -1 Gi 微減、帯内)、annex available 170.25 → 169.89 GB (約 -0.36 GB 微減) と 159-163 Gi 帯内で 3 反復連続の定着を延長、150 Gi 未満への再ドロップなし — 主張 (c)「再度の 150 Gi 未満ドロップ (148-163 Gi 帯内周期性振動)」は本反復で発生せず (REFUTED)、1 Gi 級の帯内微減は structural-decline ではない。増加源 PID 70643 不変のまま (export_and_sync 不在)。score 3 のまま (0 ベース恒久化傾向の更なる精緻化、実緑測定でのみ上昇する規約)。次の 1 アクション: 同 vector + keys + avail を再実測し **14 反復超の連続持続** or 再蓄積再開を判別、avail の帯内定着の持続 (158 Gi 第 4 時点目) vs 再度の 150 Gi 未満ドロップを観測 (再ドロップなら 148-163 Gi 帯内振動の周期性を本格検証)。附帯 (実装軸): origin/main は c618ef3 のまま (falsify-93/94/95/96/97 から連続 6 反復、新規前進なし)。
**falsify-99 (2026-09-09, 運用/滞留 軸)**: 3 ベクトル (main / 全 refs / resident-refspec) の正規 unused は全て 0 のまま (partial 14 chunk のみ)、local annex keys は 786 → 786 (増加 0, falsify-85→99 の 15 時点観測 / 14 反復連続維持) — 主張 (a) の再出現も (b) の再蓄積も検出されず、falsify-98 指定の閾値「14 反復超の連続持続」を充足し 0 ベース恒久化傾向を更に精緻化 (SURVIVED)。avail は df 実測 158 → 156 Gi (約 -2 Gi 微減)、annex available 167.72 GB (169.89 GB 比 約 -2.17 GB 微減) で 150 Gi 未満への再ドロップなし — 主張 (c)「再度の 150 Gi 未満ドロップ」は本反復で発生せず (REFUTED)。ただし **159 → 159 → 158 → 156 Gi と 3 観測連続の緩慢な単調減退**を観測し、falsify-96/97/98 の「159 Gi 前後で帯内定着」枠組みは 156 Gi で成立しなくなった (156 < 159) — 範囲修正として「帯内定着」を**「150 Gi へ向かう漸次的減退トレンド」**へ再アンカー (150 Gi 未満への再ドロップは未発生)。増加源 PID 70643 不変のまま (export_and_sync 不在、ps の自助マッチ行 93269/93285 は測定ハーネス自身を除外)。score 3 のまま (0 ベース恒久化傾向の更なる精緻化、実緑測定でのみ上昇する規約、avail 減退は新規観測として記録)。次の 1 アクション: 同 vector + keys + avail を再実測し **15 反復超の連続持続** or 再蓄積再開を判別、avail の緩慢減退トレンドの継続 (156 Gi → ? Gi) vs 再安定化を観測 (減退継続で 150 Gi 未満再ドロップ or 150 Gi へ漸近する場合 structural-decline-monitor 再強化)。附帯 (実装軸): origin/main は c618ef3 のまま (falsify-93/94/95/96/97/98 から連続 7 反復、新規前進なし)。(evidence/2026-09-09-falsify-99.md)

**falsify-100 (2026-09-09, 運用/滞留 軸)**: 3 ベクトル (main / 全 refs / resident-refspec) の正規 unused は全て 0 のまま (partial 14 chunk のみ)、local annex keys は 786 → 786 (増加 0, falsify-85→100 の 16 時点観測 / 15 反復連続維持) — 主張 (a) の再出現も (b) の再蓄積も検出されず、falsify-99 指定の閾値「15 反復超の連続持続」を充足し 0 ベース恒久化傾向を更に精緻化 (SURVIVED)。avail は df 実測 156 → 156 Gi (不変、初の横這い)、annex available 167.72 → 166.88 GB (約 -0.84 GB 微減・減速) で 150 Gi 未満への再ドロップなし — 主張 (c)「減退トレンド継続による 150 Gi 未満再ドロップ」は本反復で発生せず (REFUTED)。さらに falsify-99 が再アンカーした「150 Gi へ向かう漸次的減退トレンド」は本反復の df 横這い (156→156 Gi) でその連続性を失い (単調減退の 3rd-point が flat、annex available も -2.17 → -0.84 GB に減速)、単調減退の継続予測も成立せず — 範囲修正として avail を「156 Gi で横這い (減退停止)、GB 級微減は一等級減速して継続」へ再アンカー (structural-decline-monitor の再強化は見送り、再ドロップ or 減退再開時まで経過観測)。増加源 PID 70643 不変のまま (export_and_sync 不在、ps の自助マッチ行 75072/74693 は測定ハーネス自身を除外)。score 3 のまま (0 ベース恒久化傾向の更なる精緻化、実緑測定でのみ上昇する規約、avail 減退停止は新規観測として記録)。次の 1 アクション: 同 vector + keys + avail を再実測し **16 反復超の連続持続** or 再蓄積再開を判別、avail の横這い (156 Gi) の持続 vs 減退再開 or 再安定化を観測 (横這い継続なら減退は一過性の低位振動として確定、156 Gi からの減退再開が 2 時点連続で観測された場合にのみ structural-decline-monitor を再強化)。附帯 (実装軸): origin/main は c618ef3 のまま (falsify-93/94/95/96/97/98/99 から連続 8 反復、新規前進なし)。(evidence/2026-09-09-falsify-100.md)
**falsify-101 (2026-09-09, 運用/滞留 軸)**: 3 ベクトル (main / 全 refs / resident-refspec) の正規 unused は全て 0 のまま (partial 14 chunk のみ)、local annex keys は 786 → 786 (増加 0, falsify-85→101 の 17 時点観測 / 16 反復連続維持) — 主張 (a) の再出現も (b) の再蓄積も検出されず、falsify-100 指定の閾値「16 反復超の連続持続」を充足し 0 ベース恒久化傾向を更に精緻化 (SURVIVED)。avail は **df 実測 156 → 127 Gi (約 -29 Gi、3 回確認で不変)、annex available 166.88 → 136.02 GB (約 -30.86 GB) と 150 Gi 未満を大きく下回る再ドロップ** — 主張 (c)「falsify-100 再アンカーの『156 Gi 横這い (減退停止)』が持続する」仮説は本反復で維持できず (REFUTED)、falsify-96..100 が「発生せず」としてきた「150 Gi 未満への再ドロップ」が最初に実観測 (127 Gi、falsify-94/95 の 149/148 Gi より深い)。減退幅 ~29 Gi は GB 級微減 (-0.36〜-2.17 GB) から別格の単反復ステップダウンで、structural-decline-monitor を**単一時点の規模から即時再強化** (2 時点待ち解除)、148-163 Gi 帯内振動仮説は 127 Gi で帯外逸脱し監視枠組みを「構造的減退」へ切替。local annex keys 786 / 11.68 GB 不変ゆえ減退は annex 外部 (TCC 遮断で特定不能、operator の du 調査を異常系・優先度高で Tier 2 提出)。増加源 PID 70643 不変のまま (export_and_sync 不在、ps の自助マッチ行 57065/57067 は測定ハーネス自身を除外)。score 3 のまま (0 ベース恒久化傾向の更なる精緻化、実緑測定でのみ上昇する規約、avail 再ドロップは risk-5 / structural-decline-monitor 再強化トリガとして記録)。次の 1 アクション: 同 vector + keys + avail を再実測し **17 反復超の連続持続** or 再蓄積再開を判別、avail の 127 Gi 定着 vs 更なる減退 (100 Gi 未満で緊急フラグ) vs 回復を観測し structural-decline-monitor を経過観測継続。附帯 (実装軸): **origin/main は b1b4b85f (kotoba.lang.text 配線着地、c618ef3 から更新、8 反復連続 no-advance 解消、291 files / +475 -470)**。(evidence/2026-09-09-falsify-101.md)

**falsify-102 (2026-09-09, 実装 軸)**: origin/main **b1b4b85f** (kotoba.lang.text 配線着地) で既知 2 build-break の生存と kotoba.text 結線整合を再実測。parity: src **214** / test **237** ファイル (b1b4b85f 実測、falsify-84 の d226614: src 244/test 247 からリビジョン差を含む新 anchor)。**(a) build-break #1 (identity.clj 裸 java ベクタ ns 生成形) は SURVIVED (主張 REFUTED の逆 = 修復されず)**: b1b4b85f の identity.clj ns は `(:require ...)` 閉じ括弧の後に bare `[java.nio.charset StandardCharsets]` 等 5 裸ベクタが `(:import ...)` ラッパー無しで残存 (falsify-63 の 679572b と構造同一)、6f9fd0d は「nothing but the substitutions moved」で文字列置換のみ (ns 生成形に不触)。JVM Clojure 1.12.5 (deps.edn と同一 mvn) で `macroexpand-1` → **`Syntax error macroexpanding clojure.core/ns`** (falsify-63 と同一署名。babashka/sci は lenient で誤 OK — 本 bot は JVM を正とした)。**(b) build-break #2 (kotoba-net 未宣言) は SURVIVED (修復されず)**: http_client.clj:16 `(:require [kotoba.net.jvm-host :as jvm-host])` 不変 (repository で唯一の要求元)、deps.edn 全 alias (top :deps / :dev :override-deps / :local/root) で kotoba-net / jvm-host grep **ヒット 0** (rc=1)。参照対象 sibling kotoba-lang/kotoba-net/src/kotoba/net/jvm_host.clj は実在するが、このアプリの deps.edn に宣言なし — falsify-75 の FileNotFoundException 解消せず。**(c) kotoba.lang.text 結線整合は SURVIVED (新規実測)**: deps.edn top-level :deps 行 7 に `io.github.kotoba-lang/text {:git/sha "73bdb13..."}` 宣言 (falsify-101 の「top-level :deps へ移動」を再確認)、src 内 kotoba.lang.text require **132 ファイル**、clojure.string 参照 **残 0** (移行完了)、sibling kotoba-lang/text 実在 — 宣言/参照/移行残0/提供側の 4 側で結線整合。score **3 のまま** (実装軸は JVM suite が 2 build-break で走らないため実緑測定不可 — 実緑でしか上昇しない規約。kotoba.text 結線整合は新規 positive として score 根拠の一部を強化)。次の 1 アクション: 次反復は運用/滞留 軸 (falsify-101 の 17 反復超 / avail 127 Gi 監視) へ戻る。実装軸の修理案は Tier 2 kanban/human 継続: (1) identity.clj の裸 [java.*] ベクタを `(:import ...)` に wrap、(2) kotoba.net 座標を deps.edn に宣言 (git sha 或は :local/root)。(evidence/2026-09-09-falsify-102.md)

**falsify-103 (2026-09-09, 運用/滞留 軸)**: 3 ベクトル (main / 全 refs / resident-refspec) の正規 unused は全て 0 のまま (partial 14 chunk のみ)、local annex keys は 786 → 786 (増加 0, falsify-85→103 の 19 時点観測 / 18 反復連続維持) — 主張 (a) の再出現も (b) の再蓄積も検出されず、falsify-102 ハンドオフの実行対象である falsify-101 指定の閾値「17 反復超の連続持続」を充足 (18 反復 > 17) し 0 ベース恒久化傾向を更に精緻化 (SURVIVED)。avail は **df 実測 127 → 122 Gi (約 -5 Gi)、annex available 136.02 → 130.49 GB (約 -5.53 GB) と falsify-101 の 127 Gi からの回復は認められず継続低下** — 主張 (c)「falsify-101 の 127 Gi 低位が一過性で 148-163 Gi 帯へ回復する」仮説は本反復で成立せず (REFUTED)、falsify-101 の -29 Gi ステップダウンは一過性でなく継続低下中と確定 (低下幅は -29 → -5 Gi に減速、100 Gi 未満の緊急フラグ未到達)。local annex keys 786 / 11.68 GB 不変ゆえ低下は annex 外部 (TCC 遮断で特定不能、operator の du 調査を異常系・優先度高で Tier 2 継続提出)。増加源 PID 70643 不変のまま (falsify-85..102 と同一 PID、export_and_sync 不在、ps の自助マッチ行 64049/64051 は測定ハーネス自身を除外)。score 3 のまま (0 ベース恒久化傾向の更なる精緻化、実緑測定でのみ上昇する規約、avail 継続低下は risk-5 / structural-decline-monitor の経過観測継続として記録)。次の 1 アクション: 同 vector + keys + avail を再実測し **18 反復超の連続持続** or 再蓄積再開を判別、avail 122 Gi の更なる継続低下 (100 Gi 未満で緊急フラグ) vs 減速したままの低位定着 (122 Gi 前後) vs 回復 (148-163 Gi 帯、structural-decline-monitor 再評価) を観測。附帯 (実装軸): origin/main は **b1b4b85f のまま** (falsify-102 と同一、新規前進なし)。(evidence/2026-09-09-falsify-103.md)
