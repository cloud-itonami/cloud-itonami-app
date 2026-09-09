(ns cloud.itonami.app.controller-standby
  "Health-only migration listener. Never loads application state or credentials."
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def health-body
  "{\"ok\":true,\"service\":\"cloud-itonami-app\",\"mode\":\"standby\",\"executionEnabled\":false}")

(defn start! [port]
  (when-not (and (integer? port) (<= 0 port 65535))
    (throw (ex-info "Invalid standby port" {:port port})))
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (let [health? (and (= "GET" (.getRequestMethod exchange))
                            (= "/health" (.getPath (.getRequestURI exchange))))
               body (.getBytes (if health? health-body
                                   "{\"error\":\"controller_standby\",\"executionEnabled\":false}")
                               StandardCharsets/UTF_8)]
           (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
           (.set (.getResponseHeaders exchange) "Cache-Control" "no-store")
           (.sendResponseHeaders exchange (if health? 200 503) (alength body))
           (with-open [out (.getResponseBody exchange)] (.write out body))))))
    (.start server)
    server))

(defn -main [& _]
  (let [port (Long/parseLong (or (System/getenv "CLOUD_ITONAMI_CONTROLLER_PORT") "1438"))
        server (start! port)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(.stop server 0)))
    (println (str "cloud-itonami controller standby on 127.0.0.1:" port
                  "; execution disabled"))))
