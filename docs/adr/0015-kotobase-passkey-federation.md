# ADR-0015: Kotobase 連携は Passkey 検証後の短命 assertion とする

Status: accepted — 2026-08-04

Cloud Itonami の利用者 DID は WebAuthn P-256 公開鍵から導出される。一方、
Kotobase の CACAO と `git.kotobase.net` の Nekko request/sigref は Ed25519
署名である。WebAuthn は任意の SIWE 文字列へ署名できないため、Passkey 自身が
CACAO を署名したことにはしない。

Passkey で成立した human session だけが
`POST /api/integrations/kotobase/assertion` を呼べる。専用 Ed25519 鍵で
有効期間120秒の CACAO assertion を発行し、audience を
`https://authn.kotobase.net` に固定する。subject は Passkey P-256 DID、
capability は `session`、`datomic-query`、`git-read` を明示し、Authn が nonce
を一度だけ consume する。email/agent session は発行できない。

返された `cacao_b64` を `exchange_url` へ top-level form POST し、Authn 自身が
`Domain=kotobase.net` の HttpOnly session cookie を設定する。

この session は Datomic query と authenticated Git bundle read に使う。
`git.kotobase.net` write は別の操作証明であり、Nekko request signature、
sigref、repository delegation chain、distinct-signer quorum を維持する。

assertion response の公開 `issuer` DID を Authn の
`AUTHN_FEDERATION_ISSUERS` に追加する。秘密 seed は移送しない。空の allowlist
は federation disabled として fail closed する。
