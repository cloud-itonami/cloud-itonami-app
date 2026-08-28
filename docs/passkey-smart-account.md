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
3. **Smart Account owner** — on-chain account が署名を受理する公開鍵。現在の
   descriptor は最初に検証した1個の Passkey 公開鍵を固定します。

同じ Principal として federation できることは、同じ WebAuthn 秘密鍵を各サイトへ
公開することでも、別の Passkey が既存 Smart Account の owner になることでも
ありません。

## 現在のドメイン境界

| Surface | 現在の認証 | `itonami.cloud` Passkey を直接使うか | 同じ Principal への接続 |
|---|---|---:|---|
| `itonami.cloud` / `auth.itonami.cloud` / `app.itonami.cloud` | RP ID `itonami.cloud` の WebAuthn | Yes | Yes |
| Cloud Itonami resident (`http://localhost:1338`) | RP ID `localhost` のローカル WebAuthn、または `auth.itonami.cloud` OAuth | No。ローカル credential は別物 | Hosted auth では可能。ただし中央 credential の公開鍵はローカル Wallet owner record へ未投影 |
| `murakumo.cloud` | 現在の browser login は SIWE/EIP-4361 | No | `auth.itonami.cloud` client は未接続 |
| `kotobase.net` / `auth.kotobase.net` | 独立 Passkey、SIWE、DID/CACAO 等 | No | Cloud Itonami のローカル Passkey session から120秒の federation assertion を発行する経路はある |

2026-08-28 の公開面では `itonami.cloud`、`murakumo.cloud`、`kotobase.net` の
`/.well-known/webauthn` はすべて 404 です。したがって、WebAuthn Level 3 の
Related Origin Requests で3つの apex domain が1個の RP ID を共有している、とは
扱いません。`auth.itonami.cloud` が RP ID `itonami.cloud` を使えるのは通常の
registrable-suffix 規則によるもので、Related Origins は不要です。

Kotobase federation は raw Passkey を Kotobase へ渡しません。Cloud Itonami が
human Passkey session を確認した後、audience と capability を限定した一回限り・
120秒の assertion を発行します。現在の capability は `session`、
`datomic-query`、`git-read` です。Git write は Nekko signature、delegation、
distinct-signer quorum の別境界を維持します。

また、現在の assertion 発行条件はローカルの `:kind :passkey` session です。
`auth.itonami.cloud` から返った phishing-resistant な federated session を同じ条件で
受け入れる変更はまだ入っていません。したがって「itonami の中央 Passkey で入り、
そのまま Kotobase へ交換する」は未完成です。

## 採用するドメイン連携方針

3つの apex domain に raw WebAuthn ceremony を複製するのではなく、
`auth.itonami.cloud` を Passkey ceremony の入口にして federation します。

```text
murakumo.cloud ─┐
kotobase.net ───┼─ Authorization Code + PKCE / audience-bound assertion
resident ───────┘                 │
                                  ▼
                         auth.itonami.cloud
                         RP ID: itonami.cloud
                                  │
                                  ▼
                         credential manager
```

各 consumer は issuer、audience、resource、期限、nonce、authentication assurance を
検証します。cookie を apex 間で共有せず、Passkey 公開鍵や秘密鍵もコピーしません。
Smart Account の UserOperation を承認するときは、consumer が operation digest を
中央 ceremony へ渡し、`auth.itonami.cloud` で Passkey を使って署名し、結果を
要求元へ戻す構成にします。

WebAuthn Related Origin Requests は代替手段ですが、現時点の既定にはしません。
採用すると `itonami.cloud/.well-known/webauthn` に他の apex origin を列挙し、
全 ceremony で共通 RP ID を要求し、server verifier の exact origin allowlist も
広げる必要があります。これは Passkey を呼び出せる origin を増やすため、中央認証で
足りる限り避けます。

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

現在の account model は複数の login Passkey を保存できますが、Smart Account
descriptor は最初の owner を固定します。別の端末で新規作成した別公開鍵を既存 Wallet へ
追加する on-chain `addOwner` UserOperation は未実装です。

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
- ローカル Passkey session から Kotobase read/session assertion を発行する

まだできないこと:

- 1個の `itonami.cloud` Passkey で3つの apex siteから直接 WebAuthn を実行する
- Murakumo が `auth.itonami.cloud` の Principal を受理する
- 中央 Passkey 公開鍵を新しい resident の既存 Wallet descriptor へ安全に復元する
- 新しい Passkey を既存 Smart Account owner set へ追加・削除する
- Passkey 署名の ERC-4337 UserOperation を submit/deploy する

設計判断は [ADR-0080](adr/0080-passkey-smart-account-is-the-default-wallet.md)、
Kotobase の短命交換境界は
[ADR-0015](adr/0015-kotobase-passkey-federation.md) を参照してください。

## Standards and platform references

- [Web Authentication Level 3 — related origins](https://www.w3.org/TR/webauthn-3/#sctn-related-origins)
- [Chrome: Related Origin Requests and cross-device hints](https://developer.chrome.com/blog/passkeys-updates-chrome-129)
- [Apple: use passkeys across devices with iCloud Keychain](https://support.apple.com/en-gb/guide/iphone/iphf538ea8d0/ios)
- [Google: use passwords and passkeys across devices](https://support.google.com/accounts/answer/6197437)
