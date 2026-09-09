(ns cloud.itonami.app.http-client
  "JVM HTTP client facade for cloud-itonami-app — delegates to
  kotoba.lang.http.host.jvm (the workspace's single java.net.http site).

  Exposes the request-map style the app's http call sites use:
    {:url string :method :get|:post|:put|:patch|:delete
     :headers {string string} :body string}
    -> {:status int :headers {lowercase-name value} :body string}

  Migrating a call site: replace the builder+send block with

    (http/request {:url url :method :post :headers headers :body body})

  The adapter fails closed on unknown methods and applies a 120s timeout
  unless :timeout-seconds is overridden.

  ## Why the namespace changed

  This shim was written on 2026-09-07 against `kotoba.net.jvm-host`, and that
  namespace did not exist -- not in this workspace, and not anywhere on GitHub
  (searched 2026-09-09). The migration landed the 35 call sites without the
  transport they delegate to and without a dependency that could carry it, so
  every one of the 36 namespaces that require this file failed to load, the
  application could not start, and the Windows launch smoke was red on every
  run for two days.

  The transport now exists as `kotoba.lang.http.host.jvm` in kotoba-lang/http
  -- which is the repository that owns HTTP hosts (`host/babashka`,
  `host/httpkit` were already there) rather than `kotoba.net.*`, which belongs
  to io-libp2p. It adds no dependency of its own: java.net.http is in the JDK."
  (:require [kotoba.lang.http.host.jvm :as jvm-host]))

(defn request
  "Perform an HTTP request via the workspace transport. See ns docstring."
  ([req] (request req {}))
  ([{:keys [timeout-seconds] :as req} _opts]
   ((jvm-host/http-transport {:timeout-seconds (or (:timeout-seconds req) 120)})
    req)))
