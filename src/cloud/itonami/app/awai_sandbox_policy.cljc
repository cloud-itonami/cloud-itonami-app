(ns cloud.itonami.app.awai-sandbox-policy
  "Policy for the read-only static bundle surface inside an opaque app iframe.

  App documents use an opaque origin. Their non-credentialed static module and
  font requests serialize that origin as `null`; no host API route uses this
  policy. Network and host effects remain denied by the response CSP.")

(defn static-origin-allowed?
  "Allow initial iframe navigation, the opaque sandbox origin, or the exact
  app asset origin. Use only on CID-verified static GET responses."
  [expected-origin request-origin]
  (and (string? expected-origin)
       (or (nil? request-origin)
           (= "null" request-origin)
           (= expected-origin request-origin))))

(defn content-security-policy [app-origin parent-origin]
  (when (and (string? app-origin)
             (string? parent-origin)
             (not (re-find #"[;\r\n]" app-origin))
             (not (re-find #"[;\r\n]" parent-origin)))
    (str "default-src 'none'; "
         ;; Apply the sandbox to the document response itself too. The iframe
         ;; attribute is not present when someone opens the per-app URL
         ;; directly, so response-level sandboxing must preserve the opaque
         ;; origin and deny top-level navigation in both cases.
         "sandbox allow-scripts; "
         "script-src " app-origin " 'wasm-unsafe-eval'; "
         "style-src " app-origin " 'unsafe-inline'; "
         "img-src " app-origin " data:; "
         "font-src " app-origin " data:; "
         "media-src " app-origin "; connect-src 'none'; "
         "frame-src 'none'; child-src 'none'; worker-src 'none'; "
         "object-src 'none'; base-uri 'none'; form-action 'none'; "
         "frame-ancestors " parent-origin)))
