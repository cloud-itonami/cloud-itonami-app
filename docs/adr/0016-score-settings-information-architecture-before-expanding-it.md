# ADR-0016: Settingsの情報設計を先に採点してから拡張する

- Status: Accepted
- Date: 2026-08-04

## Context

Settings は Passkey、Organization、User、Private Relay、OAuth、Agent tenant
connection を同時に表示していた。機能は見つかるが、利用開始時にどれを行うべきか
判断できない。コンポーネントがDADS準拠でも、情報構造が理解できるとは限らない。

## Decision

Settings の品質を、再現できる監査値から0–100で算出する。

| 次元 | 重み | 観測値 |
|---|---:|---|
| タスク明確性 | 25% | 次の操作の有無、同時に見える主操作数 |
| 選択負荷 | 15% | 可視選択肢の Hick–Hyman entropy `log2(n+1)` |
| 段階的開示 | 25% | 全カテゴリに対する同時表示カテゴリ数 |
| DADS適合 | 25% | ラベル、結果通知、視覚階層、44px操作対象 |
| レスポンシブ適合 | 10% | 390/768/1180pxでの収まり |

監査入力は `resources/cloud/itonami/app/settings_ux_audit.edn`、計算は
`cloud.itonami.app.ux-score` が所有する。初期値は **28.69**、改善後は
**91.58**。これはユーザビリティテストの代替ではなく、同じ欠陥を再導入しないための
engineering score である。

情報構造は次の順にする。

1. 利用開始までの3段階を表示する。
2. 「次にやること」を1件だけ表示する。
3. 詳細は「概要 / アカウント / 組織とメンバー / サービス接続 / Agent接続」に分ける。
4. 既定では概要だけを開き、カテゴリを選んだときだけフォームを開く。
5. iPhoneでは進捗とカテゴリを横スクロール可能にし、主操作を全幅にする。

DADSの余白・階層、可視ラベル、状態通知、44px以上の操作対象を維持する。選択数は
Hick–Hymanの情報量、操作対象はFittsの到達困難度に対応づける。

## System dynamics

`docs/ux/settings-information-architecture.xmile` は `Decision_Friction` をstock、
改善をoutflowとして4 iterationをEuler法で実行する。シミュレーションは既存の
`org-oasis-open-xmile` に委譲し、アプリ側で積分器を再実装しない。

品質の軌跡は `28.69 → 44.41 → 60.13 → 75.86 → 91.58`。モデルは、情報を追加
するほど説明も増えて摩擦が蓄積する状態から、タスク焦点・段階的開示・階層化を反復
して摩擦を排出する設計判断を記録する。

## Consequences

新しい設定機能は既存カテゴリへ入れる。新カテゴリを追加する場合、監査入力・XMILE・
390/768/1180pxの検証を同時に更新し、スコア低下を意図したものかレビューする。

`bin/uiux-score-loop --watch` は、UI、interaction、監査値、XMILE、関連テストの変更を
1秒間隔で検出し、スコア、実装不変条件、JavaScript構文を再評価する。通常のCIも
`--once` を実行し、**85点未満**、監査値と実装カテゴリ数の不一致、可視フィードバック
やiPhone/iPad breakpointの欠落、XMILE targetのずれをmerge前に失敗させる。
