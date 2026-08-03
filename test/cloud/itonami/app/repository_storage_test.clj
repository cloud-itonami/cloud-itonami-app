(ns cloud.itonami.app.repository-storage-test
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.repository-measurement :as measurement]
            [cloud.itonami.app.repository-storage :as repository]
            [kagi.crypto :as crypto]
            [kagi.identity :as kagi-identity]
            [kagi.persist :as kagi-persist]
            [kagi.repository-context :as kagi-context]
            [kagi.secret-store :as secret-store]
            [kotobase.local :as local])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(def owner "user-storage-opaque-001")

(def base-state
  {:schema "cloud.itonami.app.state.v1"
   :datoms [["m1" :message/role "user"]
            ["m1" :message/content "secret-marker"]]
   :settings {:labels #{:a :b} :order [1 2]}
   :events '()})

(defn fixture []
  (let [provider (crypto/jvm-provider)
        signing (crypto/sign-keypair provider)]
    {:provider provider
     :vmk (crypto/rand-bytes provider 32)
     :signing-secret (:secret signing)
     :signing-public (:public signing)
     :transport (repository/memory-block-transport)
     :head-store (local/local-store)}))

(defn preparation
  [context base candidate current previous-head]
  (repository/prepare-publication
   (merge context
          {:owner owner :key-epoch 1 :max-chunk-bytes 1024
           :base base :candidate candidate :current current
           :basis-cid (repository/semantic-cid base)
           :previous-head previous-head})))

(deftest canonical-cid-and-chunks-preserve-edn-semantics
  (is (= (repository/content-cid {:b 2 :a 1})
         (repository/content-cid {:a 1 :b 2})))
  (let [state (assoc base-state :large (vec (range 400)))
        chunks (repository/chunk-state state 1024)]
    (is (< 1 (count chunks)))
    (is (= (repository/validate-state! state)
           (repository/rebuild-state chunks)))))

(deftest private-state-leak-markers-include-domain-keys-and-values
  (let [markers (set (repository/plaintext-markers base-state))]
    (is (contains? markers ":message/content"))
    (is (contains? markers "secret-marker"))))

(deftest representative-local-capacity-measurement-is-explicitly-warm
  (let [result (measurement/measure-local-capacity
                (assoc (fixture) :owner owner :key-epoch 1)
                base-state 2)]
    (is (= :warm-local-capacity (:scope result)))
    (is (pos? (:reconcile-bps result)))
    (is (pos? (:local-view-apply-bps result)))
    (is (pos? (:seal-input-bps result)))
    (is (pos? (:encrypted-output-ratio result)))))

(deftest cold-hydrate-requires-an-empty-isolated-block-cache
  (let [empty-root (.toFile (Files/createTempDirectory
                             "cloud-itonami-cold-cache-"
                             (make-array java.nio.file.attribute.FileAttribute
                                         0)))
        {:keys [provider vmk signing-public transport head-store] :as context}
        (fixture)
        prepared (preparation context base-state base-state base-state nil)
        _ (repository/publish-prepared!
           {:transport transport :head-store head-store
            :provider provider :signing-public signing-public
            :owner owner :expected-revision 0}
           prepared)
        cold (measurement/measure-cold-hydrate
              {:datalad-root (.getPath empty-root)
               :transport transport :head-store head-store
               :provider provider :vmk vmk
               :signing-public signing-public :owner owner})]
    (is (:cache-empty? cold))
    (is (:cold-hydrate? cold))
    (is (pos? (:hydrate-ms cold)))
    (is (pos? (:downloaded-bytes cold)))
    (let [blocks (io/file empty-root ".itonami" "blocks")
          materialized (io/file blocks
                                (str (apply str (repeat 64 "a")) ".block"))]
      (.mkdirs blocks)
      (Files/write (.toPath materialized) (byte-array [1 2 3])
                   (make-array java.nio.file.OpenOption 0))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"already contains local block"
           (repository/assert-empty-datalad-block-cache! empty-root))))))

(deftest direct-edn-and-datom-update-converge
  (let [added ["m2" :message/content "same"]
        candidate (update base-state :datoms conj added)
        direct (repository/reconcile
                {:base base-state :candidate candidate :current base-state
                 :basis-cid (repository/semantic-cid base-state)})
        transaction (repository/apply-datom-transaction
                     {:base base-state :current base-state
                      :basis-cid (repository/semantic-cid base-state)
                      :tx-data [(into [:db/add] added)]})]
    (is (= (:state direct) (:state transaction)))
    (is (= (:semantic/cid direct) (:semantic/cid transaction)))
    (is (= [added] (:assertions direct)))))

(deftest stale-basis-merges-independent-edits-and-surfaces-conflicts
  (let [local-state (assoc-in base-state [:settings :local] true)
        remote-state (assoc-in base-state [:settings :remote] true)
        merged (repository/reconcile
                {:base base-state :candidate local-state :current remote-state
                 :basis-cid (repository/semantic-cid base-state)})]
    (is (= true (get-in merged [:state :settings :local])))
    (is (= true (get-in merged [:state :settings :remote]))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"reconciliation conflict"
       (repository/reconcile
        {:base base-state
         :candidate (assoc-in base-state [:settings :order] [2])
         :current (assoc-in base-state [:settings :order] [3])
         :basis-cid (repository/semantic-cid base-state)}))))

(deftest encrypted-publish-hydrate-and-idempotent-retry
  (let [{:keys [provider vmk signing-public transport head-store] :as context}
        (fixture)
        prepared (preparation context base-state base-state base-state nil)
        journal (repository/preparation->journal prepared)
        publish-context {:transport transport :head-store head-store
                         :provider provider :signing-public signing-public
                         :owner owner :expected-revision 0}
        result (repository/publish-prepared! publish-context prepared)
        hydrated (repository/hydrate-current
                  {:transport transport :head-store head-store
                   :provider provider :vmk vmk
                   :signing-public signing-public :owner owner})
        retried (repository/publish-prepared!
                 (assoc publish-context :expected-revision 0)
                 (assoc (repository/journal->preparation journal)
                        :state base-state))]
    (is (:published? result))
    (is (= (repository/validate-state! base-state) (:state hydrated)))
    (is (= (:head/cid (:head result)) (:head/cid (:head retried))))
    (is (:idempotent? (:receipt retried)))
    (is (not (str/includes? (String. journal "UTF-8") "secret-marker"))
        "retry journal contains ciphertext only")))

(deftest transport-failure-cannot-advance-head
  (let [{:keys [transport head-store] :as context} (fixture)
        prepared (preparation context base-state base-state base-state nil)]
    (repository/fail-memory-publish! transport true)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"transport failure"
         (repository/publish-prepared!
          {:transport transport :head-store head-store
           :provider (:provider context)
           :signing-public (:signing-public context)
           :owner owner :expected-revision 0}
          prepared)))
    (is (nil? (:head (repository/head-snapshot head-store owner))))))

(deftest vmk-rotation-does-not-rewrite-payload-blocks
  (let [{:keys [provider vmk signing-public signing-secret transport head-store]
         :as context} (fixture)
        prepared (preparation context base-state base-state base-state nil)
        published (repository/publish-prepared!
                   {:transport transport :head-store head-store
                    :provider provider :signing-public signing-public
                    :owner owner :expected-revision 0}
                   prepared)
        old-head (:head published)
        old-hydrated (repository/hydrate-head
                      {:transport transport :provider provider :vmk vmk
                       :signing-public signing-public :owner owner} old-head)
        old-chunk-cids (mapv :sealed/cid
                             (get-in old-hydrated [:manifest :chunks]))
        new-vmk (crypto/rand-bytes provider 32)
        key-envelope {:wrapped :opaque-test-envelope}
        rotated (repository/prepare-vmk-rotation
                 {:transport transport :provider provider :vmk vmk
                  :new-vmk new-vmk :signing-secret signing-secret
                  :signing-public signing-public :owner owner
                  :head old-head :key-epoch 1
                  :key-envelope key-envelope})
        rotated-result (repository/publish-prepared!
                        {:transport transport :head-store head-store
                         :provider provider :signing-public signing-public
                         :owner owner :expected-revision 1}
                        rotated)
        new-head (:head rotated-result)
        new-hydrated (repository/hydrate-head
                      {:transport transport :provider provider :vmk new-vmk
                       :signing-public signing-public :owner owner} new-head)
        other-device-hydrated
        (repository/hydrate-head
         {:transport transport :provider provider :vmks {1 vmk}
          :key-epoch 1
          :unwrap-repository-vmk
          (fn [epoch envelope]
            (is (= 2 epoch))
            (is (= key-envelope envelope))
            new-vmk)
          :signing-public signing-public :owner owner}
         new-head)
        usage (repository/storage-usage
               {:transport transport :provider provider
                :vmks {1 vmk 2 new-vmk}
                :signing-public signing-public :owner owner}
               [old-head new-head])
        retained (repository/retained-heads
                  {:transport transport :provider provider
                   :vmks {1 vmk 2 new-vmk}
                   :signing-public signing-public :owner owner}
                  new-head)]
    (is (= 2 (count (:blocks rotated)))
        "new manifest and encrypted previous-head locator are written")
    (is (= old-chunk-cids
           (mapv :sealed/cid (get-in new-hydrated [:manifest :chunks]))))
    (is (= (repository/validate-state! base-state) (:state new-hydrated)))
    (is (= 2 (:key/epoch new-head)))
    (is (= key-envelope (:key/envelope new-head)))
    (is (= (repository/validate-state! base-state)
           (:state other-device-hydrated)))
    (is (= [(:head/cid new-head) (:head/cid old-head)]
           (mapv :head/cid retained)))
    (is (= (repository/validate-state! base-state)
           (:state (repository/hydrate-head
                    {:transport transport :provider provider
                     :vmks {1 vmk 2 new-vmk}
                     :signing-public signing-public :owner owner}
                    old-head))))
    (is (:reconciled? usage))
    (is (= (:sealed/bytes usage) (:physical/bytes usage)))))

(deftest published-head-cas-admits-staged-kagi-rotation
  (let [{:keys [provider vmk signing-public signing-secret transport head-store]
         :as context} (fixture)
        home (.toFile (Files/createTempDirectory
                       "itonami-kagi-rotation"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        unlock-fn (fn [_ _] vmk)
        secrets (secret-store/mem-secret-store)
        _ (kagi-persist/save! (.getPath (io/file home "vault.edn"))
                              {:meta {}})
        _ (kagi-identity/load-or-create-identity!
           (.getPath (io/file home "identity.edn")) provider
           {:secret-store secrets
            :secret-ref "mem://identity/itonami-rotation"})
        old (preparation context base-state base-state base-state nil)
        old-result (repository/publish-prepared!
                    {:transport transport :head-store head-store
                     :provider provider :signing-public signing-public
                     :owner owner :expected-revision 0}
                    old)
        staged (kagi-context/prepare-repository-vmk-rotation
                {:vault-home home :provider provider :repository-id owner
                 :identity-secret-store secrets
                 :unlock-vmk-fn unlock-fn :expected-epoch 1})
        rotated (repository/prepare-vmk-rotation
                 {:transport transport :provider provider :vmk vmk
                  :new-vmk (:vmk staged)
                  :signing-secret signing-secret
                  :signing-public signing-public :owner owner
                  :head (:head old-result) :key-epoch 1
                  :key-envelope (get (:key-envelopes staged) 2)
                  :rotation-event (:repository-rotation-event staged)})
        published (repository/publish-prepared!
                   {:transport transport :head-store head-store
                    :provider provider :signing-public signing-public
                    :owner owner :expected-revision 1}
                   rotated)
        admitted (kagi-context/adopt-repository-vmk!
                  {:vault-home home :provider provider :repository-id owner
                   :identity-secret-store secrets
                   :unlock-vmk-fn unlock-fn :key-epoch 2
                   :key-envelope (:key/envelope (:head published))
                   :rotation-event (:key/rotation-event (:head published))})
        hydrated (repository/hydrate-head
                  (merge admitted
                         {:transport transport :signing-public signing-public
                          :owner owner})
                  (:head published))]
    (is (= 2 (:current-key-epoch admitted)))
    (is (= (repository/validate-state! base-state) (:state hydrated)))
    (is (= 1 (count (:events
                     (kagi-persist/load*
                      (.getPath (io/file home "repository-rotation-dag.edn")))))))
    (is (not (str/includes? (slurp (io/file home "vault.edn"))
                            "secret-marker")))))

(deftest workspace-refuses-a-path-inside-datalad
  (let [root (.toFile (Files/createTempDirectory "itonami-workspace-test"
                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        dataset (io/file root "dataset")]
    (.mkdirs dataset)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"outside DataLad"
         (repository/write-workspace! dataset dataset owner base-state
                                      {:head/cid "head"} 1)))))

(deftest workspace-refuses-a-trackable-git-path
  (let [root (.toFile (Files/createTempDirectory
                       "itonami-trackable-workspace"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        workspace (io/file root "workspace")
        datalad (io/file root "separate-datalad")]
    (.mkdirs (io/file root ".git"))
    (.mkdirs datalad)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not ignored"
         (repository/write-workspace! workspace datalad owner base-state
                                      {:head/cid "head"} 1)))))

(deftest workspace-commit-reuses-journal-and-three-way-merges-remote
  (let [{:keys [provider vmk signing-public transport head-store] :as context}
        (fixture)
        root (.toFile (Files/createTempDirectory
                       "itonami-workspace-commit"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        workspace (io/file root "workspace")
        datalad (io/file root "datalad")
        _ (.mkdirs datalad)
        runtime (merge context {:workspace-root workspace
                                :datalad-root datalad :owner owner
                                :key-epoch 1 :max-chunk-bytes 1024})]
    (repository/write-workspace! workspace datalad owner base-state
                                 {:head/cid nil} 0)
    (repository/fail-memory-publish! transport true)
    (is (thrown? clojure.lang.ExceptionInfo
                 (repository/commit-workspace! runtime)))
    (let [staged-before (set (keys @(:staged transport)))]
      (repository/fail-memory-publish! transport false)
      (repository/commit-workspace! runtime)
      (is (= staged-before (set (keys @(:staged transport))))
          "retry reuses exact random-nonce ciphertext"))

    (let [head1 (:head (repository/head-snapshot head-store owner))
          local-candidate (assoc-in base-state [:settings :local] true)
          remote-candidate (assoc-in base-state [:settings :remote] true)
          remote-prepared (preparation context base-state remote-candidate
                                       base-state head1)]
      (spit (io/file workspace owner "state.edn") (pr-str local-candidate))
      (repository/publish-prepared!
       {:transport transport :head-store head-store :provider provider
        :signing-public signing-public :owner owner :expected-revision 1}
       remote-prepared)
      (repository/commit-workspace! runtime)
      (let [hydrated (repository/hydrate-current
                      {:transport transport :head-store head-store
                       :provider provider :vmk vmk
                       :signing-public signing-public :owner owner})]
        (is (= true (get-in hydrated [:state :settings :local])))
        (is (= true (get-in hydrated [:state :settings :remote])))))))

(deftest datalad-payload-audit-detects-plaintext-and-cid-mismatch
  (let [root (.toFile (Files/createTempDirectory
                       "itonami-datalad-audit"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (.mkdirs (io/file root ".git"))
        transport (repository/datalad-block-transport root nil)
        ciphertext (.getBytes "opaque-ciphertext" "UTF-8")
        cid (repository/bytes-cid ciphertext)]
    (repository/stage-block! transport cid ciphertext)
    (is (:qualified? (repository/audit-datalad-blocks root ["secret-marker"])))
    (spit (io/file root ".itonami" "blocks" "not-a-cid.block")
          "secret-marker")
    (let [audit (repository/audit-datalad-blocks root ["secret-marker"])]
      (is (false? (:qualified? audit)))
      (is (= #{:invalid-filename :plaintext-marker}
             (set (mapcat :problems (:violations audit))))))))

(deftest empty-datalad-payload-cannot-pass-audit
  (let [root (.toFile (Files/createTempDirectory
                       "itonami-empty-datalad-audit"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (io/file root ".itonami" "blocks"))
    (is (false? (:qualified?
                 (repository/audit-datalad-blocks root ["private"]))))))

(deftest deployed-encrypted-graph-cas-is-a-head-registry
  (let [remote-state (atom nil)
        authorization (atom nil)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        handler
        (reify HttpHandler
          (handle [_ exchange]
            (let [path (.getPath (.getRequestURI exchange))
                  request (json/read-str (slurp (.getRequestBody exchange))
                                         :key-fn keyword)
                  _ (reset! authorization
                            (.getFirst (.getRequestHeaders exchange)
                                       "Authorization"))
                  get? (str/ends-with? path ".get")
                  current @remote-state
                  [status body]
                  (cond
                    (and get? (nil? current))
                    [404 {:ok false :error "EncryptedGraphNotFound"}]

                    get?
                    [200 {:ok true :epoch (:epoch current)
                          :manifest (:manifest current)}]

                    (not= (:expected_epoch request) (or (:epoch current) 0))
                    [409 {:ok false :error "HeadConflict"}]

                    :else
                    (do (reset! remote-state request)
                        [200 {:ok true :epoch (:epoch request)
                              :manifest_cid "bafy-manifest"}]))
                  bytes (.getBytes (json/write-str body) StandardCharsets/UTF_8)]
              (.sendResponseHeaders exchange status (alength bytes))
              (with-open [output (.getResponseBody exchange)]
                (.write output bytes)))))]
    (.createContext server "/xrpc/ai.gftd.apps.kotobase.encryptedGraph.get"
                    handler)
    (.createContext server "/xrpc/ai.gftd.apps.kotobase.encryptedGraph.put"
                    handler)
    (.start server)
    (try
      (let [{:keys [provider vmk signing-public transport] :as context} (fixture)
            registry (repository/encrypted-graph-head-registry
                      (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                      "test-token")
            prepared (preparation context base-state base-state base-state nil)
            result (repository/publish-prepared!
                    {:transport transport :head-store registry
                     :provider provider :signing-public signing-public
                     :owner owner :expected-revision 0}
                    prepared)
            hydrated (repository/hydrate-current
                      {:transport transport :head-store registry
                       :provider provider :vmk vmk
                       :signing-public signing-public :owner owner})]
        (is (= "Bearer test-token" @authorization))
        (is (= 1 (:revision (:receipt result))))
        (is (= (:head/cid (:head result))
               (:head/cid
                (repository/decode-wire-string
                 (get-in @remote-state
                         [:manifest :blocks 0 :envelope :head_edn])))))
        (is (= (repository/validate-state! base-state) (:state hydrated))))
      (finally (.stop server 0)))))
