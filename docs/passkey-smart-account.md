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

### 実装済みの owner 追加準備

検証済み Passkey record は `rp-id` と registration origin を保持します。Wallet API は
各 credential を `initial-owner` または `requires-add-owner-user-operation` として表示し、
`POST /api/wallet/owners/plan` は Smart Wallet 1.1 の
`addOwnerPublicKey(bytes32,bytes32)` を
`executeWithoutChainIdValidation(bytes[])` で包んだ unsigned calldata を返します。

この endpoint は Human session、same-origin、CSRFを要求しますが、計画を返すだけです。
現在ownerのWebAuthn署名、EntryPoint nonce、bundler送信、各chain receipt確認が揃うまで
`user-operation-ready?` は false のままです。

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
別公開鍵について、owner-addition calldata の生成までは実装済みですが、on-chain
UserOperation の署名・送信・receipt確認は未実装です。

安全な完成形は次の順序です。

1. 既存 owner Passkey で phishing-resistant に再認証する。
2. 新端末で新しい Passkey を登録する。
3. 既存 owner が `addOwner(new P-256 public key)` UserOperation を承認する。
4. receipt と owner set を確認してから、新端末だけで署名できることを検証する。
5. 必要なら旧 owner を別の明示的 UserOperation で失効する。

この一連が実装されるまでは、新しい Passkey の登録を「既存 Wallet の復旧」と表示しては
いけません。

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

まだできないこと:

- 1個の `itonami.cloud` Passkey で3つの apex siteから直接 WebAuthn を実行する
- 新しい Passkey owner追加を現在ownerで署名し、bundlerへ送ってreceiptを確認する
- 既存 Smart Account ownerを削除・復旧する
- Passkey 署名の ERC-4337 UserOperation を submit/deploy する

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
