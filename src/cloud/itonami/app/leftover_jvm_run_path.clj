(ns cloud.itonami.app.leftover-jvm-run-path
  "Refuse leftover JVM aliases. Not a server, not MCP, not the CLI.

  `:server`, `:mcp`, and `:cli` used to start this app. They must not remain
  the way to start it. The run path is the nbb host that loads the guest
  wasm (`bin/cloud-itonami-server`, `bin/itonami-mcp`). `bin/itonami` no
  longer `spawnSync`s `clojure -M:cli`.")

(def message
  (str "leftover JVM run path is closed.\n"
       "server: nbb --classpath bin bin/cloud-itonami-server\n"
       "mcp:    nbb --classpath bin bin/itonami-mcp\n"
       "cli:    leftover; bin/itonami does not start clojure -M:cli"))

(defn -main [& _]
  (binding [*out* *err*]
    (println message))
  (System/exit 2))
