(ns cloud.itonami.app.org-root-did-test
  "The organization root DID, verified the way a stranger would verify it.

  The test that matters most is `published-bytes-verify`: it serialises the
  log and the witness file exactly as they are served, parses them back with
  no knowledge of how they were made, and resolves. Verifying the in-memory
  value proves the library works; verifying the BYTES proves this app
  publishes something a resolver can read."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.org-root-did :as root-did]
            [cloud.itonami.app.store :as store]
            [didwebvh.entry :as entry]
            [didwebvh.hash :as webvh-hash]
            [didwebvh.signer :as signer]
            [didwebvh.time :as t]))

(defn- seed [n] (byte-array (map unchecked-byte (repeat 32 n))))

(def ^:private update-key (signer/from-seed (seed 1)))
(def ^:private next-key (signer/from-seed (seed 2)))
(def ^:private witnesses
  (mapv (fn [[role n]] (assoc (signer/from-seed (seed n)) :role role))
        (map vector root-did/witness-roles [11 12 13 14 15])))

(def ^:private assertion-multikey (:multikey (signer/from-seed (seed 7))))
(def ^:private version-time "2026-08-20T09:00:00Z")
(def ^:private now (+ (t/parse version-time) 60))
(def ^:private domain "acme.cloud-itonami.app")

(defn- minted []
  (root-did/mint {:domain domain
                  :version-time version-time
                  :assertion-multikey assertion-multikey
                  :also-known-as [(str "did:web:" domain)]
                  :update-signer update-key
                  :next-multikey (:multikey next-key)
                  :witness-signers witnesses}))

(deftest an-organization-root-resolves
  (let [{:keys [did scid log witness-file]} (minted)
        result (root-did/verify {:log log :witness-file witness-file
                                 :did did :now now})]
    (is (:ok? result) (pr-str (dissoc result :versions)))
    (is (= (str "did:webvh:" scid ":" domain) did))
    (is (= 46 (count scid)))
    (is (true? (:portable? result))
        "portable is only settable at genesis, so a domain proved later must not cost the identity")
    (testing "all five witnesses signed; three is the floor, not the count"
      (is (= 5 (get-in result [:versions 0 :witness :weight])))
      (is (= 3 (get-in result [:versions 0 :witness :threshold])))
      (is (= 5 (count (get-in result [:versions 0 :witness :approved])))))
    (is (= (str "https://" domain "/.well-known/did.jsonl") (root-did/log-url did)))
    (is (= (str "https://" domain "/.well-known/did-witness.json") (root-did/witness-url did)))))

(deftest the-document-names-the-credential-key-not-the-update-key
  (let [{:keys [did log]} (minted)
        state (get-in log [0 "state"])
        vm (first (get state "verificationMethod"))]
    (is (= assertion-multikey (get vm "publicKeyMultibase")))
    (is (= [(str did "#" assertion-multikey)] (get state "assertionMethod")))
    (is (= [(str "did:web:" domain)] (get state "alsoKnownAs"))
        "the did:web name this organization used to be is kept, not dropped")
    (is (not= assertion-multikey (:multikey update-key))
        "signing a credential and controlling the identity are different authorities")
    (is (not (str/includes? (pr-str state) (:multikey update-key)))
        "control does not appear in the document -- a verifier reading the DID document
         finds what signs assertions, not what may rewrite the log")
    (is (= [(:multikey update-key)] (get-in log [0 "parameters" "updateKeys"]))
        "control appears in the parameters, which is where the method puts it")))

(deftest published-bytes-verify
  (let [{:keys [did log witness-file]} (minted)
        jsonl (root-did/log-jsonl log)
        witness-json (root-did/witness-json witness-file)
        parsed-log (mapv #(json/read-str %) (str/split-lines jsonl))
        parsed-witness (json/read-str witness-json)
        result (root-did/verify {:log parsed-log :witness-file parsed-witness
                                 :did did :now now})]
    (is (str/ends-with? jsonl "\n"))
    (is (= 1 (count parsed-log)))
    (is (:ok? result) (pr-str (dissoc result :versions)))))

(deftest below-threshold-does-not-resolve
  (let [{:keys [did log witness-file]} (minted)
        two-of-five (update-in witness-file [0 "proof"] #(vec (take 2 %)))
        three-of-five (update-in witness-file [0 "proof"] #(vec (take 3 %)))]
    (is (= :didwebvh/witness-threshold-unmet
           (:error (root-did/verify {:log log :witness-file two-of-five
                                     :did did :now now}))))
    (is (:ok? (root-did/verify {:log log :witness-file three-of-five
                                :did did :now now}))
        "the same log with one more witness proof resolves")))

(deftest the-witness-parameter-carries-no-private-material
  (let [param (root-did/witness-parameter witnesses)]
    (is (= 3 (get param "threshold")))
    (is (= 5 (count (get param "witnesses"))))
    (is (every? #(str/starts-with? (get % "id") "did:key:z") (get param "witnesses")))
    (testing "the shape is identical whether the keys are here or in five HSMs"
      (is (= param (root-did/witness-parameter
                    (mapv #(select-keys % [:did-key]) witnesses)))))))

(deftest custody-is-declared-rather-than-inferred
  (is (true? (root-did/co-located-custody?))
      "five signatures with one custody is one point of failure wearing five hats -- the record says so"))

(deftest proving-a-domain-does-not-destroy-a-webvh-root
  (testing "the regression did:webvh was adopted to prevent"
    (let [previous @store/state
          webvh-did (:did (minted))]
      (try
        (reset! store/state (store/initial-state))
        (store/transact!
         (fn [c] (assoc-in c [:identity :organizations "org-1"]
                           {:id "org-1" :tenant/kind :organization
                            :organization-id "acme" :did webvh-did
                            :did-method :webvh :domain domain})))
        (let [bound (local-identity/bind-verified-domain! "org-1" "did.acme.example")
              stored (get-in (:identity @store/state) [:organizations "org-1"])]
          (is (= webvh-did (:did bound))
              "proving a name must not swap the log-backed identity for a did:web")
          (is (= webvh-did (:did stored)))
          (is (= "did.acme.example" (:domain stored)) "the proved name IS recorded")
          (is (true? (:did-location-pending stored))
              "and the fact that the log has not moved there yet is recorded too")
          (is (= webvh-did (:identity.subject/did (:subject stored)))
              "the subject follows the DID, not the domain"))
        (finally (reset! store/state previous))))))

(deftest a-did-web-tenant-still-moves-with-its-proved-name
  (testing "the guard is scoped to webvh and does not freeze the old behaviour"
    (let [previous @store/state
          previous-profile @local-identity/runtime-identity-profile]
      (try
        (reset! store/state (store/initial-state))
        (reset! local-identity/runtime-identity-profile
                (assoc @local-identity/runtime-identity-profile :publish-did-web? true))
        (store/transact!
         (fn [c] (assoc-in c [:identity :organizations "org-2"]
                           {:id "org-2" :tenant/kind :organization
                            :organization-id "beta" :did "did:web:beta.cloud-itonami.app"
                            :domain "beta.cloud-itonami.app"})))
        (let [bound (local-identity/bind-verified-domain! "org-2" "did.beta.example")]
          (is (= "did:web:did.beta.example" (:did bound)))
          (is (nil? (:did-location-pending bound))))
        (finally
          (reset! local-identity/runtime-identity-profile previous-profile)
          (reset! store/state previous))))))

;; ── rotation, portability and the pre-rotation commitment ────────────────────

(defn- with-temp-data-dir [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-org-root-did"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
      (body (.toFile temporary)))))

(deftest a-proved-domain-appends-a-version-that-resolves
  (testing "the demonstration the pre-rotation commitment was written for"
    (with-temp-data-dir
      (fn [_]
        (let [issued (root-did/issue! {:domain domain
                                       :assertion-multikey assertion-multikey
                                       :also-known-as [(str "did:web:" domain)]
                                       :now (t/parse version-time)})
              moved (root-did/move! {:log (:log issued)
                                     :domain "did.acme.example"
                                     :assertion-multikey assertion-multikey
                                     :now (+ (t/parse version-time) 86400)})
              witness-file (root-did/merge-proofs (:witness-file issued)
                                                  (:new-proofs moved))
              result (root-did/verify {:log (:log moved)
                                       :witness-file witness-file
                                       :did (:did moved)
                                       :now (+ (t/parse version-time) 90000)})
              [v1 v2] (:log moved)]
          (is (:ok? result) (pr-str (dissoc result :versions)))
          (is (= 2 (count (:versions result))))
          (is (= (:scid issued) (:scid result))
              "the SCID survives the move -- the identity did not change")
          (is (= (str "did:webvh:" (:scid issued) ":did.acme.example") (:did moved)))
          (is (some #{(:did issued)} (get-in v2 ["state" "alsoKnownAs"]))
              "the DID it used to be is reachable from the DID it became")
          (testing "the commitment was redeemed, not merely written"
            (let [committed (get-in v1 ["parameters" "nextKeyHashes"])
                  used (get-in v2 ["parameters" "updateKeys"])]
              (is (= 1 (count committed)))
              (is (= (set committed) (set (map webvh-hash/key-hash used)))
                  "version 2's update key is the one version 1 named in advance")
              (is (not= (get-in v1 ["parameters" "updateKeys"]) used)
                  "and it is NOT the key that signed version 1"))))))))

(deftest the-key-that-signed-version-one-cannot-sign-version-two
  (with-temp-data-dir
    (fn [_]
      (let [issued (root-did/issue! {:domain domain
                                     :assertion-multikey assertion-multikey
                                     :now (t/parse version-time)})
            stale (root-did/update-signer 0)
            forged (root-did/append {:log (:log issued)
                                     :version-time "2026-08-21T00:00:00Z"
                                     :state (get-in issued [:log 0 "state"])
                                     :update-signer stale
                                     :next-multikey (:multikey (root-did/update-signer 1))
                                     :witness-signers (root-did/local-witness-signers)})
            honest (root-did/move! {:log (:log issued)
                                    :domain domain
                                    :assertion-multikey assertion-multikey
                                    :now (+ (t/parse version-time) 86400)})
            check (fn [built]
                    (root-did/verify
                     {:log (:log built)
                      :witness-file (root-did/merge-proofs (:witness-file issued)
                                                           (:new-proofs built))
                      :now (+ (t/parse version-time) 90000)}))]
        (is (= :didwebvh/uncommitted-update-key (:error (check forged)))
            "a compromised current key still cannot name itself the successor")
        (is (:ok? (check honest)) "the pre-committed key can")))))

;; ── witness intake from outside this deployment ──────────────────────────────

(deftest an-external-witness-can-file-a-proof
  (with-temp-data-dir
    (fn [_]
      (let [issued (root-did/issue! {:domain domain
                                     :assertion-multikey assertion-multikey
                                     :now (t/parse version-time)})
            witness (get-in issued [:log 0 "parameters" "witness"])
            version-id (:version-id issued)
            ;; The genesis file already has all five, because all five live
            ;; here today. Start from an EMPTY file so this measures the
            ;; intake and not the local signers.
            declared (first (root-did/local-witness-signers))
            outsider (signer/from-seed (seed 99))
            file-one (root-did/accept-witness-proof
                      {:witness witness :version-id version-id :witness-file []
                       :proof (entry/witness-proof version-id declared)})]
        (is (:ok? file-one))
        (is (= (:did-key declared) (:witness file-one)))
        (is (= 1 (count (get-in (:witness-file file-one) [0 "proof"]))))
        (testing "a second proof from the same witness is refused, not stacked"
          (is (= :didwebvh/witness-already-approved
                 (:error (root-did/accept-witness-proof
                          {:witness witness :version-id version-id
                           :witness-file (:witness-file file-one)
                           :proof (entry/witness-proof version-id declared)})))))
        (testing "a proof from a key this DID never declared is refused"
          (is (= :didwebvh/not-a-declared-witness
                 (:error (root-did/accept-witness-proof
                          {:witness witness :version-id version-id :witness-file []
                           :proof (entry/witness-proof version-id outsider)})))))
        (testing "a proof over a different version is refused"
          (is (= :didwebvh/bad-signature
                 (:error (root-did/accept-witness-proof
                          {:witness witness :version-id version-id :witness-file []
                           :proof (entry/witness-proof "2-Qmwrong" declared)})))))))))

(deftest the-witness-request-carries-no-key-material
  (with-temp-data-dir
    (fn [_]
      (let [issued (root-did/issue! {:domain domain
                                     :assertion-multikey assertion-multikey
                                     :now (t/parse version-time)})
            request (root-did/witness-request
                     {:did (:did issued)
                      :witness (get-in issued [:log 0 "parameters" "witness"])
                      :version-id (:version-id issued)})]
        (is (= {"versionId" (:version-id issued)} (:document request)))
        (is (= 3 (:threshold request)))
        (is (= 5 (count (:witnesses request))))
        (is (every? #(str/starts-with? % "did:key:") (:witnesses request)))))))

;; ── durability ───────────────────────────────────────────────────────────────

(deftest the-log-survives-outside-the-state-file
  (with-temp-data-dir
    (fn [_]
      (let [issued (root-did/issue! {:domain domain
                                     :assertion-multikey assertion-multikey
                                     :now (t/parse version-time)})]
        (root-did/persist! "org-1" issued)
        (let [recovered (root-did/read-persisted "org-1")
              result (root-did/verify {:log (:log recovered)
                                       :witness-file (:witness-file recovered)
                                       :did (:did issued)
                                       :now (+ (t/parse version-time) 60)})]
          (is (:ok? result)
              "what came back off disk resolves -- a copy that does not is not a copy")
          (is (nil? (root-did/read-persisted "org-never-written"))))))))

;; ── resolving somebody else's did:webvh ──────────────────────────────────────

(deftest an-external-did-webvh-resolves-through-an-injected-fetch
  (with-temp-data-dir
    (fn [_]
      (let [issued (root-did/issue! {:domain domain
                                     :assertion-multikey assertion-multikey
                                     :now (t/parse version-time)})
            did (:did issued)
            served {(root-did/log-url did) {:status 200
                                            :body (root-did/log-jsonl (:log issued))}
                    (root-did/witness-url did) {:status 200
                                                :body (root-did/witness-json
                                                       (:witness-file issued))}}
            fetch (fn [url] (get served url {:status 404 :body ""}))
            now (+ (t/parse version-time) 60)]
        (is (:ok? (root-did/resolve-external did {:fetch fetch :now now})))
        (testing "no witness file is a threshold miss, not a pass"
          (let [without (fn [url] (if (= url (root-did/witness-url did))
                                    {:status 404 :body ""}
                                    (fetch url)))]
            (is (= :didwebvh/witness-threshold-unmet
                   (:error (root-did/resolve-external did {:fetch without :now now}))))))
        (testing "a log that cannot be fetched is named as such"
          (is (= :didwebvh/log-unavailable
                 (:error (root-did/resolve-external
                          did {:fetch (fn [_] {:status 500 :body ""}) :now now})))))))))

;; ── upgrading tenants that predate ADR-0068 ──────────────────────────────────

(deftest a-did-web-tenant-is-upgraded-and-keeps-its-old-name-reachable
  (with-temp-data-dir
    (fn [_]
      (let [previous @store/state
            previous-profile @local-identity/runtime-identity-profile]
        (try
          (reset! store/state (store/initial-state))
          (reset! local-identity/runtime-identity-profile
                  (assoc @local-identity/runtime-identity-profile
                         :publish-did-web? true :root-did-method :webvh))
          (store/transact!
           (fn [c] (assoc-in c [:identity :organizations "org-old"]
                             {:id "org-old" :tenant/kind :organization
                              :organization-id "acme" :domain domain
                              :did (str "did:web:" domain)})))
          (let [upgraded (local-identity/upgrade-organizations-to-webvh!)
                stored (get-in (:identity @store/state) [:organizations "org-old"])]
            (is (= 1 (count upgraded)))
            (is (str/starts-with? (:did stored) "did:webvh:"))
            (is (= (str "did:web:" domain) (:did-upgraded-from stored)))
            (is (some #{(str "did:web:" domain)}
                      (get-in stored [:did-log 0 "state" "alsoKnownAs"]))
                "the name it was known by is reachable from the name it became")
            (is (:ok? (root-did/verify {:log (:did-log stored)
                                        :witness-file (:did-witness stored)
                                        :did (:did stored)}))))
          (testing "running it again changes nothing -- a second genesis would be a second identity"
            (let [before (get-in (:identity @store/state) [:organizations "org-old"])
                  again (local-identity/upgrade-organizations-to-webvh!)]
              (is (= [] again))
              (is (= (:did before)
                     (:did (get-in (:identity @store/state) [:organizations "org-old"]))))))
          (finally
            (reset! local-identity/runtime-identity-profile previous-profile)
            (reset! store/state previous)))))))

(deftest a-deployment-that-publishes-nothing-upgrades-nothing
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (store/transact!
       (fn [c] (assoc-in c [:identity :organizations "org-x"]
                         {:id "org-x" :tenant/kind :organization
                          :organization-id "acme" :domain domain
                          :did (str "did:web:" domain)})))
      (is (= [] (local-identity/upgrade-organizations-to-webvh!))
          "every shipped profile has :publish-did-web? false, and a DID nobody
           serves is worse than none")
      (finally (reset! store/state previous)))))
