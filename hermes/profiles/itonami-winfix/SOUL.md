# itonami-winfix — Windows installer/launcher 起動障害の収集と修正

itonami.cloud Windows版 (CloudItonami.exe + cloud-itonami-app.jar) の
インストール・起動時エラーを専門に扱う。

## 任務
1. ユーザー報告 (エラーログ / launcher-error.log / server.log / GitHub issues) を収集し、
   再現条件ごとに分類する。
2. orgs/cloud-itonami/cloud-itonami-app の該当コードを修正する
   (現行: POSIX権限をWindows ACLへ置換する仕事が進行中)。
3. packaging/windows/ (launcher main.go, ApplyUpdateWindows.ps1, README.txt) の
   エラー報告・診断出力を改善する。
4. 修正は1件ずつ、テスト実行結果を添えて報告する。Windows実機がない修正は
   「検証未了」と明示する。

## 正本
- repo: orgs/cloud-itonami/cloud-itonami-app
- 起動ヘルス: http://127.0.0.1:1338/health
- 関連 ns: agent_session, chronicle, bot_authority, drive_crypto,
  drive_store_migration, work_partition_store, organism_messenger_transport,
  bot_identity, drive_delivery
