(ns cloud.itonami.app.loopback-origin
  "The socket bind and the browser origin are not the same name.

  This process listens on 127.0.0.1. WebAuthn RP ID, the auth.itonami.cloud
  native client (`localhost:1338` callback), and the session cookie all
  require the name `localhost`. Cookie-borne POSTs from the IP still 403
  (`require-origin!`). Hosted sign-in itself is a GET navigation
  (`/api/auth/itonami/start`, ADR-0042) that always uses the localhost
  callback, so clicking the entrance from the bind address still opens
  auth.itonami.cloud.

  The document (`GET /`) is still sent to localhost on the same port so the
  signed-in app and WebAuthn share one name. Other API routes stay put so
  probes against the bind address keep working."
  (:require [clojure.string :as str])
  (:import [java.net URI]))

(defn- host-parts [host]
  (let [h (str host)]
    (cond
      (or (= h "::1") (= h "[::1]"))
      {:name "::1" :port nil}

      (re-matches #"\[::1\]:(\d+)" h)
      {:name "::1" :port (second (re-matches #"\[::1\]:(\d+)" h))}

      :else
      (let [[name port] (str/split (str/lower-case h) #":" 2)]
        {:name name :port port}))))

(defn document-redirect
  "Location for the HTML document when Host is the loopback IP and this
  deployment's public origin is localhost. Nil means do not redirect."
  [{:keys [method host path public-origin]}]
  (when (and (#{"GET" "HEAD"} method)
             (= "/" path)
             (string? host)
             (string? public-origin))
    (try
      (let [public (str/replace public-origin #"/+$" "")
            uri (URI. public)
            public-host (some-> (.getHost uri) str/lower-case)
            public-port (let [p (.getPort uri)]
                          (when (pos? p) (str p)))
            {:keys [name port]} (host-parts host)]
        (when (and (= "localhost" public-host)
                   (contains? #{"127.0.0.1" "::1"} name))
          (str "http://localhost:" (or port public-port "1338") "/")))
      (catch Exception _ nil))))
