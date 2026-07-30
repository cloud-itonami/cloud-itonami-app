(ns cloud.itonami.app.storj-node
  "Running a Storj storage node — the other end of `cloud.itonami.app.storj`.

  That namespace is this app as a *customer*: it puts bytes in a bucket. This
  one is the app as a *supplier*: it holds pieces for other people and answers
  a satellite. `kotoba-lang/io-storj-node` is the protocol; this is the
  configuration, the lifecycle and the disk.

  ## Two gates before this can join the public network

  Both are outside this code and neither is a bug:

  1. **Difficulty 36.** `storj.node.mint/storagenode-difficulty` is what a
     public satellite requires, and minting at it takes hours of CPU. This
     defaults to nothing and refuses to invent one — see `identity-dir`.
  2. **An authorized identity.** A public satellite wants a CA signed by
     Storj's signing service, which arrives by email as a token and is
     redeemed with `identity authorize`. Nothing in `io-storj-node` models
     that, so an identity minted here is well-formed and unauthorized.

  Against a **local or private satellite** — `storj-up`, or anything whose
  difficulty and authorization rules you set — neither applies, and this runs
  today. That is the configuration it is written for.

  ## What it actually serves

  Check-in outbound, and inbound `PingNode`, `Exists`, `Retain`,
  `DeletePieces`, `RestoreTrash`, `Upload` and `Download`. What it does not do
  is settlement, graceful exit, or piece expiry — a node that stores and
  serves and is never paid. Listed in `io-storj-node`'s README under scope.

  ## Storage

  `storj.node.host.blobs/in-memory` is the only `IBlobStore` that library
  ships, and a store that forgets everything when the process ends is not
  storage. So this app supplies its own over the data directory. Pieces are
  files; the format (`.sj1` or bare) is part of the path, which is how a node
  tells a verified piece from an unverified one after a restart."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [proto.wire :as w]
            [storj.node.contact :as contact]
            [storj.node.host.keys :as hk]
            [storj.node.host.rpc :as rpc]
            [storj.node.host.tls :as htls]
            [storj.node.host.verify :as v]
            [storj.node.identity :as ident]
            [storj.node.piece :as piece]
            [storj.node.protocols :as p]
            [storj.node.service :as svc]
            [storj.node.transfer :as tr])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def schema "cloud.itonami.app.storj-node.v1")

(defn- env [k] (some-> (System/getenv k) str/trim not-empty))

;; ── configuration ───────────────────────────────────────────────────────────

(defn identity-dir
  "Where this node's `ca.cert` / `identity.cert` / `identity.key` live.

  `STORJ_NODE_IDENTITY_DIR`, and nil when it is unset. Deliberately not
  defaulted and deliberately not minted on demand: an identity is the node's
  name on the network and its payout address's twin, and one generated
  silently at first boot is one nobody backed up. Mint with
  `clojure -M:mint <dir> <difficulty>` in `io-storj-node`."
  []
  (env "STORJ_NODE_IDENTITY_DIR"))

(defn config
  "Node configuration from the environment, or nil when it is not set.

      STORJ_NODE_IDENTITY_DIR   the identity to run as        (required)
      STORJ_NODE_SATELLITE      host:port to check in with    (required)
      STORJ_NODE_SATELLITE_ID   its node id, hex              (required)
      STORJ_NODE_ADDRESS        what to tell it we are        (required)
      STORJ_NODE_PORT           what to listen on             (default 28967)
      STORJ_NODE_FREE_DISK      bytes to advertise            (default 1 GiB)
      STORJ_NODE_EMAIL          operator contact
      STORJ_NODE_WALLET         payout address

  `STORJ_NODE_ADDRESS` is separate from the port because they are different
  facts: the satellite dials the address back, so it has to be reachable from
  the satellite rather than from here. `127.0.0.1:28967` works for a local
  satellite and is wrong for every other kind."
  []
  (let [dir (identity-dir)
        sat (env "STORJ_NODE_SATELLITE")
        sid (env "STORJ_NODE_SATELLITE_ID")
        adr (env "STORJ_NODE_ADDRESS")]
    (when (and dir sat sid adr)
      {:identity-dir dir
       :satellite    sat
       :satellite-id sid
       :address      adr
       :port         (or (some-> (env "STORJ_NODE_PORT") parse-long) 28967)
       :free-disk    (or (some-> (env "STORJ_NODE_FREE_DISK") parse-long)
                         (* 1024 1024 1024))
       :email        (env "STORJ_NODE_EMAIL")
       :wallet       (env "STORJ_NODE_WALLET")})))

(defn configured?
  "Whether a node can be started at all. Says nothing about whether a
  satellite will have it — see the two gates in the namespace docstring."
  []
  (some? (config)))

;; ── the identity ────────────────────────────────────────────────────────────

(defn load-identity
  "The identity in `dir`, as `storj.node.host.tls` wants it.

  The leaf key: `identity.key` is what signs on the wire and what a peer
  verifies messages against, while the CA key stays offline once the identity
  exists. The node *id* still comes from the CA — those are different
  certificates and mixing them up produces a signature that verifies against
  nothing."
  [dir]
  (let [chain (ident/parse-chain-pem (slurp (io/file dir "identity.cert")))
        key   (ident/parse-private-key-pem (slurp (io/file dir "identity.key")))]
    {:chain chain
     :private-key (hk/import-private-key (:der key) (:encoding key))}))

(defn node-id
  "This node's id, from the CA certificate in `dir`."
  [dir]
  (ident/node-id (ident/certificate
                  (first (ident/parse-chain-pem (slurp (io/file dir "ca.cert")))))))

;; ── storage ─────────────────────────────────────────────────────────────────

(defn- pieces-dir []
  (doto (io/file (config/data-dir) "storj-pieces") (.mkdirs)))

(defn- safe-path
  "A blob path resolved under the pieces directory, refusing to escape it.

  `blob-path` builds these from base32 of ids, so a traversal cannot occur
  from a well-formed piece — which is exactly why the check is here rather
  than trusted: the day something else calls this with a path from a peer,
  the refusal is already in place."
  [rel]
  (let [root (.toPath (pieces-dir))
        p    (.normalize (.resolve root ^String rel))]
    (when-not (.startsWith p root)
      (throw (ex-info "storj-node: piece path escapes the pieces directory"
                      {:path rel})))
    p))

(defn file-blobs
  "An `IBlobStore` over the data directory.

  The only one `io-storj-node` ships is in-memory, and a node that forgets
  every piece when the process ends is not storage. Writes go through a
  temporary file and a move so a crash mid-write leaves no half piece that a
  later audit would read as corruption."
  []
  (reify p/IBlobStore
    (-get [_ path]
      (let [p (safe-path path)]
        (when (Files/exists p (into-array java.nio.file.LinkOption []))
          (mapv #(bit-and (int %) 0xff) (Files/readAllBytes p)))))
    (-put [_ path bytes]
      (let [p   (safe-path path)
            tmp (.resolveSibling p (str (.getFileName p) ".tmp"))]
        (Files/createDirectories (.getParent p) (into-array FileAttribute []))
        (Files/write tmp (byte-array (map unchecked-byte bytes))
                     (into-array java.nio.file.OpenOption
                                 [java.nio.file.StandardOpenOption/CREATE
                                  java.nio.file.StandardOpenOption/TRUNCATE_EXISTING
                                  java.nio.file.StandardOpenOption/WRITE]))
        (Files/move tmp p (into-array java.nio.file.CopyOption
                                      [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                       java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
        nil))
    (-delete [_ path]
      (let [p (safe-path path)]
        (when-not (Files/deleteIfExists p)
          ;; DeletePieces counts what it could not do, and succeeding here
          ;; would report a clean sweep over pieces this node never had
          (throw (ex-info "storj-node: no such piece" {:path path})))
        nil))
    (-exists? [_ path]
      (Files/exists (safe-path path) (into-array java.nio.file.LinkOption [])))))

;; ── the node ────────────────────────────────────────────────────────────────

(defn- unhex [s]
  (mapv #(Integer/parseInt % 16) (re-seq #"[0-9a-fA-F]{2}" s)))

(defn- clock []
  (reify p/IClock (-now-seconds [_] (quot (System/currentTimeMillis) 1000))))

;; The satellite's signing key, learned at check-in.
;;
;; `orders/admit` needs it to check the signature on an order limit, and
;; without it every transfer is refused. It is not configuration: the
;; satellite presents its certificate chain on the check-in handshake, the
;; chain is verified against the node id we were told to expect, and the key
;; is the leaf's — the same key `SigneeFromPeerIdentity` uses to verify an
;; order limit. So a node that has checked in has already been handed it, and
;; asking an operator to paste it in would be asking for something the
;; protocol just delivered.
;;
;; The node id is what makes that safe. `check-in!` dials with
;; `:expected-node-id`, so a chain that does not derive to the satellite we
;; meant to reach is refused before this is set.
;;
;; (`defonce` takes no docstring, which is why this is a comment.)
(defonce ^:private satellite-key (atom nil))

(defn context
  "Everything the protocol layers need, from one config.

  Built per connection rather than once, so a node that checks in after it
  started listening does not serve a whole connection with the key it had at
  boot. Until check-in has happened `:satellite-key` is nil and `admit`
  refuses every transfer — a node that accepted them instead would be storing
  pieces on nobody's authority."
  [{:keys [identity-dir satellite-id]}]
  (let [id  (load-identity identity-dir)
        sat (unhex satellite-id)]
    {:node-id       (node-id identity-dir)
     :verifier      v/verifier
     :satellite-key @satellite-key
     :algorithm     :ecdsa-sha256
     :clock         (clock)
     :signer        hk/key-material
     :private-key   (:private-key id)
     :blobs         (file-blobs)
     :paths         (fn ([pid] (piece/blob-path sat pid))
                      ([pid version] (piece/blob-path sat pid version)))
     :identity      id}))

(defn check-in!
  "Introduce this node to its satellite. Returns the response as data.

  `ping_node_success` is the satellite reporting whether it could dial this
  node *back* at `:address`, so a false here with the call itself succeeding
  means the address is wrong or unreachable — not that check-in failed."
  [{:keys [satellite satellite-id address free-disk email wallet] :as cfg}]
  (let [{:keys [identity]} (context cfg)
        [host port] (str/split satellite #":")
        c (htls/connect {:host host :port (parse-long port)
                         :identity identity
                         :verify-opts {:expected-node-id (unhex satellite-id)}
                         :preamble htls/drpc-mux-header})
        ;; the chain has been admitted and its node id matched the one we
        ;; dialled, so this is the satellite's own signing key rather than
        ;; whoever answered the port
        _ (when-let [k (:signing-key (:peer c))] (reset! satellite-key k))
        r (rpc/call (:socket c)
                    {:rpc contact/rpc
                     :request (contact/check-in-request
                               (cond-> {:address address
                                        :version {:version "1.104.5" :release? true}
                                        :capacity {:free-disk free-disk}}
                                 (or email wallet)
                                 (assoc :operator (cond-> {}
                                                    email  (assoc :email email)
                                                    wallet (assoc :wallet wallet)))))})]
    (htls/close! {:socket (:socket c)})
    (if-let [msg (:message r)]
      (contact/read-check-in-response (w/decode msg))
      {:error (or (:message (:error r)) "no response")})))

(defn- handler
  "The unary and streaming halves, over one connection's transfer state."
  [ctx]
  (let [xfers (atom (tr/transfers))]
    {:handle (fn [call] (svc/handle ctx call))
     :on-message
     (fn [{:keys [rpc] :as m}]
       (if-not (or (tr/streaming? rpc) (get @xfers (:stream m)))
         {:out []}
         (let [r (tr/message @xfers ctx m)]
           (reset! xfers (:state r))
           r)))}))

(defonce ^:private listener (atom nil))

(defn start!
  "Listen for satellites and uplinks. Returns the listener, or nil when the
  node is not configured — which is the ordinary state for this app."
  []
  (when-let [cfg (config)]
    (let [boot (context cfg)
          l (htls/listen
             {:port (:port cfg)
              :identity (:identity boot)
              :verify-opts {}
              ;; a real peer arrives through Storj's port multiplexer
              :expect-preamble htls/drpc-mux-header
              :on-connection
              (fn [{:keys [socket]}]
                ;; fresh, so a check-in that happened after boot is visible
                (let [{:keys [handle on-message]} (handler (context cfg))]
                  (rpc/serve-connection socket handle on-message)))
              :on-refused (fn [_] nil)})]
      (reset! listener l)
      (future (while @listener ((:accept l))))
      l)))

(defn stop! []
  (when-let [l @listener]
    (reset! listener nil)
    (htls/close! l)))

(defn status
  "What to show an operator, without starting anything."
  []
  (if-let [cfg (config)]
    {:configured? true
     :node-id     (try (apply str (map #(format "%02x" %) (node-id (:identity-dir cfg))))
                       (catch Exception e {:error (ex-message e)}))
     :satellite   (:satellite cfg)
     :address     (:address cfg)
     :port        (:port cfg)
     :listening?  (some? @listener)
     ;; the two gates, reported rather than discovered
     :satellite-key-known? (some? @satellite-key)
     :notes (cond-> ["a public satellite requires difficulty 36 and an authorized identity"]
              (nil? @satellite-key)
              (conj "no check-in yet, so order limits are refused"))}
    {:configured? false
     :needs ["STORJ_NODE_IDENTITY_DIR" "STORJ_NODE_SATELLITE"
             "STORJ_NODE_SATELLITE_ID" "STORJ_NODE_ADDRESS"]}))
