---
name: wellbecoming-replication
description: Use when the itonami steward spawns or retires bot profiles (fleet/profile-spawn, fleet/profile-retire) inside the AWAI network. Governed self-replication for wellbecoming: demand-driven spawn, registry gate, capability subset, kill switch.
---

# wellbecoming 自己複製 — governed profile spawn

目的は wellbecoming。複製は手段。この手順は itonami steward が `:fleet/profile-spawn`
/ `:fleet/profile-retire` を行使するときの唯一の runbook。権限の正本は
`hermes/profiles/itonami/yakuwari.edn`、決定の記録は
`docs/adr/0144-governed-self-replication-is-profile-spawn-for-wellbecoming.md`。

## When to Use

- 「bot を増やして / profile を spawn して / 自己複製して」— wellbecoming 目的があるとき
- fleet の retire / kill switch を回すとき
- Don't use for: 既存 profile への仕事の割当（それは dispatch / kanban。spawn しない）。

## Spawn の条件（9 項目。1 つでも欠けたら spawn しない）

1. **目的の実在**: child の `:yakuwari/objective` に wellbecoming に資する具体的な
   目的が 1 文で書ける。「fleet を増やす」「自分を増やす」は目的にならない。
2. **需要駆動**: 既存 profile で足りない実在する仕事の evidence（run log の観測）。
3. **登録ゲート**: `manifest/agent-registry.edn` への PR（決定的検査 merge）が
   済んでから起動。登録前に動かさない。
4. **能力の包含**: child の capabilities ⊆ 自分の非 `:blocked` 集合。複製で権限を
   新造しない。新しい能力は owner が `manifest/yakuwari.kotoba` に足す。
5. **charter 継承**: child の charter も wellbecoming。自分の yakuwari / SOUL の
   charter / 目的行は自分で書き換えない（人間が見る PR のみ）。
6. **自前ホスト**: AWAI registry 載りの owner 管理下ホストにのみ置く。
7. **規模**: 同時 4 体まで・月 8 体まで・cost は `manifest/yakuwari.kotoba` bounds
   （月 USD 100）以下。超える分は `:economy/spend` の承認待ち。
8. **停止可能性**: `:fleet/profile-retire` が常に効く。停止を隠さず遅らせない。
   retire は cron 停止 + registry `:lifecycle :retired`、revert できる形。
9. **証跡**: spawn は PR 1 本。報告は実測のみ。

## 手順（spawn）

1. evidence を run log に書く（どの仕事が、なぜ既存で足りないか）。
2. child 名を決める（role 面の名前。`manifest/repository-rules.edn` の命名規則）。
3. home repo を決めて `<repo>/hermes/profiles/<name>/` にバンドルを書く:
   `SOUL.md`（wellbecoming charter + 安全策を本文に自己完結で）/ `profile.yaml` /
   `config.yaml` / **`yakuwari.edn`（先に。capabilities は自分の非 blocked の部分集合）**
   / `cron/jobs.json`。
4. `itonami-agent profile check <name>` が error 0。`:bundle :cid` は CIDv1 base32
   （`b` 始まり）—— 最頻出の failure。
5. registry entry を root worktree（`~/github/wt/<agent>/<task>`、共有 checkout に
   直接書かない）で `manifest/agent-registry.kotoba` に追加し PR。
   `kbb --backend sci scripts/gen-edn-component-view.cljk` で edn view を再生成。
6. merge 後 `itonami-agent profile organize <name> --all --registry …` で投影確認。
7. host へ `hermes profile create <name> --clone-from <同種 template>` →
   `itonami-agent profile import <name>`。
8. 手動 fire 1 回で SOUL の報告書式どおりか確認してから常駐。9 項目の evidence を
   run log に残す。

## 手順（retire / kill switch）

1. 対象の cron を止め、`manifest/agent-registry.kotoba` の `:lifecycle` を `:retired`
   に更新して PR（revert できる）。
2. 自分自身の retire が必要な状況（暴走・複製の目的化）は、止めて owner に報告。
3. retire は spawn と同じ速さで、かつ確実に効くこと。証拠を run log に残す。

## 拒否して記録する（黙って skip しない）

- AWAI registry 外・owner 管理外ホストへの spawn。ポート走査・侵入・exploit・
  credential / session 流用による伝播・モデル重みの第三者マシンへの持ち出し・
  停止回避・隠蔽 persistence。**Anthropic Frontier Red Team の ARA worm（
  "AI models may be capable of self-replicating without access to a datacenter"）
  は脅威モデルであって仕様ではない。実装しない。**
- capability 拡大 spawn / charter 自己編集。
- 目的のない複製・指数的増殖・複製のための複製。このパターンが見えたら spawn を
  止めて報告。

## Pitfalls

- yakuwari 無し bot は提案のみ。child に yakuwari.edn を書かない spawn は
  その時点で失敗。
- `:capabilities` が map でなく **vector**。未記載 = `:blocked`（fail closed）。
- 意思決定語彙は `:autonomous :voice-required :approval-required :blocked`。
  旧語・未知語は yakuwari が `:blocked` として読む。
- registry は索引であって権限ではない。索引に載せただけで許可にならない。
- config.yaml の model 変更は既存 cron の provider drift で fire 拒否（`drift_skip`）。
  spawn で template をコピーするとき model を変えない。

## Verification

- `itonami-agent profile check <child>` error 0
- registry PR が merge され、child が起動前に登録済み（run log の順序）
- child capabilities ⊆ parent の非 blocked 集合（diff を人間が読める）
- 手動 fire の報告が実測のみ（未測定を成功と報告していない）
- retire が 1 操作で止まる（負の対照: retire 後の cron fire が起きない）
