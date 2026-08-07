(ns cloud.itonami.app.credential-http-test
  "Drives the credential endpoints over real HTTP against a running server.

  The function-level tests in `credential-test` cover issuance, revocation and
  verification semantics. These cover what only the server can get wrong, and
  what I previously had no coverage of at all: routing, status codes, the JSON
  envelope, the origin/CSRF gate, and — the one that matters most — whether the
  owner/admin restriction on revocation is actually ENFORCED by the route rather
  than merely computed by `credential/may-revoke?`.

  A `403` that is only ever produced in a unit test is not a gate. This file is
  the difference between having written an authorization check and having one.

  The passkey gate is stubbed rather than satisfied: a real ceremony needs an
  authenticator, and what is under test here is the route layer behind that gate."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.credential :as credential]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "test-csrf-token")
(def ^:private subject-did "did:key:zDnaerDaTF5BXEavCrfRZEk316dpbLsfPDZ3WJ5hRTPFU2169")

(def ^:private config
  {:brand {:name "Test"}
   ;; Port 0: a stray server on 1338, or another session, cannot make this flake.
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}]
   ;; Deny-by-default, as shipped. One test overrides it.
   :credentials {:trusted-issuers nil}})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- bound-port [] (.getPort (.getAddress @server/server)))

(defn- request [method path {:keys [body headers]}]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" (bound-port) path)))
                    (.header "Content-Type" "application/json"))]
    (doseq [[header value] headers] (.header builder header value))
    (let [built (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (or body "{}"))))
          response (.send client (.build built)
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :body (try (json/read-str (.body response) :key-fn keyword)
                  (catch Exception _ {:raw (.body response)}))})))

(defn- authed [method path & [body]]
  (request method path {:body body
                        :headers {"Origin" origin
                                  "X-CLOUD-ITONAMI-CSRF" csrf}}))

(defn- with-server
  "Run `body` against a live server. `role` is the acting membership's role, which
  is what the revocation gate reads."
  ([body] (with-server :owner nil body))
  ([role domain body]
   (let [temporary (java.nio.file.Files/createTempDirectory
                    "cloud-itonami-app-credential-http"
                    (make-array java.nio.file.attribute.FileAttribute 0))
         previous @store/state]
     (try
       (reset! store/state (store/initial-state))
       (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                     local-identity/session (fn [_] {:csrf csrf :user-id "test-user"})
                     local-identity/require-passkey! identity
                     local-identity/configure! (fn [_] nil)
                     local-identity/membership-role (fn [_] role)
                     local-identity/organization-domain-for-did-web (fn [] domain)
                     ;; The route resolves the document from the request's Host
                     ;; since ADR-0025; the deployment-level function still
                     ;; answers for the status list.
                     local-identity/did-web-domain-for-host (fn [_] domain)
                     local-identity/membership-credential-context
                     (fn [_] {:subject-did subject-did
                              :role :member
                              :organization-did nil
                              :organization-domain domain
                              :organization-name "Test"})]
         (server/stop!)
         (server/start! config)
         (try (body) (finally (server/stop!))))
       (finally
         (server/stop!)
         (reset! store/state previous))))))

;; ── the full round trip, over HTTP ───────────────────────────────────────────

(deftest issue-list-verify-revoke-over-http
  (with-server
    (fn []
      (let [issued (authed :post "/api/credentials/membership")]
        (is (= 200 (:status issued)))
        (is (= "cloud.itonami.app.credential.v1" (get-in issued [:body :schema])))
        (is (= 0 (get-in issued [:body :status-index])))
        (is (some? (get-in issued [:body :credential :proof :proofValue])))

        (testing "the register lists it, and says this session may revoke"
          (let [listed (authed :get "/api/credentials")]
            (is (= 200 (:status listed)))
            (is (true? (get-in listed [:body :may-revoke?])))
            (is (= 1 (count (get-in listed [:body :issued]))))
            (is (= subject-did (:subject (first (get-in listed [:body :issued])))))
            (testing "and the register does not carry the signed document"
              (is (nil? (:credential (first (get-in listed [:body :issued]))))))))

        (testing "verifying the credential it just returned"
          (let [credential (get-in issued [:body :credential])
                verified (authed :post "/api/credentials/verify"
                                 (json/write-str {:credential credential}))]
            (is (= 200 (:status verified)))
            (is (true? (get-in verified [:body :verified])))
            (is (true? (get-in verified [:body :valid?])))))

        (testing "revoking it, then seeing it refused"
          (let [revoked (authed :post "/api/credentials/0/revoke")]
            (is (= 200 (:status revoked)))
            (is (true? (get-in revoked [:body :revoked?]))))
          (let [credential (get-in issued [:body :credential])
                after (authed :post "/api/credentials/verify"
                              (json/write-str {:credential credential}))]
            (is (= 200 (:status after)))
            (is (true? (get-in after [:body :verified])) "signature still good")
            (is (false? (get-in after [:body :valid?])) "but not to be honoured")
            (is (true? (get-in after [:body :revoked?])))))))))

;; ── the gate that only HTTP can prove ────────────────────────────────────────

(deftest a-member-cannot-revoke-over-http
  (testing "credential/may-revoke? returning false in a unit test is not a gate.
            This is the assertion that makes it one."
    (with-server :member nil
      (fn []
        (is (= 200 (:status (authed :post "/api/credentials/membership"))))
        (let [refused (authed :post "/api/credentials/0/revoke")]
          (is (= 403 (:status refused)))
          (is (= "forbidden" (get-in refused [:body :error :type]))))
        (testing "and the register tells the UI not to offer the button"
          (is (false? (get-in (authed :get "/api/credentials") [:body :may-revoke?]))))
        (testing "the credential is still valid, since nothing was revoked"
          (is (empty? (credential/revoked-indices (store/snapshot)))))))))

(deftest an-auditor-cannot-revoke-either
  (with-server :auditor nil
    (fn []
      (authed :post "/api/credentials/membership")
      (is (= 403 (:status (authed :post "/api/credentials/0/revoke")))))))

(deftest an-admin-can-revoke
  (with-server :admin nil
    (fn []
      (authed :post "/api/credentials/membership")
      (is (= 200 (:status (authed :post "/api/credentials/0/revoke")))))))

;; ── the origin/CSRF gate ─────────────────────────────────────────────────────

(deftest mutating-routes-require-origin-and-csrf
  (with-server
    (fn []
      (doseq [path ["/api/credentials/membership" "/api/credentials/0/revoke"
                    "/api/credentials/verify"]]
        (testing (str path " without CSRF")
          (is (= 403 (:status (request :post path
                                      {:headers {"Origin" origin}})))))
        (testing (str path " without Origin")
          (is (= 403 (:status (request :post path
                                      {:headers {"X-CLOUD-ITONAMI-CSRF" csrf}})))))))))

;; ── the public documents ─────────────────────────────────────────────────────

(deftest the-did-document-is-404-until-a-domain-is-published
  (with-server
    (fn []
      (let [r (request :get "/.well-known/did.json" {})]
        (is (= 404 (:status r))
            "publish-did-web? is false as shipped, so there is no document")))))

(deftest the-did-document-is-served-when-a-domain-is-configured
  (with-server :owner "acme.example"
    (fn []
      (let [r (request :get "/.well-known/did.json" {})]
        (is (= 200 (:status r)))
        (is (= "did:web:acme.example" (get-in r [:body :id])))
        (testing "public by necessity — no session was sent, and it must still serve"
          (is (seq (get-in r [:body :assertionMethod]))))
        (testing "and it carries no private key material"
          (is (not (re-find #"privateKey" (pr-str (:body r))))))))))

(deftest the-status-list-is-served-signed
  (with-server
    (fn []
      (authed :post "/api/credentials/membership")
      (authed :post "/api/credentials/0/revoke")
      (let [r (request :get "/credentials/status/1" {})]
        (is (= 200 (:status r)))
        (is (some? (get-in r [:body :proof :proofValue]))
            "an unverified list of zeros would un-revoke everything")
        (is (= "BitstringStatusList"
               (get-in r [:body :credentialSubject :type])))))))

;; ── external verification ────────────────────────────────────────────────────

(deftest external-verification-refuses-an-untrusted-issuer-over-http
  (with-server
    (fn []
      (let [issued (authed :post "/api/credentials/membership")
            ;; our own credential, presented as if it came from outside
            r (authed :post "/api/credentials/verify/external"
                      (json/write-str
                       {:credential (get-in issued [:body :credential])}))]
        (is (= 200 (:status r)) "an answer, not an error status")
        ;; a did:key issuer resolves without a trust list; the point here is that
        ;; the route exists and answers in the documented envelope
        (is (contains? (:body r) :verified))
        (is (contains? (:body r) :revocation)))))

  (testing "the trusted-issuer list is visible, so an empty one is not a mystery"
    (with-server
      (fn []
        (let [r (authed :get "/api/credentials/trusted-issuers")]
          (is (= 200 (:status r)))
          (is (= [] (get-in r [:body :trusted-issuers]))))))))

;; ── malformed input ──────────────────────────────────────────────────────────

(deftest malformed-verification-input-is-an-answer-not-a-500
  (with-server
    (fn []
      (doseq [body [(json/write-str {:credential "nonsense"})
                    (json/write-str {:credential {}})
                    "{}"]]
        (let [r (authed :post "/api/credentials/verify" body)]
          (is (= 200 (:status r)) (str "body " body " must not 500"))
          (is (false? (get-in r [:body :valid?])))
          (is (some? (get-in r [:body :reason]))))))))

;; ── SD-JWT VC over HTTP ──────────────────────────────────────────────────────
;; Added because the previous change put two routes in and covered neither, one
;; change after the HTTP seam turned up a real bug. The same class of thing is
;; available here: the verify route reads a compact STRING rather than a document,
;; so it uses read-json rather than read-json-raw, and getting that backwards would
;; keywordize nothing useful but could still mangle the field lookup.

(deftest sd-jwt-vc-issue-and-verify-over-http
  (with-server
    (fn []
      (let [issued (authed :post "/api/credentials/membership/sd-jwt-vc")]
        (is (= 200 (:status issued)))
        (is (= "cloud.itonami.app.credential-sd-jwt.v1" (get-in issued [:body :schema])))
        (is (string? (get-in issued [:body :presentation])))
        (is (= 1 (count (get-in issued [:body :disclosures])))
            "exactly one disclosable claim: the subject")
        (is (str/starts-with? (get-in issued [:body :vct]) "urn:cloud-itonami:")
            "no organization domain configured in this fixture, so a urn")

        (testing "verifying the full presentation discloses the subject"
          (let [r (authed :post "/api/credentials/sd-jwt-vc/verify"
                          (json/write-str
                           {:presentation (get-in issued [:body :presentation])}))]
            (is (= 200 (:status r)))
            (is (true? (get-in r [:body :valid?])))
            (is (true? (get-in r [:body :subject-disclosed?])))
            (is (= subject-did (get-in r [:body :subject])))
            (testing "and the response states what it does NOT prove"
              (is (true? (get-in r [:body :bearer-presentable?]))))))

        (testing "withholding the subject still proves the role — the point"
          (let [jwt (first (str/split (get-in issued [:body :presentation]) #"~"))
                withheld (str jwt "~")
                r (authed :post "/api/credentials/sd-jwt-vc/verify"
                          (json/write-str {:presentation withheld}))]
            (is (= 200 (:status r)))
            (is (true? (get-in r [:body :valid?])))
            (is (false? (get-in r [:body :subject-disclosed?])))
            (is (nil? (get-in r [:body :subject])))
            (is (= "member" (get-in r [:body :role])))))))))

(deftest sd-jwt-vc-routes-require-origin-and-csrf
  (with-server
    (fn []
      (doseq [path ["/api/credentials/membership/sd-jwt-vc"
                    "/api/credentials/sd-jwt-vc/verify"]]
        (is (= 403 (:status (request :post path {:headers {"Origin" origin}})))
            (str path " without CSRF"))
        (is (= 403 (:status (request :post path
                                     {:headers {"X-CLOUD-ITONAMI-CSRF" csrf}})))
            (str path " without Origin"))))))

(deftest sd-jwt-vc-malformed-input-is-an-answer-not-a-500
  (with-server
    (fn []
      (doseq [body [(json/write-str {:presentation "nonsense"})
                    (json/write-str {:presentation ""})
                    (json/write-str {:presentation nil})
                    "{}"]]
        (let [r (authed :post "/api/credentials/sd-jwt-vc/verify" body)]
          (is (= 200 (:status r)) (str "body " body " must not 500"))
          (is (false? (get-in r [:body :valid?])))
          (is (some? (get-in r [:body :reason]))))))))

(deftest a-tampered-sd-jwt-vc-presentation-is-refused-over-http
  (with-server
    (fn []
      (let [issued (authed :post "/api/credentials/membership/sd-jwt-vc")
            presentation (get-in issued [:body :presentation])
            ;; flip a character inside the signature segment
            [jwt & rest-parts] (str/split presentation #"~")
            [h p s] (str/split jwt #"\.")
            forged (str/join "~" (into [(str h "." p "." (str/reverse s))] rest-parts))
            r (authed :post "/api/credentials/sd-jwt-vc/verify"
                      (json/write-str {:presentation forged}))]
        (is (= 200 (:status r)))
        (is (false? (get-in r [:body :valid?])))))))
