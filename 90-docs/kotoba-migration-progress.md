# Clojure から Kotoba へのリファクタリング計画

## 概要

**リポジトリ**: cloud-itonami/cloud-itonami-app  
**対象**: src/cloud/itonami/app/ 内の Clojure ファイル  
**総規模**: 233 ファイル  
**移行済**: 21 ファイル (.kotoba, 2070 行)  
**移行率**: 9%

## 移行パターン

```clojure
;; health_core.kotoba (Kotoba decision core)
(ns cloud.itonami.app.health)
(defn health-route? [method path]
  (and (= method "GET") (= path "/health")))

;; health.cljc (Host wrapper)
(ns cloud.itonami.app.health
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

(defn health-route? [method path]
  (oracle/call :health 'health-route? [(str method) (str path)]))
```

⚠ 実測の注意 (2026-09-07): 現在の移行済 21 .kotoba は**どれも pure の
7 head (lam/app/ref/perform/rel/query/handle) を一切使っていない。**
全部 clojure-shaped (`defn` / `and` / `let` / `if` / `string=?`) で、
さらに `:i64` / `:bool` / `:string` の **word-typed slice** (native 到達可能) に
自家制限している (`health_core.kotoba` / `store_core.kotoba` から確認)。

したがって現在の 9% は「pure S-expr への移行」ではなく
「clojure-shaped の native-word スライスへの移行」を実測している。
pure コアの文法権威 (adt 7 head) は 2026-09-06 時点で admitted 済みだが、
移行ファイルはまだその面を使っていない。区別して進める:

- **`lam` / `app` / `ref` / `perform`** — wasm32 到達・identity 実測済み。
  移行 slice にはこの 4 head を積極的に使える。
- **`rel` / `query` / `handle`** — `rel`/`query` は KIR のみで wasm32 未達
  (kgraph lowering gap)。`handle` は `:abort` の消去のみで capability call を
  包めない。現行 slice では使って wasm を出せない。手を付けない。
- **`ref` の STM 衝突**: モジュール自身の top-level 定義名を参照する時だけ
  admitted。変数名・引数名に使うと `:ambient-forbidden` で落ちる。
  pure 形で `ref` を普通の名前に使わない。

## 優先順位

### Phase 1: 単純な decision core（既に移行済み）
- `health_core.kotoba` - 35 行
- `tls_binding_core.kotoba` - 33 行
- `oauth_resource_core.kotoba` - 18 行

### Phase 2: 中規模 business logic（移行中）
- `bot_core.kotoba` - 337 行
- `model_routing_core.kotoba` - 142 行
- `store_core.kotoba` - 84 行

### Phase 3: 複雑な integration
- `account_link_sync.clj`
- `payment_settlement_actor.clj`
- `email_login.clj`

## 次のステップ

1. Phase 2 の残りの namespace を移行
2. 各遷移にチェックポイント（native 対応可否、test 整合性）
3. Phase 3 の移行方針を具体化
4. 移行完了時に、既存 clj/cljc の削除または残存判断

## pure S-expr としての完成度分析（2026-09-07）

`kotoba-lang/kotoba-lang` の grammar authority + surface-status + 裏付けテストを
突き合わせた結果（実ファイルを読み、両方向のテストで確認）。

### 結論

**pure コアの 7 特殊形式は、2026-09-06 時点で「文法権威に全 7 件 admitted」に達
している。** ただし 3 つの現実的な残課題がある:

1. **Step 2 (alpha-normalisation) が未着地** — `.cljk` と pure のコア形式で
   **同一 Definition CID** を打刻するのが ADR-544 §3 のゴール。現状
   `(app (lam [x] (+ x 1)) n)` と `(let [g (fn [x] …)] (g n))` は wasm32
   artifact は同一だが KIR hash が異なる。「同じ wasm = CID 同一」とは読まない
   （surface-status 自身が明記）。
2. **`rel` / `query` は KIR まで。wasm32 未達** (`(kgraph-get 1 2)` 単独が
   `:wasm-local-encoding` で失敗)。kgraph lowering 由来の継承された backend gap。
   この head 自体の阻止ではない。
3. **`query` は point read であって関係代数の query ではない** — relational
   pattern form は言語にまだ無い。「将来の head」と命名されている。
   `query` が欠落を wrap しない `[:option T]`: 欠落時に i64 MIN を返すため、
   option が保証する total 性を store が持っていない。

また `perform` / `handle` は対 (pair) ではない: `perform` は host が応答する
capability effect を導入する。`handle` は `:abort` ability の消去のみ。
capability call は wrap できない (cap-call が effect row に `:abort` を寄与せず、
`try` は逃す対象が無いため。実測済み)。

これは 2026-09-07 時点の記録であり、将来動いたらその時点で測り直す。

## 進捗記録

| 日付 | 移行数 | 行数 |
|------|--------|------|
| 2026-09-07 | 21 | 2070 |