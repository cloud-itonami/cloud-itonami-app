(ns cloud.itonami.app.controller-entry
  "Choose standby before loading the application's state-bearing namespaces.")

(defn entry-symbol [mode]
  (case mode
    (nil "" "active") 'cloud.itonami.app.server/-main
    "standby" 'cloud.itonami.app.controller-standby/-main
    (throw (ex-info "Unknown controller mode" {:mode mode}))))

(defn -main [& args]
  (apply (requiring-resolve
          (entry-symbol (System/getenv "CLOUD_ITONAMI_CONTROLLER_MODE"))) args))
