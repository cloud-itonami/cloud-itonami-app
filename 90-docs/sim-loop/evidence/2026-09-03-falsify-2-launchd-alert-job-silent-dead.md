# falsify-2: 「launchd KeepAlive で server/ui-host/tick は安定的に稼働」は半分しか真でない

- 日時: 2026-09-03 (JST)
- 測定者: itonami-maint
- 対象軸: 運用 (score 3 の根拠行)
- 手段: `launchctl list | grep -i itonami` および
  `launchctl print gui/501/<label>` の実測出力をそのまま記録

## 主張 (反証対象)

運用軸 score 3 の根拠「server/UI-host/tick の launchd KeepAlive、cron 5 本、
ingress 疎通済み」→「全 itonami launchd job が健全に KeepAlive 稼働している」
という広がり。

## 実測出力 (そのまま)

`launchctl list | grep -i itonami` (PID / last exit / label):

    -	0	cloud.itonami.noren.resident
    45891	143	dev.cloud-itonami.app
    9330	0	com.gftdcojp.itonami-5820-tunnel
    -	0	com.gftdcojp.itonami.mail-drain
    -	1	com.gftdcojp.itonami.expiry-alert
    -	0	com.gftd.itonami-maturity-improve
    53087	0	com.gftdcojp.itonami-5820-serve
    -	0	com.gftd.itonami-qwen36-tick
    15463	0	com.gftd.itonami-maturity-tick
    90738	0	dev.cloud-itonami.ui-host
    -	0	com.gftdcojp.itonami.nudge-scan
    -	0	cloud.itonami.hanmoto-register
    11277	78	ai.hermes.gateway-itonami-winfix
    -	0	com.gftd.itonami-os-connect
    -	0	com.gftdcojp.itonami.warm-flagship

 Coring 個別 print:

- `com.gftdcojp.itonami.expiry-alert`: state = not running,
  last exit code = 1, program = /opt/homebrew/bin/nbb
- `ai.hermes.gateway-itonami-winfix`: state = running,
  last exit code = 78 (EX_CONFIG) — 現 PID 11277 で稼働中だが前回は設定エラーで落ちた
- `dev.cloud-itonami.app`: PID 45891, last exit 143 (SIGTERM) —
  KeepAlive による再起動痕跡。現行は稼働中
- 疎通: `GET https://gateway.itonami.cloud/health` → **200** (本測定で実行)

## 判定: 部分反証 (範囲修正で survived 寄りに収束)

- 反証された部分: 「cron/alert 系も含めて全 job 健全」— expiry-alert が
  exit 1 のまま not running (KeepAlive=false で再起もされず黙って死んでいる)。
  「KeepAlive で再現稼働」は server/app/ui-host/tick には成立するが、
  alert 系ジョブには成立しない。
- 維持された部分: コア稼働面 (app PID 45891 / ui-host PID 90738 /
  maturity-tick PID 15463、いずれも exit 0 稼働中、health 200)。

## 範囲修正 (ledger 反映)

運用 score 3 は維持だが、根拠を正確化: 「KeepAlive 再現は server/UI-host/tick
で実測成立。ただし expiry-alert が exit 1 で silent-dead (自動復帰なし) —
KeepAlive=false の alert 系は静かに死ぬ」。

## 修理案 (Tier 2 — 着地は人間判断)

expiry-alert の plist に KeepAlive=true (または SuccessfulExit=false) を付与し、
死んだまま放置されないようにする。復帰後に一度手動 kickstart で exit 0 を確認。

## 付随測定 (リスク-3 の進行)

- journal_bytes = 1,271,163 / bound = 4,194,304 → **30.3%** (前回測定 21%)。
  1 日弱で +9pt。このペースなら bound 到達は数十日 — checkpoint/rotation の
  監視を次反復以降で継続する。
