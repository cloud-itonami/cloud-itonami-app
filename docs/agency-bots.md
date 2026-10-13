# 広告運用代行と営業代行

`itonami-ad-operator` と `itonami-sales-agent` はメールと aozora.app のみを業務
チャネルとする専用プロフィール。`hermes/profiles/` が source owner であり、
itonami-registry にプロフィールを複製しない。推論は Murakumo のみ。

実行ツールは `bin/itonami-agency-mcp.mjs` の独立した stdio MCP サーバー。
Node 22 以降で動き、JVM、追加 npm パッケージ、ローカル Itonami server、
Gmail/Resend/Google Ads/Meta Ads/SaaS CRM、共有 filesystem に依存しない。
Hermes 以外の MCP 対応実行ホストでも同じサーバーを使える。
既存の native `bin/itonami-mcp` の fleet_search 面を拡大せず、専用 MCP として
接続する。ネイティブ Bot への単なる SOUL インポートは MCP の実行接続を付けない。

## 役割と実行面

| 役割 | 実行できること | 権限の境界 |
|---|---|---|
| 広告運用代行 | aozora の PR 投稿、投稿反応の観測、メールでの掲載交渉、campaign 記録 | 顧客が許可した自分の DID とメール箱。決済・入札・有料枠購入・削除はなし |
| 営業代行 | 初回営業、返信、追客、メールでの商談調整、lead/停止/receipt 記録 | 専用メール箱、登録 identity、一人の宛先。公開投稿ツールは不可 |

広告運用の初期対象は aozora のスポンサー投稿。投稿の作成は有料配信枠の購入や
インプレッションの保証ではない。現在のツールは observed like/reply/repost/quote
counts を返す。表示回数、クリック、売上、成約は未計測なら null のまま。
営業の成果は相手の返信・顧客確認の商談・契約証拠で別途確認する。

メールは [JMAP Mail (RFC 8621)](https://datatracker.ietf.org/doc/html/rfc8621) の
Email/query/get/set、Identity/get、EmailSubmission/query/get/set を使う。
自己管理メールサーバーの API URL と account/mailbox/identity を設定する。
IMAP/SMTP の credential を JMAP API に渡しても動かない。既存の Resend relay は
この実行面に接続しない。MailA2A は既存の transport-neutral binding の owner
`kotoba-lang/mail-a2a` にあり、本変更はその実装や暗号メールを複製しない。
Bot 間の暗号化 MailA2A や汎用 SMTP/IMAP adapter は本変更の検証範囲外。

aozora は既存 XRPC の getRecord/createRecord/listRecords と getPosts を使う。
公共投稿の collection は app.bsky.feed.post、業務イベントはこの repo が所有する
cloud.itonami.agency.event。未知 collection の汎用記録に対応しない PDS は拒否結果
として扱う。PDS/AppView の実デプロイがこの面を提供しているかは導入時に確認する。

## 接続と実行権限

host は一つの Bot/client に一つの設定ファイルを渡す。設定例は
`config/agency-ad-operator.example.json` と `config/agency-sales-agent.example.json`。
例は enabled=false で、実 DID、アカウント、credential、署名鍵を含まない。
ITONAMI_AGENCY_CONFIG が未設定でも、profile の ITONAMI_AGENCY_ROLE に対応する
disabled な例を使って MCP が起動し、tools/list と agency_status を確認できる。
実接続は host 設定を渡すまで拒否する。別 role の設定ファイルも起動時に拒否する。

設定に必要なもの:

- Bot/client/role、owner が認めた Ed25519 公開鍵、明示的な allowedTools。
- Bot ごとに独立した aozora DID と delegated service credential。
- Bot の専用 JMAP account/mailbox/identity と From、delegated service credential。
- Bot/client ごとの 32-byte AES key の host secret reference。
- 実際の HTTPS PDS/AppView/mail API URL。model から URL、From、account、DID を受け取らない。

これらは service delegation の設定で、人間のログイン・回復・step-up の仕組み
ではない。既存の Passkey-only 人間認証を置き換えず、セッションを発行しない。
設定・issuer key は既存の owner-authorized host provisioning で配布する。
public key や DID を書くだけでアカウント所有を確立した扱いにしない。

owner の署名付き action は version/bot/client/role/tool/argsHash/notBefore/
expiresAt/nonce を覆う。引数の変更、別顧客、別 Bot、期限切れ、別署名者は
外部 I/O の前に拒否する。許可は最大一時間、付属 signer の既定は十五分。
host の allowedTools と各サービス自身の権限の双方が必要。例を true にしただけでは
credential も、PDS/mailbox 権限も作られない。

owner は具体的な action JSON を `scripts/agency-authorize.mjs --prepare` で
確認し、別の issuer 環境で `--sign` を実行する。stdin は `{tool, arguments}`、
stdout は同じ引数に authorization を追加した JSON。秘密鍵を stdout に出さない。
issuer の `AGENCY_OWNER_PRIVATE_KEY_FILE` を Bot や MCP の環境へ渡さない。
署名環境と worker は別の OS principal/container 権限に置き、issuer key を mount
しない。同じ OS account の shell access があればこの隔離は成立しない。
profile の CLI toolset は mcp-agency のみ。汎用 terminal/browser/file/外部 app
を一緒に付けると、この実行面の境界を迂回できるため許可しない。

```sh
# Run in the source checkout or a managed workspace. Values are non-secret paths.
export ITONAMI_AGENCY_APP_DIR=/Users/junkawasaki/github/cloud-itonami/cloud-itonami-app
export ITONAMI_AGENCY_CONFIG=/path/to/host-managed/customer-bot.json
node "$ITONAMI_AGENCY_APP_DIR/bin/itonami-agency-mcp.mjs"
```

他ノードでは ITONAMI_AGENCY_APP_DIR をそのノードの canonical checkout にする。
source profile の MCP 設定は固定 command とこの変数だけを使う。host credential は
環境または既存の secret manager が設定例の名前に解決する。値は profile や Git、
会話に含めない。新しい ~/.hermes や ~/.cloud-itonami を project/scratch として作らない。

## 記録と複数ノード

業務記録は owner 承認イベントを AES-256-GCM で暗号化し、Bot DID の repository に
保存する。cloud.itonami.agency.event の公開面は ciphertext/IV/tag/version のみ。
lead の住所、宛先、商談情報は plaintext の公開 record にしない。
[AT repository は公開データの保存面](https://atproto.com/specs/repository)なので、
暗号化鍵を持たない第三者が読める前提で設計する。ciphertext の長さと投稿頻度は
隠さない。メール transport 自体の end-to-end 暗号化をこの処理で実装したとは言わない。

event は内容と承認の HMAC から決まる record key を持ち、同じ action を別ノードで
実行しても同じ ciphertext になる。読取りでは GCM、署名、scope、record key を検査する。
同じ顧客でも役割ごとに独立 DID/key を使う。別 Bot へ営業情報を渡す場合は、その
Bot の範囲への別の承認済み event として渡す。key rotation では履歴の移行と
過去 public key の検証を先に設計し、既存 key を突然置き換えない。

parent refs は event に含める。並列イベントは消さず branch/missingParents を返す。
ページ途中で「完全な履歴」とは言わない。timestamp の sort だけを合意順序にしない。
同じ record の読戻しに成功しても、全 PDS replica の収束や耐障害性の証拠ではない。
PDS 側の record key が append-only を強制しない場合でも、ツールは一致した内容だけを
再使用する。server が第三者に更新・削除を許す問題を client だけで解消したとは言わない。

メール送信は EmailSubmission/set の ifInState を署名対象にして、二つの worker の
同時送信を server の状態チェックで拒否させる。既存の submission も先に照合し、
成功時は `$draft` を外す。server が条件付き更新を正しく実装していることが前提。
結果不明、消去された submission、server の状態回帰に対して自動再送しない。
`agency_mail_submissions` で owned message の受付・deliveryStatus を照合する。
空の履歴は「未送信」の証拠ではない。

suppression イベントを送信直前に全ページ読む。拒否した相手への送信を止める。
これは aozora とメールをまたぐ原子的 transaction ではなく、直前観測のガード。
同時に別ノードで停止が書かれるケースを完全に線形化した保証はない。厳密な
cross-channel fence が必要なら既存の Kotoba grant/execution owner での統合が必要で、
ローカル mutex や新しい centralized CRM で「完全分散」を装わない。

## 導入と検証状態

profile source、MCP tools、暗号化記録、exact-action grants と host fixture は実装済み。
テスト: `node --test test/agency-tools.test.mjs`。既存の ci-jvm-free-emit 検証入口でも同じテストを実行する。新しい GitHub Actions ジョブは追加しない。
署名改変、顧客・メール箱の分離、暗号化と別 host の復元、投稿の読戻し、並行
送信、一度の受付、停止、未知 metrics、stdio MCP の実起動を fixture で検証する。
fixture は実メール、実 DID、federation、Murakumo の model-to-tool 呼出しの証拠ではない。

本番の有効化には、owner が管理する JMAP URL/identity/mailbox、専用 aozora DID、
delegated credentials と issuer public key の設定が必要。それからテスト用の本人宛
メールで受付と配送を別々に確認し、専用 DID の広告テスト投稿と AppView の読戻しを
確認する。二つの独立した host からの競合、再起動後の記録復元、停止と federation
障害時の挙動も確認してから distributedExecutionQualified を更新する。
デフォルトは liveVerified=false / distributedExecutionQualified=false。
空の cron/jobs.json は scheduled job の実行を主張しない。

rollback は MCP の停止、profile の非稼働化、host enabled=false、delegated service
credential の失効。既存 profile や顧客記録を削除しない。公開済み投稿や送信済み
メールを、この設定変更で取り消した扱いにはしない。
