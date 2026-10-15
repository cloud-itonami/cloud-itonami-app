# hermes/ — the resident bots that act for this repository

This directory is the **source of truth** for the Hermes profiles listed below
(ADR-2609241200). The host's `~/.hermes/profiles/<profile>` is materialized
from `hermes/profiles/<profile>/` and checked against it:

```
kbb --backend sci scripts/hermes-profile-repo.cljk materialize <profile>   # repo -> host
kbb --backend sci scripts/hermes-profile-repo.cljk check <profile>         # 0 agree / 1 drift / 2 could not compare
kbb --backend sci scripts/hermes-profile-repo.cljk export <profile>        # host -> repo, then commit
```

(run from the com-junkawasaki/root superproject; registry
`manifest/hermes-profile-repos.edn`.)

Each profile directory holds SOUL.md, profile.yaml, config.yaml (host-local
blocks removed), cron/jobs.json (definitions only), scripts/ and the skills the
profile owns. **Never here:** `.env` or any secret value, workspace/ledgers,
sessions, memories, logs, caches, run state.

## Profiles

| profile | description |
|---|---|
| `itonami-ad-operator` | aozora PR 掲載・反応測定・メール掲載交渉の広告運用代行。専用 agency MCP |
| `itonami-sales-agent` | メール営業・返信・追客と aozora 暗号化 lead 管理の営業代行。専用 agency MCP |
| `itonami-hermes-bridge` | Hermes ⇔ Itonami 互換（ADR-0143: API・Itonami profile・registry）を保ち、引き継ぎリストを 1 項目ずつ PR にする。propose only |
| `itonami-dispatch` | cloud-itonami 各 business の bot 群を murakumo fleet 上で分散稼働させる dispatch bot。kanban |
| `itonami-maint` |  |
| `itonami-winfix` |  |
| `itonami-winfix-qa` | itonami qa |
| `refactor` | refactor — clj, cljc を kotoba に refactor するよ. jvm 依存を外して, amu, kotoba |
| `reuse-operations` | 不用品回収・遺品整理・リユース／リサイクル事業の運営支援（source profile、接続・定期実行は別途設定） |

Agency setup, permissions and qualification boundaries: [docs/agency-bots.md](../docs/agency-bots.md).
These two profiles are separate from the tool-free `itonami-sales` / `itonami-mktg` bridge roles.
