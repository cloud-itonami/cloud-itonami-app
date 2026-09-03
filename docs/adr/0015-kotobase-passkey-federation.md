# ADR-0015: 製品RPへの連携は Passkey 検証後の短命 assertion とする

Status: accepted — 2026-08-04; multi-RP addendum — 2026-08-28

Cloud Itonami は安定した Principal と、現在操作している credential/controller を
分ける。後者が WebAuthn P-256 公開鍵であっても、Kotobase の CACAO と
`git.kotobase.net` の Nekko request/sigref は Ed25519 署名である。WebAuthn は
任意の SIWE 文字列へ署名できないため、Passkey 自身が CACAO を署名したことには
しない。assertion は stable Principal と active controller を別 resource として記録する。

Passkey で成立した human session だけが
`POST /api/integrations/kotobase/assertion` を呼べる。専用 Ed25519 鍵で
有効期間120秒の CACAO assertion を発行する。接続先は server-side allowlist の
`kotobase` (`https://auth.kotobase.net`) または `murakumo`
(`https://auth.murakumo.cloud`) のみで、audience、exchange URL、return URLを
同じ target record から決める。request が任意の audience を指定することはできない。
subject は stable Principal、controller
resource は active DID、capability は `session`、`datomic-query`、`git-read` を
明示し、Authn が nonce を一度だけ consume する。email/agent session は発行できない。

受理するのは resident が直接検証した `:kind :passkey` session、または中央認証が
issuer、client、scope と assurance claims を検証して発行した
`:kind :federated` session のうち、`issued-via=itonami-cloud`、
`authn-provider=itonami-cloud`、`authn-level=phishing-resistant`、
`authn-decision=authenticated`、`authn-factors=[webauthn]` がすべて一致するものだけである。
Email、通常のSSO、agent session はこの経路で Passkey proof に昇格しない。

返された `cacao_b64` を `exchange_url` へ top-level form POST し、Authn 自身が
対象 apex (`kotobase.net` または `murakumo.cloud`) の HttpOnly session cookie を
設定する。cookie は apex を越えて共有しない。

この session は Datomic query と authenticated Git bundle read に使う。
`git.kotobase.net` write は別の操作証明であり、Nekko request signature、
sigref、repository delegation chain、distinct-signer quorum を維持する。

assertion response の公開 `issuer` DID を両RPを提供する Authn Worker の
`AUTHN_FEDERATION_ISSUERS` に追加する。秘密 seed は移送しない。空の allowlist
は federation disabled として fail closed する。

この federation は同じ Principal を渡す仕組みであり、`itonami.cloud` の raw
WebAuthn credential を `kotobase.net` origin から呼ぶ仕組みではない。ドメイン共有と
別端末の境界は [Passkey Smart Account guide](../passkey-smart-account.md) および
[ADR-0080](0080-passkey-smart-account-is-the-default-wallet.md) を正本とする。

対象RPでの最初の Passkey 登録は、上記の高 assurance session が存在するときだけ
同じ account record へ link する。`auth.murakumo.cloud` で匿名登録を許すと別Principalを
作ってしまうため、secondary RPでは `controller_link_required` として fail closed する。
Passkey登録が同じPrincipalへ到達したことと、その公開鍵をon-chain Smart Account ownerへ
追加したことは別の証拠であり、後者は UserOperation と chain receipt が揃うまで未完了である。
