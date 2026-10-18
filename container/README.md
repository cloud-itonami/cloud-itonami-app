# itonami agent container (JUN-77 — Mac から aiueos VM へ)

`itonami agent CLI`（= cloud-itonami-cli の kbb クライアント + cloud-itonami-app の
resident host）を aiueos VM 上の container で動かすための image 一式。

## 形態（実測ベース、2026-10-18）

- **JVM は不要**（PR #270 以降の実装）: CLI は classpath-free な kbb クライアント、
  server は `bin/cloud-itonami-server` = Node http ホスト + `server_main.wasm`
  guest（listen 判定は guest 側、port 1338）。
- kbb v2 は SCI-on-Node（`kotoba-lang/kotoba/bin/kbb` → `org-babashka-nbb`）。
- data は `CLOUD_ITONAMI_DATA_DIR`（本 image では /data、volume）。enrollment key
  (`agent-enrollment.key`, 0600) が load-bearing。image に焼き込まない。

## build & run

```sh
container/assemble-context.sh          # context 組み立て（app/cli/kbb/nbb を sibling 配置）
podman build -f container/Containerfile -t itonami-agent container/
podman run --rm itonami-agent cli commands        # CLI 疎通（server 不要）
podman run -d -p 1338:1338 -v itonami-data:/data itonami-agent serve
```

## guest wasm の provenance

`server_main.wasm` は image に含めない。`bin/compile-amu` を compiler pin
（`compiler-pin.edn`）どおりに走らせて発行された artifact を
`/data/guest/server_main.wasm` に mount する。builder stage への組み込みは
amu launcher image が整ってから（JUN-34 の VM boot 後に検証）。

## 運用上の注意

- secrets（kagi / service token）は env か volume で注入。image / commit に含めない。
- 初回起動時に resident が `agent-enrollment.key` を /data に発行する。
- VM 側の container runtime は docker または podman（murakumo-ci-6600hs に docker
  実在を確認、sudo 必要）。
