# ADR-0015: Kotobase 連携は Passkey 検証後の短命 assertion とする

Status: accepted — 2026-08-04

Cloud Itonami は安定した Principal と、現在操作している credential/controller を
分ける。後者が WebAuthn P-256 公開鍵であっても、Kotobase の CACAO と
`git.kotobase.net` の Nekko request/sigref は Ed25519 署名である。WebAuthn は
任意の SIWE 文字列へ署名できないため、Passkey 自身が CACAO を署名したことには
しない。assertion は stable Principal と active controller を別 resource として記録する。

Passkey で成立した human session だけが
`POST /api/integrations/kotobase/assertion` を呼べる。専用 Ed25519 鍵で
有効期間120秒の CACAO assertion を発行し、audience を
`https://authn.kotobase.net` に固定する。subject は stable Principal、controller
resource は active DID、capability は `session`、`datomic-query`、`git-read` を
明示し、Authn が nonce を一度だけ consume する。email/agent session は発行できない。

現在の実装が受理するのは resident が直接検証した `:kind :passkey` session である。
`auth.itonami.cloud` が WebAuthn を検証して返した `:kind :federated` session は、
`acr=phishing-resistant` であってもまだ assertion 発行条件へ含めない。このため、
同じ中央 Passkey で itonami.cloud から Kotobase へ透過的に入れる、とはまだ言わない。
次の統合では issuer、AMR/ACR、audience、authentication time を検証した中央 session を
明示的に admission し、単なる Email/SSO federation と区別する。

返された `cacao_b64` を `exchange_url` へ top-level form POST し、Authn 自身が
`Domain=kotobase.net` の HttpOnly session cookie を設定する。

この session は Datomic query と authenticated Git bundle read に使う。
`git.kotobase.net` write は別の操作証明であり、Nekko request signature、
sigref、repository delegation chain、distinct-signer quorum を維持する。

assertion response の公開 `issuer` DID を Authn の
`AUTHN_FEDERATION_ISSUERS` に追加する。秘密 seed は移送しない。空の allowlist
は federation disabled として fail closed する。

この federation は同じ Principal を渡す仕組みであり、`itonami.cloud` の raw
WebAuthn credential を `kotobase.net` origin から呼ぶ仕組みではない。ドメイン共有と
別端末の境界は [Passkey Smart Account guide](../passkey-smart-account.md) および
[ADR-0080](0080-passkey-smart-account-is-the-default-wallet.md) を正本とする。
