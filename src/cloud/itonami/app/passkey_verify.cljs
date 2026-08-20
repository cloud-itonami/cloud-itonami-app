(ns cloud.itonami.app.passkey-verify
  "Passkey ceremony crypto on WebCrypto. ClojureScript only (ADR-0065).

  Thin wrapper over `webauthn.adapters.edge` so this app does not grow a
  second verifier. Storage and DID binding stay in the host that called us."
  (:require [webauthn.adapters.edge :as edge]))

(defn verify-registration!
  "Promise of edge registration result. Caller owns single-use challenge."
  [config payload]
  (edge/verify-registration! config payload))

(defn verify-authentication!
  "Promise of edge authentication result."
  [config payload]
  (edge/verify-authentication! config payload))
