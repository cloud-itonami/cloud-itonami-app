# 分散化ギャップ分析: DNS 非依存・happ 型 itonami app に向けて

作成: 2026-10-13 / 対象: `cloud-itonami/cloud-itonami-app` / 種別: 調査メモ(ADR ではない)

## 1. 問い

「itonami app は Holochain の happ のように、DNS などに依存せず分散して動く設計・実装か」。

**結論: 現状はそうではない。** ID と grant は暗号学的に自己完結する方向(DNS 非依存の布石)にあるが、権限の実行主体は単一の resident プロセスで、公開面・ログイン・テナント検証は DNS/ドメインと Cloudflare に依存している。一方、分散の土台(murakumo の libp2p WASM lattice)は既に存在し、そこへ載せ替える経路も ADR-0092 に書かれている。足りないのは「権限と状態を lattice 側へ移す」部分である。

## 2. 比較基準: happ 型とは何か

| 性質 | happ(Holochain)での姿 |
|---|---|
| agent-centric | 各 agent が自分の source chain を持ち、署名した entry が正本 |
| 検証の分散 | validation rule を全ピアが実行し、不正 entry は誰でも拒否できる |
| 共有状態 | DHT に entry を置き、複数ピアが保持する(中央 DB なし) |
| 配送 | ピア間 gossip / 直接接続。DNS・CA・中央サーバーを必須としない |
| ID | 公開鍵が agent 自身(ドメインに紐づかない) |
| 可用性 | 一部ピアが落ちても継続。本人のマシンが寝ていても他ピアが保持 |

## 3. 現状の評価(観点別)

凡例: ✅ 満たす / 🟡 部分的 / ❌ 満たさない

| # | 観点 | 判定 | 現状 | 根拠 |
|---|---|---|---|---|
| 1 | 権限の所在 | ❌ | Goals / Assignments / grants / approvals / replay / Bot turn はすべて単一 resident(JVM)が正本 | `mesh/itonami.app.edn` 冒頭「stay OFF-MESH on the JVM resident」、ADR-0077、ADR-0092 §2 |
| 2 | 状態の保管 | ❌ | resident ローカルの `state.edn` と object store。複製・合意の仕組みなし | `docs/architecture.md`(`state.edn`、Drive、object store) |
| 3 | ユーザー ID | ✅ | Ed25519 `did:key`(解決に I/O 不要)。Passkey は別軸(ADR-0064) | `docs/tenant-model.md` |
| 4 | 組織 ID | 🟡 | ADR-0068 で `did:webvh`(pre-rotation、3-of-5 witness)へ。ただし web ホスト前提で、`did:web` は引き続き DNS+TLS 保有者が controller | ADR-0068、README「did:web」節 |
| 5 | 認可 | ✅ | CACAO による署名済み・期限付き・オフライン検証可能な capability。VC は `eddsa-jcs-2022` + status list | `deps.edn`(org-chainagnostic-cacao)、README Status |
| 6 | ウォレット | 🟡 | Passkey 由来の ERC-4337 counterfactual account。bundler と L1/L2 RPC は外部依存 | README、ADR-0080 / 0082 |
| 7 | ログイン | ❌ | WebAuthn の RP ID(ドメイン)に縛られ、`itonami.cloud` / `murakumo.cloud` / `kotobase.net` で Passkey を共有できない | README Status、`docs/passkey-smart-account.md` |
| 8 | テナント/ドメイン | ❌ | カスタムドメインは DNS TXT `_itonami-verification.<domain>` で検証。DNS が検証の根 | `docs/tenant-model.md` |
| 9 | 公開面 | ❌ | `app.` / `hooks.` / `mcp.` / `bots.` は Cloudflare Workers。resident は loopback 専用 bind で、外部到達は別経路 | ADR-0092 Context、`docs/architecture.md` §loopback |
| 10 | ノード間配送 | 🟡 | murakumo lattice は libp2p gossipsub、WASM component をオークションで配置、content-addressed。ただし itonami が載せているのは `agent-profile` / `agent-heartbeat` の 2 つだけで、どちらも「決定済みの事実を提供・証明する」のみ | ADR-0092、`mesh/itonami.app.edn` |
| 11 | 更新配布 | 🟡 | `:kotoba.app/latest` は鍵由来 IPNS 名。実装は delegated HTTP ルーター(delegated-ipfs.dev 等)経由で publish/resolve。自前の DHT ノードではない | `src/cloud/itonami/app/latest.cljk`、`deps.edn` |
| 12 | 外部 SaaS 連携 | ❌(性質上) | Google / Microsoft / Slack / Notion、Baseten / Together(GLM-5.3)、`kotobase.net`、`relay.gftd.ai` | `deps.edn`、README、ADR-0092 |
| 13 | 可用性 | 🟡 | resident が落ちると権限者不在。ADR-0092 で cloud 常駐化したが、依然「1 つの resident」。lattice は 12 ノード中 9 到達(2026-09-02 計測)と冗長だが、権限は冗長化されていない | ADR-0092 計測表 |
| 14 | データ局所性の方針 | 🟡 | 「local-first」ではなく「security-first」(ADR-2608130100)。egress は審査・暗号化・認証情報・全体スイッチの全条件を要求。これは分散志向ではなく境界管理 | README 冒頭 |

要約: **識別子と認可(3, 5)は DNS 非依存に近い。権限・状態・入口(1, 2, 7, 8, 9)が中央集権的。** 配送の土台(10, 11)は分散だが、そこに載っている責務が小さい。

## 4. ギャップの本質

happ 型との差は 4 点に集約できる。

1. **正本が agent の chain ではなく resident の `state.edn`。** 誰かが検証して拒否できる構造がない。
2. **入口が DNS+TLS+Cloudflare。** ドメインを失う/止められると到達も Passkey も止まる。
3. **権限の実行が WASM lattice 外。** 理由は能力不足(`llm/infer` が `contract-only`)であって方針ではない、と ADR-0092 が明記している。
4. **公開鍵を ID にしつつ、ログインは RP ID(ドメイン)に結び付く。** ID は可搬、認証器は不可搬という分裂がある。

## 5. 縮める方向(選択肢)

優先度は「効果 ÷ 工数」の私見。実装前に別途 ADR で決めること。

| 優先 | 施策 | 縮まるギャップ | 備考 |
|---|---|---|---|
| A | **状態を append-only の署名ログにする**(agent ごとの source chain 相当)。resident の `state.edn` はその投影キャッシュに降格 | 1, 2 | リポジトリ内に `chain`(L2 graph CID)、`kotoba-protocol`、Datom ログの部品が既にある。最小の分散化の核 |
| A | **Goal/Assignment/approval を署名付き entry とし、検証関数を WASM component として lattice に置く** | 1 | `mesh/itonami.app.edn` の「third component」枠を使う。`llm/infer` の admit を待たない部分(承認・grant 検証)から先に移せる |
| B | **到達性を DNS から切り離す**: ノードを公開鍵/CID で指名(libp2p peer id、または `did:key` → ノード解決)。ドメインは任意の別名にする | 8, 9 | `kekkai` / `kekkai-node` の netmap 認可が既に依存にある。入口 Worker は「ゲートウェイ」へ格下げ |
| B | **認証器の可搬化**: Passkey を RP ごとの controller の 1 つとして扱い、`did:key` / `did:webvh` 側の鍵ローテーションを正とする(ADR-0082 の延長) | 4, 7 | README 記載のとおり Passkey が Data Integrity proof を作れない構造的制約は残る |
| B | **IPNS を delegated ルーター任せにせず、lattice ノードが DHT/gossip で IPNS を提供** | 11 | 既存 `io-libp2p-specs-kad-dht` を利用可能 |
| C | **resident の多重化**: 同一 agent ログを複数 resident が読み、合意ルール(単一 writer + 失効リース、または witness 署名)で権限者を移譲 | 13 | A の後でないと意味がない |
| C | **外部 SaaS を「アダプタ」として明確に外側へ**(失敗しても agent の正本は壊れない) | 12 | 既に fail-closed の egress 審査があり、方針とは整合 |
| — | ウォレット/bundler の自前化 | 6 | 費用対効果は低い。外部依存のまま許容する選択もあり |

## 6. 判断が必要な点(未決)

- **目標の水準。** 「完全に DNS 不要」か「DNS が落ちても既存の agent が動き続け、検証できる」か。後者なら A + B の一部で足りる。前者は入口の再設計と新規ユーザーの発見(discovery)まで含み、桁違いに重い。
- **security-first との両立。** ADR-2608130100 は「ローカルで動くこと」自体を許可根拠にしない。ピア複製は「別マシンへの egress」なので、同じ審査(暗号化トランスポート・認証情報・全体スイッチ)を通す設計が必要。複製先を自分の murakumo ノードに限る、が自然な既定。
- **Holochain 互換を目指すか。** happ そのものにするのではなく、同等の性質(§2)を kotoba/murakumo 上で満たす方が既存資産に合う、というのが本メモの前提。

## 7. 確認できていないこと

- 本メモは **コードと ADR の読解**に基づく。lattice や `latest` の IPNS publish の**現在の稼働状態は確認していない**(ADR-0092 の計測は 2026-09-02 時点)。
- `kekkai-node` / `shinkansen` / `cloud-kotoba-dds` が実際に実行経路でどこまで使われているかは、依存宣言の確認に留まる。
- `app-itonami*`(genko / inkan / nle / rirekisho / shirohan / tsukuru)や `cloud-itonami-api` / `cloud-itonami-app-worktrees` は対象外。
