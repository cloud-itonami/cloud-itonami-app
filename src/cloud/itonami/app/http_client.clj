(ns cloud.itonami.app.http-client
  "JVM HTTP client facade for cloud-itonami-app — delegates to
  kotoba.net.jvm-host (the workspace's single java.net.http site).

  Exposes the request-map style the app's http call sites use:
    {:url string :method :get|:post|:put|:patch|:delete
     :headers {string string} :body string}
    -> {:status int :body string}

  Migrating a call site: replace the builder+send block with

    (http/request {:url url :method :post :headers headers :body body})

  The adapter fails closed on unknown methods and applies a 120s timeout
  unless :timeout-seconds is overridden."
  (:require [kotoba.net.jvm-host :as jvm-host]))

(defn request
  "Perform an HTTP request via the workspace transport. See ns docstring."
  ([req] (request req {}))
  ([{:keys [timeout-seconds] :as req} _opts]
   ((jvm-host/http-transport {:timeout-seconds (or (:timeout-seconds req) 120)})
    req)))
