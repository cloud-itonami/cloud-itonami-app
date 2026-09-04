# winfix-1: PR #274 (POSIX→ACL / launcher dialog) needs review & merge

## 状況 (2026-09-04 初回スキャン)

Windows起動障害の報告2件を検出。両方とも修正は既に `fix/windows-posix-acl` ブランチ
(PR #274, OPEN, レビュー0件) で実装済み。CI (`windows-launch-smoke`, windows-latest実機)
は green。**マージ待ち**の状態。

## 検出した報告

### 1. POSIX権限 — v0.4.1 初回起動クラッシュ
- 症状: `UnsupportedOperationException: 'posix:permissions' not supported as
  initial attribute` (`sun.nio.fs.WindowsSecurityDescriptor`)
- 発生源: `agent-session/ensure-key!` (agent-enrollment.key) 他、以下9箇所すべて
  `PosixFilePermissions` を直接使用しWindows(NTFS)で非対応:
  - `src/cloud/itonami/app/agent_session.clj`
  - `src/cloud/itonami/app/chronicle.clj`
  - `src/cloud/itonami/app/bot_authority.clj`
  - `src/cloud/itonami/app/bot_identity.clj`
  - `src/cloud/itonami/app/drive_crypto.clj`
  - `src/cloud/itonami/app/drive_delivery.clj`
  - `src/cloud/itonami/app/drive_store_migration.clj`
  - `src/cloud/itonami/app/work_partition_store.clj`
  - `src/cloud/itonami/app/organism_messenger_transport.clj`
- 結果: `http://127.0.0.1:1338/health` が一度も200を返さず起動失敗。

### 2. ランチャーダイアログ — 起動失敗が無表示
- 症状: サーバー起動失敗 or ヘルスポーリング中にサーバーが死んだ場合、
  `launcher-error.log` は書かれるがユーザーには何も見えず、ウィンドウが
  ただ開かないだけに見える。
- 副次バグ: `server` バイナリ探索が `cloud-itonami-server`(拡張子なし) のみを
  stat していたため、`.exe` 付きレイアウトで誤検出する可能性。

## 修正内容 (PR #274, ブランチ `fix/windows-posix-acl`)

1. 新規 `src/cloud/itonami/app/secure_file.clj`:
   - `FileStore` の capability 検出 (`os.name` 判定ではない) — POSIX対応なら
     従来の `rw-------`/`rwx------` セマンティクスを維持、非対応なら
     `AclFileAttributeView` で制限的ACL (owner full control、SYSTEM/Administrators
     は標準アクセス維持、Users/Everyone/継承エントリは除去)。
   - SMB/exFAT等どちらのビューも非対応な場合はbest-effort no-op
     (既存の `catch UnsupportedOperationException` 挙動を踏襲)。
   - 上記9箇所すべてをこのヘルパー経由に置換。
2. `packaging/windows/launcher/main.go`:
   - `showStartupErrorDialog()` — PowerShell経由 `System.Windows.Forms.MessageBox`
     でエラーダイアログ表示 (Goネイティブ依存を増やさない)。
   - `writeLauncherError()` — ログは常に上書き (追記ではない。古い実行のエラーが
     新しい失敗に見間違われるのを防止)。
   - `server`バイナリ探索を `cloud-itonami-server` → `cloud-itonami-server.exe`
     の順にフォールバック。
3. CI: `.github/workflows/ci.yml` に `windows-launch-smoke` ジョブ追加
   (windows-latest上でjar+launcherビルド→パッケージ組立→起動→`/health`ポーリング、
   失敗時は server.log/launcher-error.log をダンプ)。

## 検証状況

- ✅ macOS: 影響を受けた10 namespace全てロード確認、`harden!` が
  `{OWNER_READ, OWNER_WRITE}` を計測、`rwx------` dir variant動作、
  `create-file!` が最初のバイトからowner-only。
- ✅ JVM test suite: 9 namespace全てpass (残る失敗は既存のdirty-tree問題、
  baseline stashで実証済み、本PRと無関係)。
- ✅ CI `windows-launch-smoke` (windows-latest実機): SUCCESS — 実際にWindows上で
  ビルド・起動・health 200確認まで到達。
- ⚠️ **検証未了**: ACLパスの手動Windows実機確認、MessageBoxダイアログの
  見た目・文言確認、失敗系(サーバーが起動途中で死ぬケース)のCI negative-path
  テストは未整備 — smokeジョブはhealthy pathのみ検証。

## 次のアクション

1. PR #274 をレビューしてマージする(現在レビュー0件、CIはgreen)。
2. マージ後、`packaging/windows/README.txt` の
   "Java 21 or later available as java.exe on PATH" 要件を見直す — 現行
   `main.go` はJavaを直接起動せず `cloud-itonami-server`/`.exe` バイナリを
   execするだけなので、記載と実装が乖離している。実ユーザー報告があれば
   「Java検出」カテゴリとして別途起票。
3. マージ後、次回スキャンでPR #274相当の差分がmainに取り込まれたことを
   `winfix-state.json` の `last_main_commit_checked` 更新で確認する。

## このタスクでの制約

Windows実機を保有していないため、本計画中の「検証未了」項目は次回以降も
CI (`windows-launch-smoke`) の結果と、実ユーザーからの再現報告のみで
判断する。
