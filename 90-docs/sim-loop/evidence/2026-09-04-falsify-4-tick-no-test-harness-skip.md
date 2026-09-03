# falsify-4 (governor 統合軸): 「dirty 解消で tamaki tick が app 本体を処理できる」は半分しか真でない

- 主張: 本体 checkout の dirty 解消 (dirty_files=0) により、tamaki Tier1 tick が
  cloud-itonami-app を dirty-skip せず Tier 1 処理を着地できるはずである。
- 日時: 2026-09-04 (JST)。測定者: itonami-maint
- ソース: /Users/junkawasaki/.gftd/itonami-maturity-tick.stdout.log
  (launchd com.gftd.itonami-maturity-tick, StartInterval=900s)

## 実測

1. dirty-skip は消えた (主張のこの部分は refuted):
   2026-09-03T17:04Z 以降の全 tick で cloud-itonami-app への
   `dirty-skipped` 行は出現しない。tick は本体を処理対象として認識している。

2. しかし skip は継続し、理由が変わった (主張全体としては反証された):
   17:04Z / 17:26Z / 17:47Z / 18:07Z / 18:29Z / 18:53Z / 19:13Z の各 tick で
   いずれも
   `cloud-itonami-app -> :skipped-no-test-harness  clojure -M:dev:test cannot
   run tests here (missing :dev alias or unresolvable deps) -- no green
   baseline to verify against`
   と skip。ledger appended: 0 landed (7/7 tick 全て 0 landed, 0 fixed, 0 deferred)。

3. 同一理由で skip されている姉妹 repo: cloud-itonami-app-5090-stability,
   cloud-itonami-app-principal-v2, cloud-itonami-isic-1313 (4 repo 全部)。

4. 範囲修正: tick のスキャン数は 1430 repos (maturity.md 旧記載 1427 → 1430 に修正)。

5. 付帯観察 (未検証のまま主張しない): 19:35:58Z 開始 tick は
   `=== tick end ===` 行がログに残らないままファイル末尾 (mtime 2026-09-04
   04:36 JST)。launchctl の last exit code は 0。バッファリング or 中断の
   どちらかで断定はしない。次反復で再確認。

## Tier 2 修理案 (着地はしない)

tick はテスト緑ベースラインを要求するが、本体の `clojure -M:dev:test` は
deps.edn に :dev alias が無い/依存が解決できないため起動不可 (OPEN 赤-1 の
JVM コンパイル死とも合流)。案:
- (A) deps.edn に :dev alias を追加し `-M:dev:test` が解決するようにする
  (OPEN 赤-1 のコンパイル死修正とセットで初めて緑ベースラインが成立)。
- (B) tick 側の harness 検出を緩めることはしない (緑確認なしの着地は Tier 1
  契約違反のため)。案 A を人間判断に回す。

## 結論

- refuted: 「dirty 解消で tick 統合が有効化される」は不成立。
  現在の阻害要因は dirty ではなく no-test-harness (:dev alias 欠落)。
- governor 統合 score は 3 から上げない。根拠を正確化: tick は fleet 1430 repo
  をスキャンし本体を dirty-skip しないが、:skipped-no-test-harness により
  Tier 1 着地は継続して 0。
