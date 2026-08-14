(ns cloud.itonami.app.tls-binding
  "The certificate decisions that are not TLS.

  `tls_certificate.clj` has to be JVM: `KeyStore`, `SSLContext`, `HttpsServer`
  and the Keychain are all platform. These are not — whether a request path is
  an ACME challenge, and whether a certificate should be replaced — and this
  repository's runtime order says they should not be written as though they
  were."
  (:require [clojure.string :as str]
            [cloud.itonami.app.domain-name :as domain-name]))

(def challenge-prefix "/.well-known/acme-challenge/")

(def ^:private token-pattern #"[A-Za-z0-9_-]{16,256}")

(defn challenge-token
  "The token in an ACME challenge path, or nil for any other path.

  The pattern is the guard: a token is a token and not a path, so `..` and a
  slash are refused here rather than by whatever reads it next."
  [path]
  (when (and (string? path) (str/starts-with? path challenge-prefix))
    (let [token (subs path (count challenge-prefix))]
      (when (re-matches token-pattern token) token))))

(defn renewal-due?
  "Whether a certificate expiring at `not-after` should be replaced by `now-ms`.

  ONE decision. It was two — a nil check and a catch — reaching the same answer
  by different routes, so inverting either changed nothing any test could see.
  A break test found that, and collapsing them is what made it findable: no
  readable expiry is a single fact, and a certificate whose expiry this process
  cannot read is not one it can promise anything about."
  [not-after now-ms window-ms]
  (let [expiry (domain-name/epoch-ms not-after)]
    (or (nil? expiry) (> (+ now-ms window-ms) expiry))))
