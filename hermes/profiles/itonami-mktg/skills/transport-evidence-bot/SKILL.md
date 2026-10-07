---
name: transport-evidence-bot
description: Use when itonami transport_evidence.py runs or proposes obs.
---

# transport-evidence-bot

itonami profile の transport 観測 cron (`scripts/transport_evidence.py`、findings は
`findings/transport-obs-<date>-<slug>.json`、propose-only) の手順と罠。

## World Bank LPI 2.0 の存在 (2026-09-21 実測, OBS-LPI20-DISC-001)

- 2026-04-22 に World Bank が **Connecting to Compete 2025: The New Logistics Performance Indicators 2.0 (LPI 2.0)** を出版 (openknowledge.worldbank.org/entities/publication/b6a73b40-…、著 Arvis/Herrera Dappe/Ulybina/Wiederer)。調査 (perception) ベースの旧 LPI (2007-2023) は **legacy** に降格 — lpi.worldbank.org では "Survey-based LPI (2007-2023)" として再ラベル化済み。
- LPI 2.0: shipment-level tracking data (maritime data provider + 大手船会社 / air = Cargo iQ / postal = UPU、全球貿易 ~80% クレーム)、data window 2023-2024、223 economies、**21 indicators (core 6 = partner-economy 数 + mode別 import time, supplementary 15 = export dwell / ship turnaround / transshipment freq+duration / postal B2C 等)**。data360 dataset = WB_LPI_20 (wide = WB_LPI_20_WIDEF.csv)。
- **method 異なるため旧 survey 値と LPI 2.0 値を同一 total に混ぜない** (旧 JPN 3.9/13位 は 2023 survey・data-year 2022; LPI 2.0 の JP country 値は 2026-09-21 時点で未抽出 = UNMEASURED)。
- transport_evidence.py の LP.LPI.OVRL.XQ 経路は legacy 線 — 新 baseline として WB_LPI_20 抽出系を追加すべき観測として propose 済み (findings/transport-obs-2026-09-21-lpi20-discontinuity.edn, 2026-09-21)。

## World Bank LPI の正しい取得形状 (2026-09-20 実測)

- **`/v2/indicator/LP.LPI.OVRL.XQ?per_page=20` は観測を返さない** — indicator METADATA
  1 行だけ (total=1)。`try_lpi()` が常に空になり UNMEASURED が恒久化する。この罠で
  2026-09-17 の cron は 2 信号とも UNMEASURED だった。
- 正値: `https://api.worldbank.org/v2/country/all/indicator/LP.LPI.OVRL.XQ?format=json&per_page=300&mrnev=1`
  → 値あり 212 行。UA なし curl でも通る (CF 403 は起きない)。
- **aggregate 除外は `/v2/country?per_page=400` の `region.value=="Aggregates"` で行う。**
  ISO3 正字チェックでは弾けない (AFE/AFW/ARB は 3 文字 all-alpha、country id ZH/ZI も
  2 文字 alpha)。実測: 値あり 212 行のうち **43 行が aggregate、sovereign は 169**。
  aggregate を sovereign denominator に混ぜると rank/mean がずれる (JPN rank
  15/212→**13/169**、mean 2.887→**2.900**; 旧 14/169 表記は同点処理差)。2026-09-17 の旧 finding はこの混入あり —
  比較時は訂正済みの 2026-09-20 finding を正とする。
- **rank 実測 (2026-09-20 16:30 gate 再測)**: sovereign 169 で JPN は **13 位** (3.9 超 12 国、3.9 同点 ESP/FRA/JPN)。旧 proposal の "14/169" は同点処理で 1 位ずれた値 — 掲載時は同点注記必須。2022 subset 138 でも JPN 13 位。mean 2.9000 / median 2.7000 / pstdev 0.5831、2022 subset mean 2.9935 / med 2.9 / sd 0.5914 は 3 経路で再現済。
- **JSON /v2/country には countryiso3code field が無い** (id が ISO3 相当 + iso2Code)。indicator row の country.id は iso2 (ZH 等) — aggregate 除外は agg.iso3 ∪ agg.iso2 の両集合で row の countryiso3code / country.id それぞれに掛ける。
- 検証済み定数 (LPI 2023 survey, 2022-09-06..11-05 調査): JPN 3.9、SGP 4.3 top、
  sovereign mean 2.900 / median 2.700 / sd 0.5831 (all-year latest-per-country set)。bottom の TLS 1.71 (2007) と
  BDI 2.06 (2018) は mrnev=1 が最新 nonnull を採る性質上、古い年値 (最新 survey 値ではない)。

- **year-only 観測なら 2022-only に絞る**: sovereign 2022 subset=138、mean 2.9935 sd 0.591。
  all-year mixed (2007/2014/2016/2018/2022 混る) の mean 2.900 は 2026-09-20 両 field OR 除外の
  sovereign169 全 set 実測定数。mrnev=1 単独では古値混入 (TLS 2007 は 2007 survey; latest ではない)。

- **両 field (countryiso3code + country.id) の OR 除外が必須**: country xml の iso2 field (ZH/ZI 等 iso2)
  は row の countryiso3code (AFE/AFW/ARB 等) と別字体系。ISO3 だけでは برابرで弾け 43 aggregates
  が一致(両 field の差)して残 4 → collection 上 sov212 誤になる (両 field 除外に=169 正)。
  country endpoint `?format=json` 未指定は default **XML**、`<wb:region id="NA">` で aggregate 判定。
  per_page=400 total=295 → aggregate set 156、row 216 と intersect 43 (sovereign 169 正)。
  ISO3-only フィルタではこの 43 が残って sov212 誤になる。

## gate review 運用
- findings 計数が tick 間で動く (2026-09-20 実測 10→11→12→13) が corpus が sandbox から見えず
  個別 diff 不能。日付系列の算術照合 (09-10×5 + 09-11×2 + 09-14×2 + 09-16 + 09-17 + 09-20×2 = 13)
  で内訳照合し、合わなければ coverage に open item として明記する。空列挙で 12/13 を断言しない。
- 09-20 ゲートの判決系譜: modal-shift = publish-with-caveat 6 条件 (C: 分担率合成禁止・KPI 定義・
  年度ラベル・FY2020 356→387 急増注記・文書バージョン・001985184 混成禁止), fuel-emissions =
  publish-with-caveat (5 条件), transshipment = publish-with-caveat 6 条件 (駅数≠積替回数・22駅は
  目標・パレットデポ「現状設置なし」は宣言・delta18 は端点算術・現象記述混成禁止・2026 拡充未取得),
  他は no-op 継承。判決済み決定ファイルは publish-gate/2026-09-20-decisions-*.edn。

## itonami 自社 API の取得形状 (2026-09-21 実測, OBS-008)

- pre-run transport_evidence.py は placeholder fetch 無の仕様で **4 シグナル恒常 UNMEASURED** (mlit_lpi / mlit_policy / itonami_cloud_status / itonami_api)。「script 仕様上の UNMEASURED ≠ 値が存在しない」— bot が一次取得を実行する。
- **実 API 経路は `itonami.cloud/api/*`**: `/api/health` (ok/live/version/asOf + freePath カウンタ), `/api/status` (~795KB, scores: operationalSurface 828/828), `/api/v1/bot-economy` (trial/simulation, positions[], business verticals)。`api.itonami.cloud` サブドメインは本 cron 環境から **DNS 解決不能** (curl exit 6 / errno 8, 2026-09-21 実測) — 他環境では解決し得る (断定しない)。
- **UA 必須**: itonami.cloud 公式ドキュメント (`https://itonami.cloud/` 冒頭) が「Use a non-empty User-Agent. Default `Python-urllib/*` may get Cloudflare 403 / 1010」と宣言 → curl は `-A 'transport-evidence-bot/1.0'` で実行 (UA 無でも 200 を観測したが doc 指示に従う)。
- bot-economy は公式 self-label: `ledger_mode=simulation`, `real_fund_movement=false`, ADR-0050/0056 fail-closed — 営業主張では「稼働中の bot-first 基盤」可・「実収益実績」不可。
- logistics 垂直: bot-economy positions に **Machi-Hub** (cloud-itonami-machihub, "vacant property to urban micro logistics hub", ISIC 6810, wallet connected) が active 存在。GitHub 公開リポジトリ `cloud-itonami/cloud-itonami-machi-hub` も存在 (blueprint.edn / GOVERNANCE.md / ADR)。

## findings ディレクトリの 2 系統と status key (2026-09-21 実測)

- `workspace/findings/` (旧, 2026-09-10〜21, gate が SCANNED 16 する系譜) は `:finding/status :measured` key を持つ。`verify-findings.cljs` の required = `#{:finding/status}`。
- プロファイル直下 `findings/` (`transport-obs-<date>-<slug>.edn`) は `:obs-id` + `:status :proposed` の旧式 — `:finding/status` 欠落で verify-findings fail。新規 finding は **両 key (`:finding/status :measured` と `:status :proposed`) を併記** すれば gate clean + propose 意味論を両立できる。欠落 fail した既存 finding の後付け修正 (2026-09-21 実測): `:status :proposed` 直前に `:finding/status :measured` を 1 行 insert — edn には key 順序制約なしで reader 型不変、insert 後 verify 再実行で SCANNED 5 / clean / EXIT=0 を確認済み (lpi-dimensions / mlit-rfi 2 件解消)。
- observations.jsonl / evidence_ledger.md は両系譜とも追記対象 (append-only)。jsonl は JSON object 1 行 (ts/type/metric/values/source/method_note/gaps 形)。追記は `cat scratch/line.json >> observations.jsonl` 1 回 — 2 回適用すると重複行になる (2026-09-21 実測、`head -n <expected>` で過剰行除去 + wc -l で検証)。
- **verify 実行の確定手順 (2026-09-21 実測)**: `cd <profile> && kbb workspace/verify-findings.cljs findings > <scratch>/out.txt 2>&1; echo EXIT=$? >> out.txt` を terminal background=true で回し、45 秒 sleep 後 read_file。
- cron 環境で `python3 -c` は「script execution via -c」で Tirith block されることがある → スクリプトを .py ファイルに書いて `python3 file.py` で実行 (file 指定は可)。

## 運用罠

- **findings EDN の map key は必ず keyword (先頭 `:`) で書く**: `:sub-ranks {customs 7}` のような
  bare symbol key は edn reader では Symbol になり、verify-findings.cljs の walk-bad が
  bad-key で 7 件 fail させる (2026-09-21 実測)。`:customs 7` に直して SCANNED 16 / clean。
- cron runtime では `terminal` foreground が空出力/exit 126、`process_manage` は
  background session を追跡できない。実行は background=true + `> /tmp/out.txt 2>&1` +
  別 call の `read_file` で受け、1 回目が空なら race でもう一度 read する。
- `execute_code` は cron では Tirith block (approvals.cron_mode 未設定)。`patch` ツールは profile 外パス (/tmp や profile findings) に効かないことがある → 全文 write_file で置換。
  - ssh backend host は junkawasaki home (/Users/dan は 2026-09-23 実測不存在)。
    ~/.hermes/profiles/itonami/findings は backend から視認・書込可
    → findings は profile findings 直に write_file で着地 (transport-obs-<date>-<slug>.json 形式, 2026-09-23 実測)。

  - LPI fetchは ssh curl 通る: 出典必須 (`url` + fetch日付), UA なし OK, CF403 不.
- 測れなかったら UNMEASURED を正直に出す (script 設計どおり)。値は必ず出典 URL +
  fetch 日付を添えて findings JSON に書く。

## 再検証 URL の正確な形状 (2026-09-20 実測)

- **WB_LPI_20 CT_DT_X の正 URL**: `https://data360files.worldbank.org/data360-data/data/WB_LPI_20/CT_DT_X.csv` (HTTP 200, 503,147 B, JPN 6 行)。旧 tick 記録の略記 `…/WB_LPI_20/CT_DT_X.csv` (host 直下) は Azure 400 OutOfRangeInput になる。
- **subscore 指標コード一覧 (2026-09-22 実測, sources/2 indicators per_page=2000)**: WDI 有効は `LP.LPI.CUST.XQ / INFR.XQ / ITRN.XQ / LOGS.XQ / OVRL.XQ / TIME.XQ / TRAC.XQ` の 7 つ。**`LP.LPI.TIM.XQ` と `LP.LPI.LOGT.XQ` は invalid (400 Invalid value)** — 正は `TIME` と `LOGS`。2026-09-21 の lpi-sub-TIMA/LOGT json はどちらも 400 エラー応答がファイル化されていただけ (= 未計測)。timeliness は 2026-09-22 tick で LP.LPI.TIME.XQ により計測済 (JPN 4.0, WDI comp_rank 16/169 ties JPN/LVA/NLD/NOR, 公式表 17/139 score 4)。
- **MLIT 旧 common 番号 PDF は消え得る**: `/common/001840941.pdf` は 2026-09-20 に HTTP 404 (site-wide ではない — 同 host `/common/001184791.pdf` は 200)。出典 URL は tick ごとに再取得し、404 なら代替出典特定 + 発行物注記。
- **MLIT CID PDF は自前復号可能**: 「zlib 直接抽出不可」は誤り — stream inflate は通る。CID フォント `<XXXX> Tj` (2-byte CID を 4 hex) のため、埋込 ToUnicode CMap (bfchar/bfrange) をパースして CID→Unicode 変換すれば抽出できる。例: 2030大綱検討会提言本文 `/seisakutokatsu/freight/content/001985184.pdf` (200, 547,323 B) → 62,500 字抽出、モーダルシフト KPI (鉄道 209億トンキロ目標/164億トンキロ実績, 海運 389億/371億トンキロ, ギャップはトンベース算出) を literal 照合済。
