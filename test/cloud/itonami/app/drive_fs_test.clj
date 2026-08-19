(ns cloud.itonami.app.drive-fs-test
  "The Drive answering the thirteen questions NFS asks, and the two seams
  that decide who may ask them."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.drive-fs :as drive-fs]
            [cloud.itonami.app.nfs :as nfs-service]
            [cloud.itonami.app.store :as store]
            [kekkai.envelope]
            [drive.store.memory :as memory-store]
            [drive.workspace :as ws]
            [nfs.v3 :as nfs]
            [xdr.core :as xdr]))

(def ^:private actor "usr-test")

(defn- fresh-drive!
  "A Drive with one folder and one file, made through `drive`'s own API so
  what the tests see is the Drive rather than a fixture of its shape.

  Built from an empty workspace rather than whatever the previous test left
  behind. `ws/create-file` conjes onto the parent's `:drive/children`, so
  re-running it over an existing Drive lists the same child twice — an
  order-dependent suite that passes until someone adds a test above it."
  []
  (let [workspace (ws/workspace (str "drive-" actor) actor (* 1024 1024 1024))
        root (:drive.workspace/root-id workspace)]
    (documents/save-workspace!
     actor
     (-> workspace
         (ws/create-folder "folder-notes" root "notes" actor)
         (ws/create-file "file-a" root
                         {:drive/title "a.txt" :drive/created-at (store/now)}
                         actor)))
    root))

(defn- fs [] (drive-fs/filesystem actor {:object-store (memory-store/store)}))

(defn- text [bytes] (xdr/utf8 (xdr/->bytes bytes)))

(deftest the-drive-tree-is-the-filesystem-tree
  (let [root-id (fresh-drive!)
        fs (fs)
        root (nfs/-root fs)]
    (is (= root-id (xdr/utf8 (xdr/->bytes root))) "the handle is the item id")

    (testing "a folder is a directory and a file is a file"
      (is (= nfs/NF3DIR (:type (nfs/-attrs fs root))))
      (let [notes (nfs/-lookup fs root "notes")
            a (nfs/-lookup fs root "a.txt")]
        (is (= nfs/NF3DIR (:type (nfs/-attrs fs notes))))
        (is (= nfs/NF3REG (:type (nfs/-attrs fs a))))))

    (testing "readdir lists titles, sorted so a cookie keeps its meaning"
      (let [{:keys [entries eof?]} (nfs/-readdir fs root 0 100)]
        (is (true? eof?))
        (is (= ["a.txt" "notes"] (mapv :name entries)))
        (is (= [1 2] (mapv :cookie entries)))))

    (testing "a cookie resumes where it said it would"
      (let [{:keys [entries eof?]} (nfs/-readdir fs root 1 100)]
        (is (= ["notes"] (mapv :name entries)))
        (is (true? eof?))))

    (testing "an item with no version is empty rather than missing"
      (let [a (nfs/-lookup fs root "a.txt")]
        (is (= 0 (:size (nfs/-attrs fs a))))
        (is (= "" (text (:bytes (nfs/-read fs a 0 100)))))))))

(deftest bytes-round-trip-through-the-drive
  (let [_ (fresh-drive!)
        fs (fs)
        root (nfs/-root fs)
        a (nfs/-lookup fs root "a.txt")]
    (is (= {:count 5} (nfs/-write fs a 0 (xdr/->bytes "hello"))))
    (is (= "hello" (text (:bytes (nfs/-read fs a 0 100)))))
    (is (= 5 (:size (nfs/-attrs fs a))))

    (testing "a write at an offset is read-modify-write, not truncation"
      (nfs/-write fs a 5 (xdr/->bytes " world"))
      (is (= "hello world" (text (:bytes (nfs/-read fs a 0 100)))))
      (is (= 11 (:size (nfs/-attrs fs a)))))

    (testing "a partial read reports eof only at the end"
      (is (false? (:eof? (nfs/-read fs a 0 5))))
      (is (true? (:eof? (nfs/-read fs a 6 100)))))

    (testing "every write is a version the Drive keeps"
      (let [item (ws/item (documents/workspace-for (store/snapshot) actor)
                          (xdr/utf8 (xdr/->bytes a)))]
        (is (= 2 (count (:drive/versions item))))
        (is (= [5 11] (mapv :drive.version/size-bytes (:drive/versions item))))))))

(deftest create-mkdir-rename-and-remove
  (let [_ (fresh-drive!)
        fs (fs)
        root (nfs/-root fs)]
    (let [made (nfs/-create fs root "new.txt" {})]
      (is (not (:error made)))
      (nfs/-write fs made 0 (xdr/->bytes "x"))
      (is (= "x" (text (:bytes (nfs/-read fs (nfs/-lookup fs root "new.txt") 0 10))))))

    (is (not (:error (nfs/-mkdir fs root "sub" {}))))
    (is (= nfs/NF3DIR (:type (nfs/-attrs fs (nfs/-lookup fs root "sub")))))

    (testing "rename within a directory"
      (is (true? (nfs/-rename fs root "new.txt" root "renamed.txt")))
      (is (:error (nfs/-lookup fs root "new.txt")))
      (is (not (:error (nfs/-lookup fs root "renamed.txt")))))

    (testing "remove is trash, not purge — rm over a mount must not be the
              one irreversible path into a Drive"
      (is (true? (nfs/-remove fs root "renamed.txt")))
      (is (= {:error nfs/NFS3ERR_NOENT} (nfs/-lookup fs root "renamed.txt")))
      (let [ws' (documents/workspace-for (store/snapshot) actor)
            id (->> (:drive.workspace/items ws') vals
                    (filter #(= "renamed.txt" (:drive/title %)))
                    first :drive/id)]
        (is (some? id) "the item is still there")
        (is (true? (ws/trashed? ws' id)) "and it is in the trash")))

    (testing "a non-empty directory is refused"
      (nfs/-create fs (nfs/-lookup fs root "sub") "inside.txt" {})
      (is (= {:error nfs/NFS3ERR_NOTEMPTY} (nfs/-rmdir fs root "sub"))))))

(deftest a-missing-item-is-a-status-not-an-exception
  (let [_ (fresh-drive!)
        fs (fs)]
    (is (= {:error nfs/NFS3ERR_NOENT} (nfs/-attrs fs (xdr/->bytes "file-absent"))))
    (is (= {:error nfs/NFS3ERR_NOENT}
           (nfs/-lookup fs (nfs/-root fs) "absent")))))

(deftest a-file-that-is-too-large-is-refused-rather-than-rewritten
  (testing "the Drive stores whole objects, so a partial write costs the
            whole file — the ceiling is stated instead of the mount just
            becoming mysteriously slow"
    (let [_ (fresh-drive!)
          fs (drive-fs/filesystem actor {:object-store (memory-store/store)
                                         :max-file-bytes 16})
          root (nfs/-root fs)
          a (nfs/-lookup fs root "a.txt")]
      (is (= {:count 8} (nfs/-write fs a 0 (xdr/->bytes "12345678"))))
      (is (= {:error nfs/NFS3ERR_FBIG}
             (nfs/-write fs a 8 (xdr/->bytes "123456789")))))))

;; ── who may ask ───────────────────────────────────────────────────────────

(deftest without-a-policy-only-loopback-is-admitted
  (let [authorize (nfs-service/authorize-fn {:actor actor :port 12049})]
    (is (= actor (:actor (authorize {:remote-address "127.0.0.1"}))))
    (is (= :loopback (:via (authorize {:remote-address "::1"}))))
    (is (nil? (authorize {:remote-address "192.168.1.20"}))
        "a LAN peer with no netmap is not a peer")))

(deftest with-a-policy-the-netmap-decides
  (testing "kekkai.acl is deny-by-default and port-granular, which is exactly
            the authorization NFSv3 does not have"
    (let [self {:node/id "mac" :tags ["tag:drive"]}
          peer {:node/id "phone" :node/overlay-ip "100.64.0.9"
                :node/actor "usr-phone" :user "jun"}
          other {:node/id "guest" :node/overlay-ip "100.64.0.77" :user "guest"}
          policy {:nodes [self peer other]
                  :grants [{:src ["jun"] :dst ["tag:drive"] :ports ["12049"]}]}
          authorize (nfs-service/authorize-fn
                     {:actor actor :policy policy :self-node self :port 12049})]

      (testing "a granted peer is admitted as the node the netmap says it is,
                not as the uid the client claimed"
        (let [p (authorize {:remote-address "100.64.0.9"})]
          (is (= "usr-phone" (:actor p)))
          (is (= :kekkai (:via p)))
          (is (= "phone" (:node p)))))

      (testing "a peer with no grant is refused"
        (is (nil? (authorize {:remote-address "100.64.0.77"}))))

      (testing "an address the netmap does not claim is refused"
        (is (nil? (authorize {:remote-address "10.0.0.5"}))))

      (testing "the port is part of the grant"
        (let [wrong-port (nfs-service/authorize-fn
                          {:actor actor :policy policy :self-node self
                           :port 2049})]
          (is (nil? (wrong-port {:remote-address "100.64.0.9"}))))))))

(deftest a-non-loopback-bind-without-a-policy-is-refused
  (testing "an unauthenticated NFS export on a LAN is an open Drive, and
            falling back to loopback would silently ignore the operator"
    (is (thrown? clojure.lang.ExceptionInfo
                 (nfs-service/start! {:nfs {:enabled? true :actor actor
                                            :bind "0.0.0.0" :port 12099}})))))

(deftest the-export-is-off-unless-configured
  (is (nil? (nfs-service/config {})))
  (is (nil? (nfs-service/config {:nfs {}})))
  (is (= 12049 (:port (nfs-service/config {:nfs {:enabled? true}})))))

;; ── a netmap this host did not write ──────────────────────────────────────

(defn- signing-identity
  "A throwaway Ed25519 pair in the shape `kekkai.envelope/seal` wants."
  []
  (let [kp (.generateKeyPair (java.security.KeyPairGenerator/getInstance "Ed25519"))]
    {:private-key (.getPrivate kp) :public-key (.getPublic kp)}))

(def ^:private drive-netmap
  {:netmap/version 7
   :netmap/tailnet "kekkai.test"
   :netmap/self {:node/id "mac" :node/overlay-ip "100.64.0.1"}
   :netmap/peers [{:node/id "phone" :node/overlay-ip "100.64.0.9"
                   :node/status "authorized"}
                  {:node/id "expired" :node/overlay-ip "100.64.0.10"
                   :node/status "authorized" :node/expires-at 1}
                  {:node/id "guest" :node/overlay-ip "100.64.0.77"
                   :node/status "authorized"}]
   :netmap/edges [{:edge/from "phone" :edge/to "mac"
                   :edge/capabilities [:overlay :private-http]
                   :edge/ports [12049]}
                  {:edge/from "expired" :edge/to "mac"
                   :edge/capabilities [:overlay :private-http]
                   :edge/ports [12049]}]})

(deftest a-signed-netmap-decides-and-an-unsigned-one-is-refused
  (let [me (signing-identity)
        spki (kekkai.envelope/authority-spki-b64 me)
        envelope (kekkai.envelope/seal drive-netmap me)]

    (testing "the envelope this host was handed is verified before it is read"
      (let [nm (nfs-service/verified-netmap {:envelope envelope
                                             :authority-spki-b64 spki})]
        (is (= 7 (:netmap/version nm)))))

    (testing "a signature from a key this export does not accept is refused"
      (let [other (signing-identity)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (nfs-service/verified-netmap
                      {:envelope envelope
                       :authority-spki-b64 (kekkai.envelope/authority-spki-b64 other)})))))

    (testing "a payload edited after signing is refused"
      ;; The digest and the signature both cover the payload bytes, so this
      ;; must fail on the first of them rather than parse and admit.
      (let [tampered (assoc envelope :netmap/payload-b64
                            (.encodeToString (java.util.Base64/getEncoder)
                                             (.getBytes (pr-str (assoc drive-netmap
                                                                       :netmap/version 8))
                                                        "UTF-8")))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (nfs-service/verified-netmap {:envelope tampered
                                                   :authority-spki-b64 spki})))))

    (testing "the authority is required — an envelope that authenticated itself
              would not be authentication"
      (is (thrown? clojure.lang.ExceptionInfo
                   (nfs-service/verified-netmap {:envelope envelope}))))

    (let [nm (nfs-service/verified-netmap {:envelope envelope :authority-spki-b64 spki})
          authorize (nfs-service/authorize-fn
                     {:actor actor :netmap nm :capability :private-http
                      :actors {"phone" "usr-phone"} :port 12049})]

      (testing "a granted peer is admitted as the node the netmap names"
        (let [p (authorize {:remote-address "100.64.0.9"})]
          (is (= "usr-phone" (:actor p)))
          (is (= :kekkai (:via p)))
          (is (= "phone" (:node p)))))

      (testing "an expired node key is a refusal, not a peer"
        (is (nil? (authorize {:remote-address "100.64.0.10"}))))

      (testing "a peer with no edge is refused"
        (is (nil? (authorize {:remote-address "100.64.0.77"}))))

      (testing "the port is part of the grant"
        (is (nil? ((nfs-service/authorize-fn
                    {:actor actor :netmap nm :capability :private-http :port 2049})
                   {:remote-address "100.64.0.9"}))))

      (testing "so is the capability — kekkai has no :nfs, so naming the wrong
                one must refuse rather than fall through to :overlay"
        (is (nil? ((nfs-service/authorize-fn
                    {:actor actor :netmap nm :capability :ssh :port 12049})
                   {:remote-address "100.64.0.9"}))))))

  (testing "a hand-written policy is refused unless the operator says so"
    (is (thrown? clojure.lang.ExceptionInfo
                 (nfs-service/config {:nfs {:enabled? true :actor actor
                                            :policy {:nodes [] :grants []}}})))
    (is (some? (nfs-service/config {:nfs {:enabled? true :actor actor
                                          :allow-unsigned-policy? true
                                          :policy {:nodes [] :grants []}}}))))

  (testing "a netmap without a capability is refused rather than given a default"
    (let [me (signing-identity)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (nfs-service/config
                    {:nfs {:enabled? true :actor actor
                           :netmap {:envelope (kekkai.envelope/seal drive-netmap me)
                                    :authority-spki-b64 (kekkai.envelope/authority-spki-b64 me)}}}))))))

(deftest behind-a-forwarder-the-agent-is-asked-who-reached-us
  ;; `kekkai.node.stream-edge` connects to the service from loopback, so
  ;; `remote-address` names nothing and admission by address refuses every
  ;; connection — fail-closed, and also unusable. The agent on this host still
  ;; knows which peer it proved, keyed by the source port it opened with.
  (let [me (signing-identity)
        nm (nfs-service/verified-netmap
            {:envelope (kekkai.envelope/seal drive-netmap me)
             :authority-spki-b64 (kekkai.envelope/authority-spki-b64 me)})
        asked (atom [])
        lookup (fn [port] (swap! asked conj port) (when (= 55555 port) "phone"))
        authorize (nfs-service/authorize-fn
                   {:actor actor :netmap nm :capability :private-http
                    :actors {"phone" "usr-phone"} :port 12049 :lookup lookup})]

    (testing "a loopback connection the agent names is admitted as that peer"
      (let [p (authorize {:remote-address "127.0.0.1" :remote-port 55555})]
        (is (= "usr-phone" (:actor p)))
        (is (= "phone" (:node p)))))

    (testing "a loopback connection the agent does not name is refused —
              configuring a lookup means loopback stops self-authorising"
      (is (nil? (authorize {:remote-address "127.0.0.1" :remote-port 44444}))))

    (testing "the peer still has to be granted the port; being named is not
              being allowed"
      (is (nil? ((nfs-service/authorize-fn
                  {:actor actor :netmap nm :capability :private-http
                   :actors {"phone" "usr-phone"} :port 2049 :lookup lookup})
                 {:remote-address "127.0.0.1" :remote-port 55555}))))

    (testing "an address the netmap already claims is answered without asking"
      (let [before (count @asked)
            p (authorize {:remote-address "100.64.0.9" :remote-port 55555})]
        (is (= "phone" (:node p)))
        (is (= before (count @asked))
            "no round trip in front of a peer that is already named")))))

(deftest a-lookup-without-a-netmap-is-refused
  (testing "the lookup names a peer; only a netmap says what that peer may
            reach. One without the other admits whoever was named, on any port"
    (is (thrown? clojure.lang.ExceptionInfo
                 (nfs-service/config {:nfs {:enabled? true :actor actor
                                            :principal-endpoint "http://127.0.0.1:1"}})))))
