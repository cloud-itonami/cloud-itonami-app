# ADR-0001: jp-go-digital-design-systemを採用する

- Status: Accepted
- Date: 2026-07-28

## Context

`cloud-itonami-app` は日本語を第一言語とするローカルAI操作画面であり、モデルや
プロバイダーの状態、外部送信の有無、必須入力、エラーを誤解なく伝える必要がある。
ユーザーから `jp-go-digital-design-system` の使用が指定された。

## Decision

UIコンポーネント、デザイントークン、タイポグラフィ、フォーム規則には
`kotoba-lang/jp-go-digital-design-system` を使用する。

- DADSのvendored CSSとCLJCコンポーネントを直接利用する。
- light mode固定とし、独自dark paletteを作らない。
- 本文とUIは16px以上を基準とする。
- 入力欄は可視ラベル、要否表示、サポート文を持つ。
- 主要操作には塗りボタン、副次操作にはアウトラインボタンを用いる。
- `kotoba-lang/shell` のWebKitホストを使い、外部フォントを取得しない。
- アプリ固有レイアウトは `local-*` classに限定し、DADS classを上書きしない。

## Consequences

画面は既存のネイティブAppKit部品からWebKit surfaceへ移るが、ウィンドウ、
ライフサイクル、配布境界は引き続き `kotoba-lang/shell` が所有する。
DADSが提供しないdark modeはサポートしない。モデル実行、Kotoba policy、
kgraphメモリ、loopback APIの境界は変更しない。
