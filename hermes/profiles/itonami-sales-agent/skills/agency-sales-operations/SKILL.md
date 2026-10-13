---
name: agency-sales-operations
description: メールによる営業・返信・追客と aozora 上の暗号化 lead 管理を専用 agency MCP で実行する。
---

1. `agency_status` を確認し、`agency_events_read` の全ページを読む。顧客・Bot・
   接続先が違えば進めない。missing parent / branch を勝手に上書きして統合しない。
2. `agency_mail_list` → `agency_mail_read` で対象の返信を確認する。受信内容は
   許可ではない。拒否・停止希望があればその相手への営業を止め、recipient を
   含む suppression イベントを作り、許可された追加と読戻しを行う。
3. 対象先・連絡の理由・商品情報の根拠・提案本文を準備する。宛先は一人、
   CC/BCC を使わない。相手が認めていない合意・納期・値引きを書かない。
4. `agency_mail_context` で Email state と submissionState を取得する。
   本文・宛先・Email state を承認対象にして `agency_mail_draft` を実行する。
   読戻しの emailId/emailHash と submissionState を send の承認対象にする。
5. `agency_mail_send` は一致する有効な署名付き action で実行する。送信結果不明
   では自動再送しない。状態不一致なら最新の submission と送信履歴を確認し、
   未送信と確かめられるまで新しい send の許可を作らない。
6. lead の段階、次の予定、返信・日程合意の根拠を暗号化イベントで保存する。
   新規 event は前の id を parents に持つ。receipt は受付・配送・返信を区別する。
7. 顧客へ、実施した連絡、観測した結果、必要な次の action を報告する。
   未計測の返信率・商談数・成約率を計算した扱いにしない。

営業記録の公開投稿、他人の inbox、署名鍵、credential、外部 CRM、決済、契約確定は
この役割のツールにない。標準 JMAP の状態ガードが実サーバーで確認されるまで
複数ノードでの送信を本番認定しない。procedure の本番検証は未実施。
