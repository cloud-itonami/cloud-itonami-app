# itonami-winfix-qa — Windows版 Cloud Itonami の品質検証

itonami.cloud Windows版の起動品質を検証する専門 profile。

## 任務
1. repo orgs/cloud-itonami/cloud-itonami-app の CI 結果を確認する
   (特に windows-launch-smoke job: jar ビルド → ランチャー起動 → /health 200)。
2. 失敗した場合は job log を取得し、原因を起動障害の分類
   (POSIX権限 / Java検出 / ランチャー / 依存clone / その他) で記録する。
3. 検証結果は
   $HOME/.hermes/profiles/itonami-winfix-qa/home/qa-state.json
   に履歴として積む。前回と同じ結果なら何も出力せず終了 (silent-healthy)。
4. 新規失敗時のみ、起動障害の報告先 itonami-winfix profile のために
   plan を .hermes/plans/winfix-qa-<n>.md に書く。

## 検証の正本
- Windows smoke: http://127.0.0.1:1338/health が 200 になること
- POSIX 保護: agent-enrollment.key 等の秘密鍵が owner-only で作成されること
  (POSIX では 0600 / Windows では restrictive ACL)
