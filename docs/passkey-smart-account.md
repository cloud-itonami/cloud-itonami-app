# Passkey Smart Account: ドメイン共有・別端末・復旧

更新日: 2026-08-28

Cloud Itonami の Wallet は、WebAuthn で検証した P-256 公開鍵を最初の owner
とする ERC-4337 Smart Account です。MetaMask や Coinbase Wallet を接続しなくても
counterfactual address を作れます。

ただし「同じ key」には、別々に扱うべき3つの意味があります。

1. **Passkey credential** — authenticator／credential manager が保持する P-256
   秘密鍵。WebAuthn RP ID に束縛され、サービスへ秘密鍵を渡しません。
2. **Principal** — 人を表す安定した主体。ログイン方法や Passkey を追加・失効しても
   同じであるべき識別子です。
3. **Smart Account owner** — on-chain account が署名を受理する公開鍵。最初の
   Passkey が address を固定し、別RPの公開鍵は明示的な owner 追加で同じ account
   に参加します。

同じ Principal として federation できることは、同じ WebAuthn 秘密鍵を各サイトへ
公開することでも、別の Passkey が既存 Smart Account の owner になることでも
ありません。

## 現在のドメイン境界

| Surface | 現在の認証 | `itonami.cloud` Passkey を直接使うか | 同じ Principal への接続 |
|---|---|---:|---|
| `itonami.cloud` / `auth.itonami.cloud` / `app.itonami.cloud` | RP ID `itonami.cloud` の WebAuthn | Yes | Yes |
| Cloud Itonami resident (`http://localhost:1338`) | RP ID `localhost` のローカル WebAuthn、または `auth.itonami.cloud` OAuth | No。ローカル credential は別物 | Hosted auth では可能。ただし中央 credential の公開鍵はローカル Wallet owner record へ未投影 |
| `murakumo.cloud` / `auth.murakumo.cloud` | RP ID `auth.murakumo.cloud` の独立 Passkey | No | Cloud Itonami の Passkey-rooted session から120秒の target-bound assertion で同じ Principal へ接続 |
| `kotobase.net` / `auth.kotobase.net` | RP ID `auth.kotobase.net` の独立 Passkey、SIWE、DID/CACAO 等 | No | 同じ target-bound assertion で同じ Principal へ接続 |

2026-08-28 の公開面では `itonami.cloud`、`murakumo.cloud`、`kotobase.net` の
`/.well-known/webauthn` はすべて 404 です。したがって、WebAuthn Level 3 の
Related Origin Requests で3つの apex domain が1個の RP ID を共有している、とは
扱いません。`auth.itonami.cloud` が RP ID `itonami.cloud` を使えるのは通常の
registrable-suffix 規則によるもので、Related Origins は不要です。

製品RPへの federation は raw Passkey を接続先へ渡しません。Cloud Itonami が
human Passkey session を確認した後、Kotobase または Murakumo の exact audience と
capability を限定した一回限り・120秒の assertion を発行します。現在の capability は `session`、
`datomic-query`、`git-read` です。Git write は Nekko signature、delegation、
distinct-signer quorum の別境界を維持します。

assertion 発行条件はローカルの `:kind :passkey` session、または
`auth.itonami.cloud` が検証した phishing-resistant WebAuthn sessionです。通常の
Email/SSO/agent session は受理しません。対象RPでは assertion を first-party sessionへ
交換したあと、そのドメイン用の別Passkeyを登録します。この登録は同じPrincipalへ
linkしますが、on-chain owner追加はまだ別途必要です。

### 3ドメインを接続する手順

1. Cloud ItonamiへPasskeyでサインインします。
2. 設定の「同じ Principal を使う」で `kotobase.net` または
   `murakumo.cloud` を選びます。
3. 開いた認証画面で、そのドメイン用のPasskeyを作成します。
4. 同じ手順でもう一方も接続します。

秘密鍵、cookie、raw credentialはドメイン間を移動しません。同じになるのはPrincipalと
Smart Account座標で、Passkey公開鍵は各RPごとに別controllerとして記録されます。

## 採用するドメイン連携方針

WebAuthn credential 自体をドメイン非依存にはしません。credential はフィッシング
耐性のため必ずRP IDに束縛されます。ドメイン非依存にするのは Principal と Smart
Account であり、各製品のRP-scoped Passkeyを同じ owner setへ明示的に追加します。

```text
itonami.cloud Passkey  ──┐
murakumo.cloud Passkey ──┼─ explicit addOwner ── Smart Account / Principal
kotobase.net Passkey  ───┤                         (domain-independent root)
localhost Passkey ───────┘
```

`auth.itonami.cloud` の Authorization Code + PKCE と audience-bound assertion は、
同じ Principal へ到達するための便利な経路として維持します。しかし、その issuer
やRP IDを唯一の identity root にはしません。各 consumer は issuer、audience、
resource、期限、nonce、authentication assurance を検証し、on-chain authority は
Smart Account owner set と receipt で検証します。cookie と秘密鍵は apex 間で
共有しません。

WebAuthn Related Origin Requests は代替手段ですが、現時点の既定にはしません。
採用すると `itonami.cloud/.well-known/webauthn` に他の apex origin を列挙し、
全 ceremony で共通 RP ID を要求し、server verifier の exact origin allowlist も
広げる必要があります。これは Passkey を呼び出せる origin を増やし、なお共通RP
domainへの依存は残すため、identity portability の既定にはしません。

### 実装済みの owner 追加

検証済み Passkey record は `rp-id` と registration origin を保持します。Wallet API は
各 credential を `initial-owner` または `requires-add-owner-user-operation` として表示し、
`POST /api/wallet/owners/plan` は Smart Wallet 1.1 の
`addOwnerPublicKey(bytes32,bytes32)` を
`executeWithoutChainIdValidation(bytes[])` で包んだ unsigned calldata を返します。

実行経路は次の3 endpointです。すべて Human session、same-origin、CSRFを要求します。

- `POST /api/wallet/owners/authorize/start` — allowlist済みchainについて、RPCとbundlerの
  chain ID、EntryPoint v0.6、factory code、implementation、factoryが返すaddress、現在owner
  indexを照合し、予約nonce key `8453`、gas、任意paymasterを含むUserOperationを固定します。
- `POST /api/wallet/owners/authorize/finish` — 固定したhashを現在owner Passkeyで署名します。
  serverはcredential ID、challenge、RP ID hash、exact origin、UP/UV、signature counter、
  P-256署名を再検証し、Smart Wallet 1.1の`SignatureWrapper`へlow-Sで符号化します。
- `POST /api/wallet/owners/operations/{id}/receipt` — bundlerのUserOperation hashが
  ローカル計算値と一致した送信だけを追跡し、成功receipt、sender、nonce、EntryPoint、
  transaction status、account code、`isOwnerPublicKey(x,y)`を再照会します。

WebAuthn assertionそのものは保存しません。保存するのはoperation hash、transaction hash、
chain IDと確認時刻です。異なるchainではnonce・gas・receiptが別なので、owner追加はchainごとに
実行して確認します。`executeWithoutChainIdValidation`はchain IDを署名hashから外しますが、
別chainへの無条件な自動broadcastを意味しません。

### Bundler設定

既定設定は特定のBase/Coinbaseサービスへ依存しません。任意のERC-4337 v0.6 providerを、
chain allowlistと環境変数で指定します。

```text
CLOUD_ITONAMI_EVM_RPC_URL=https://…
CLOUD_ITONAMI_ERC4337_BUNDLER_URL=https://…
CLOUD_ITONAMI_ERC4337_PAYMASTER_URL=https://…   # optional
```

paymasterを設定しない場合は、deterministic Smart Account addressがgasを支払える必要があります。
URLに含まれるprovider keyを`/api/wallet`へ返したり、operation recordへ保存したりしません。

mainnetで初回の権限変更を行う前に、chain ID `11155111` のEthereum Sepoliaを使えます。
2026-08-28に公開Sepolia RPCへread-only照会し、Smart Wallet v1.1 factory
`0xBA5E…5842`、implementation `0x0000…534d`、EntryPoint v0.6 `0x5FF1…2789`
のcodeとfactoryの`implementation()`一致を確認しました。Sepoliaのendpointはmainnetと
別の環境変数で設定します。

```text
CLOUD_ITONAMI_EVM_SEPOLIA_RPC_URL=https://…
CLOUD_ITONAMI_ERC4337_SEPOLIA_BUNDLER_URL=https://…
CLOUD_ITONAMI_ERC4337_SEPOLIA_PAYMASTER_URL=https://…   # optional
```

最初のhosted adapterとしては、Pimlico v2の同じchain endpointをbundlerとpaymasterに
指定できます。Pimlico固有のidentity、wallet SDK、秘密鍵は採用せず、標準
`eth_*UserOperation`とv0.6 `pm_sponsorUserOperation`を境界にします。gas価格だけは、
Pimlicoが要求する`pimlico_getUserOperationGasPrice`の`fast` tierを署名対象へ入れます。
これはnodeの`eth_gasPrice`がbundlerの最低値を下回ることと、取得からTouch IDまでに
時間差があるためです。このoptional methodがJSON-RPC `-32601`なら`eth_gasPrice`へ
戻るので、Alto等のself-hosted bundlerや別providerへURLを差し替えても、Principal、
Passkey、Smart Account addressは変わりません。それ以外のgas oracleエラーは古い値で
送信せずfail closedにします。API keyはURLの一部になるためsecret storeまたは
LaunchAgent環境へのみ置き、設定ファイルやログへcommitしません。

```text
https://api.pimlico.io/v2/sepolia/rpc?apikey=<secret>
```

Pimlicoのtestnet sponsorshipは無料ですがAPI keyが必要です。mainnet sponsorshipは
off-chain残高または登録済み支払手段を消費するため、Sepolia receiptを確認するまでは
有効化しません。Coinbase CDP PaymasterはBase Mainnet / Base Sepolia専用なので、
portable defaultにはしません。

## 別端末から使う

### 1. 同じ credential manager に同期されている場合

もっとも短い経路です。

1. 新端末で、登録時に選んだ iCloud Keychain、Google Password Manager、1Password、
   Bitwarden などの同じ vault/account を有効にします。
2. `https://itonami.cloud/ja/signin/` または `https://auth.itonami.cloud` を開きます。
3. 「パスキーでサインイン」を選び、端末の生体認証または画面ロックで承認します。

credential manager が**同じ credential**を同期していれば公開鍵も同じなので、
Smart Account owner の鍵は変わりません。ただし、新端末の resident は現在
`localhost` RP の local Wallet record を中央 credential から復元しないため、Hosted
sign-in だけで既存 Wallet の送金準備まで完了するとは扱いません。

### 2. Passkey を持つスマートフォンを近くのPCから使う場合

ブラウザの「別の端末のパスキー」または QR code を選び、Bluetooth を有効にして
スマートフォン側で承認します。これは cross-device authentication であり、秘密鍵を
PC へコピーしません。サインインはできますが、PC に新しい owner key を追加する操作では
ありません。

### 3. 別の credential manager／device-bound Passkey を追加したい場合

現在の account model は複数の login Passkey を保存し、RP ID と origin を記録します。
Smart Account descriptor は最初の owner と address を固定します。別の端末で作った
別公開鍵は、次の操作でon-chain ownerへ追加できます。

安全な完成形は次の順序です。

1. 既存 owner Passkey で phishing-resistant に再認証する。
2. 新端末で新しい Passkey を登録する。
3. 既存 owner が `addOwner(new P-256 public key)` UserOperation を承認する。
4. appがreceipt と owner setを独立に確認し、初めてそのchainで`active-on-chain`と表示する。
5. 新端末だけで別の明示的UserOperationを署名できることを検証する。
6. 必要なら旧 owner を別の明示的 UserOperation で失効する。

owner追加は実装済みですが、旧ownerの失効とguardian/delayed recoveryは未実装です。
したがって新しいPasskey登録だけを「既存Walletの復旧」と表示してはいけません。

### 4. すべての端末／Passkeyを失った場合

Email や SSO は Principal への回復経路になり得ますが、現在は Smart Account owner の
秘密鍵を復元せず、on-chain owner set も変更しません。したがってログイン回復と資産の
署名権回復は別です。

資産を持つ前に、少なくとも次を用意します。

- 異なる failure domain に置いた第二の Passkey／hardware security key
- owner 追加・失効の Passkey UserOperation
- recovery guardian または遅延付き recovery policy
- lost-device session revoke と owner-set 監査
- 変更前後の chain receipt と counterfactual/deployed state の照合

## 現在できること／まだできないこと

現在できること:

- Passkey 公開鍵から、拡張Walletなしで deterministic receive address を作る
- 同じ factory がある EVM chain で同じ address を参照する
- 同期済み Passkey または nearby-device WebAuthn で中央認証へサインインする
- ローカルまたは中央の Passkey-rooted session から Kotobase／Murakumo向けの
  target-bound assertion を発行する
- KotobaseとMurakumoの別RP Passkeyを同じPrincipalへlinkする
- 複数RPの credentialを同じ Principal の owner候補として区別する
- replay-safeな `addOwnerPublicKey` unsigned calldataを生成する
- 現在owner Passkeyで完全なERC-4337 v0.6 UserOperationを署名する
- configured bundlerへ送信し、返されたhashをローカル計算値と照合する
- receiptと`isOwnerPublicKey`後条件を確認してchain別owner状態へ反映する

まだできないこと:

- 1個の `itonami.cloud` Passkey で3つの apex siteから直接 WebAuthn を実行する
- 既存 Smart Account ownerを削除・復旧する
- 一般送金をPasskey UserOperationとして署名・送信する
- bundler/RPC/paymasterを未設定のinstallでon-chain操作する

設計判断は [ADR-0080](adr/0080-passkey-smart-account-is-the-default-wallet.md)、
RP-scoped controller と domain-independent identity の境界は
[ADR-0082](adr/0082-webauthn-credentials-are-rp-scoped-smart-account-controllers.md)、
Kotobase の短命交換境界は
[ADR-0015](adr/0015-kotobase-passkey-federation.md) を参照してください。

## Standards and platform references

- [Web Authentication Level 3 — related origins](https://www.w3.org/TR/webauthn-3/#sctn-related-origins)
- [Chrome: Related Origin Requests and cross-device hints](https://developer.chrome.com/blog/passkeys-updates-chrome-129)
- [Apple: use passkeys across devices with iCloud Keychain](https://support.apple.com/en-gb/guide/iphone/iphf538ea8d0/ios)
- [Google: use passwords and passkeys across devices](https://support.google.com/accounts/answer/6197437)
