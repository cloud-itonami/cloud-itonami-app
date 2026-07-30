(ns cloud.itonami.app.mcp-stdio
  "Newline-delimited MCP stdio transport.

  Stdout is reserved exclusively for JSON-RPC messages. The process boundary
  supplies the actor through CLOUD_ITONAMI_MCP_STDIO_ACTOR."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.mcp :as mcp]
            [cloud.itonami.app.store :as store])
  (:import [java.io BufferedReader InputStreamReader]
           [java.nio.charset StandardCharsets]))

(defn- authorized-actor! [actor]
  (let [user (get-in (store/snapshot) [:identity :users actor])]
    (when-not (and (string? actor) (not (str/blank? actor))
                   user (:passkey-enrolled? user))
      (throw (ex-info "MCP stdio actor is not a Passkey account."
                      {:type :mcp/actor-invalid})))
    actor))

(defn process-line
  "Process one stdio frame. Notifications intentionally produce no output."
  [configuration actor line]
  (let [request (json/read-str line)
        _ actor
        response (mcp/respond configuration request)]
    (when (contains? request "id")
      (json/write-str response))))

(defn- error-response [line error]
  (let [request-id
        (try
          (get (json/read-str line) "id")
          (catch Exception _ nil))]
    (json/write-str
     {"jsonrpc" "2.0" "id" request-id
      "error" {"code" -32603
               "message" (or (.getMessage error) "MCP stdio error")}})))

(defn -main [& _]
  (try
    (let [actor (authorized-actor!
                 (System/getenv "CLOUD_ITONAMI_MCP_STDIO_ACTOR"))
          configuration (config/load-config)
          reader (BufferedReader.
                  (InputStreamReader. System/in StandardCharsets/UTF_8))]
      (doseq [line (line-seq reader)
              :when (not (str/blank? line))]
        (try
          (when-let [response (process-line configuration actor line)]
            (println response)
            (flush))
          (catch Exception error
            (println (error-response line error))
            (flush)))))
    (catch Exception error
      (binding [*out* *err*]
        (println (.getMessage error)))
      (System/exit 1))))
