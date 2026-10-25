# itonami agent container (JUN-77 — Mac から aiueos VM へ)

`itonami agent CLI`（= cloud-itonami-cli の kbb クライアント + cloud-itonami-app の
resident host）を aiueos VM 上の container で動かすための image 一式。

## 形態（実測ベース、2026-10-18）

- **実行は JVM 不要**（PR #270 以降の実装）。ただし image には JRE を入れている —
  nbb→bb→tools.deps の**依存解決だけ**が `java` を呼ぶため（実測: 『Couldn't find
  'java'』で cold 解決が失敗）。build 時に warm-up 済みなので実行時に解決は走らない: CLI は classpath-free な kbb クライアント、
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

実測（2026-10-18）: `AMU=<amu launcher> kbb --backend sci --classpath bin bin/compile-amu`
で `target/amu/server_main.wasm`（3,434 bytes）ほかが provenance 付きで発行される。
server は `<app>/target/amu/server_main.wasm` を読むので、それを mount する:

```sh
podman run -d --name itonami-serve -v $PWD/target/amu:/opt/itonami/cloud-itonami/cloud-itonami-app/target/amu:ro \
  -v itonami-data:/data itonami-agent:dev serve
podman exec itonami-serve curl -s http://127.0.0.1:1338/health   # → 200 guest-wasm
podman exec itonami-serve itonami status                          # → :answering? true
```

⚠ server は意図的に **127.0.0.1 bind**（loopback host）。`-p 1338:1338` の
port publish では外から届かない（設計どおり）。CLI/監視は同一 container 内の
exec、または同一 pod の sidecar から。

`server_main.wasm` は image に含めない。`bin/compile-amu` を compiler pin
（`compiler-pin.edn`）どおりに走らせて発行された artifact を
`/data/guest/server_main.wasm` に mount する。builder stage への組み込みは
amu launcher image が整ってから（JUN-34 の VM boot 後に検証）。

## 運用上の注意

- secrets（kagi / service token）は env か volume で注入。image / commit に含めない。
- 初回起動時に resident が `agent-enrollment.key` を /data に発行する。
- VM 側の container runtime は docker または podman（murakumo-ci-6600hs に docker
  実在を確認、sudo 必要）。
