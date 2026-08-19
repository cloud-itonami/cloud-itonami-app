(ns cloud.itonami.app.nfs
  "Serving the Drive as a volume the operating system mounts.

  Three libraries meet here and none of them knows about the others.
  `org-ietf-nfs` speaks NFSv3 over an injected filesystem;
  `cloud.itonami.app.drive-fs` is that filesystem; `kekkai` decides who
  may reach the socket. This namespace is the join, which is the arrangement
  `documents` already uses for `drive` and the office surfaces.

  ## Why NFS

  Measured, not preferred (ADR-2608171200). On macOS it is the only surface
  that mounts with no kernel extension, no third-party install, no code
  signing and no root. FUSE needs macFUSE installed; a File Provider
  extension needs Swift in a signed app; SMB needs NTLMSSP and is an
  addition to a working NFS rather than an alternative.

  ## Authorization, because NFSv3 has none

  `AUTH_SYS` carries a uid the *client* chose. Two things answer that:

  - **The bind address.** Loopback by default, and the default is the
    security boundary rather than a convenience. A LAN-visible NFS export
    with no authentication is an open Drive.
  - **A netmap the control plane signed.** `:nfs {:netmap {:envelope-file …
    :authority-spki-b64 …} :capability …}`. `kekkai.envelope/verify` checks
    the signer, the digest and the signature BEFORE parsing, and admission is
    `kekkai.node.netmap`'s `authorized?` + `permitted?` — the node's own
    reading, reused rather than re-derived here. A peer with no matching edge,
    or an expired node key, is closed before a byte is read.

    This sentence used to say `kekkai.acl/edge-allowed?` was signed
    upstream. It was not. `:policy` is a map an operator types into
    configuration; calling it a netmap did not make the control plane
    something other than a file on this host, and `kekkai-node` had been
    verifying envelopes since 2026-08-06 while this side never received the
    other half. `:policy` still works and now needs
    `:allow-unsigned-policy? true` — the same switch the node puts in front
    of an unsigned netmap, for the same reason.

  The capability an export rides is **configured, never inferred**: kekkai's
  vocabulary is `:overlay :ssh :private-http :tun` and has no `:nfs`, so a
  default chosen here would silently grant whatever that default was to every
  edge that already carried it.

  `:actors` maps a node id to the Drive owner it is admitted as. That mapping
  lives here because kekkai has no notion of an actor and must not be asked to
  carry one — the previous code read `:node/actor` off a netmap node, a field
  that exists nowhere in kekkai.

  What kekkai deliberately does *not* decide is read-versus-write — its
  charter authorizes reachability and never what flows. That granularity is
  the Drive's ACL, which every read and write here already goes through.

  ## Disabled unless configured

  `:nfs {:enabled? true}`. Not inferred from anything: a Drive that becomes
  network-reachable because a port happened to be free is the failure this
  is written to avoid."
  (:require [clojure.string :as str]
            [cloud.itonami.app.drive-fs :as drive-fs]
            [kekkai.acl :as acl]
            [kekkai.envelope :as envelope]
            [kekkai.node.netmap :as wire]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [nfs.tcp :as tcp]))

(defonce ^:private server (atom nil))

(def ^:const default-port 12049)
(def ^:const default-export "/kotoba")

(defn verified-netmap
  "The wire netmap this export authorises against, or a thrown refusal.

  `:envelope` is the Ed25519 envelope `kekkai.netmap`/`kekkai.envelope`
  publishes; `:authority-spki-b64` is the signer this deployment accepts.
  Verification is `kekkai.envelope/verify` — the publisher-side verifier, whose
  agreement with `kekkai.node.signed-netmap` is already pinned byte for byte —
  and it checks the signer, the digest and the signature BEFORE it parses.

  Why this exists: `:policy` was a map an operator typed into configuration.
  It was called a netmap and nothing had signed it, so the control plane was a
  file on this host. `kekkai-node` has verified envelopes since 2026-08-06 and
  this side had never been given the other half."
  [{:keys [envelope envelope-file authority-spki-b64]}]
  (when-not (string? (not-empty authority-spki-b64))
    (throw (ex-info "nfs: :netmap needs :authority-spki-b64 — the signer this export accepts"
                    {:type :nfs/netmap-authority-required})))
  (let [env (or envelope
                (when envelope-file
                  (let [f (io/file envelope-file)]
                    (when-not (.exists f)
                      (throw (ex-info "nfs: :netmap :envelope-file does not exist"
                                      {:type :nfs/netmap-envelope-missing
                                       :file (str envelope-file)})))
                    ;; `clojure.edn`, not the Clojure reader: this file is read
                    ;; BEFORE anything about it has been verified.
                    (edn/read-string (slurp f)))))]
    (when-not (map? env)
      (throw (ex-info "nfs: :netmap needs :envelope or :envelope-file"
                      {:type :nfs/netmap-envelope-required})))
    (envelope/verify env authority-spki-b64)))

(defn- peer-by-address
  "The peer a source address belongs to, from a verified wire netmap."
  [netmap address]
  (first (filter #(= address (:node/overlay-ip %)) (:netmap/peers netmap))))

(defn- admit-by-netmap
  "`peer -> principal | nil` against a VERIFIED wire netmap.

  Admission and the edge grant are both `kekkai.node.netmap`'s, not a second
  reading of the same rules written here. `authorized?` is what makes an
  expired node key a refusal rather than a peer, and `permitted?` folds
  capability and port together so neither can be satisfied without the other.

  The capability is configured and never inferred. kekkai's vocabulary is
  `:overlay :ssh :private-http :tun` and has no `:nfs`, so an export must say
  which capability it rides rather than have this code pick one — a default
  here would grant whatever that default happened to be, to every edge that
  already had it."
  [{:keys [netmap actors actor capability port]} address]
  (let [self (:node/id (:netmap/self netmap))
        now (quot (System/currentTimeMillis) 1000)]
    (when-let [peer (peer-by-address netmap address)]
      (when (and (wire/authorized? peer now)
                 (wire/permitted? netmap (:node/id peer) self capability port))
        ;; The uid the client claimed is not consulted, for the same reason as
        ;; the policy path: the principal is the node the netmap says this
        ;; address is. `:actors` is this application's own map from node to
        ;; Drive owner — kekkai has no notion of an actor and must not be
        ;; asked to carry one.
        {:actor (or (get actors (:node/id peer)) actor)
         :via :kekkai
         :node (:node/id peer)}))))

(defn- node-for
  "The kekkai node a peer address belongs to, from the configured netmap.

  Nil when nothing in the netmap claims that address, which `authorize`
  turns into a refusal — an unknown peer is not a peer."
  [policy address]
  (first (filter #(= address (:node/overlay-ip %)) (:nodes policy))))

(defn authorize-fn
  "`(fn [peer] principal | nil)` for `nfs.tcp/start!`.

  With no policy, loopback is the only thing admitted and it is admitted as
  the configured actor — which is the single-user desktop case, and is why
  the bind address matters so much there.

  With a policy, the answer comes from `kekkai.acl`: the peer's node must
  reach this node's export port under some grant. `edge-allowed?` returns
  the allowed ports or nil, and `\"*\"` is the wildcard it publishes."
  [{:keys [actor policy netmap actors capability self-node port]}]
  (fn [{:keys [remote-address]}]
    (cond
      ;; A verified netmap answers first when there is one. Ordered rather than
      ;; merged: a deployment that has both should not have the unsigned half
      ;; able to widen what the signed half granted.
      (some? netmap)
      (admit-by-netmap {:netmap netmap :actors actors :actor actor
                        :capability capability :port port}
                       remote-address)

      (nil? policy)
      (when (contains? #{"127.0.0.1" "::1" "0:0:0:0:0:0:0:1"} remote-address)
        {:actor actor :via :loopback})

      :else
      (when-let [peer (node-for policy remote-address)]
        (let [ports (acl/edge-allowed? policy peer self-node)]
          (when (and ports
                     (or (some #{"*"} ports)
                         (some #{(str port)} ports)))
            ;; The uid the client claimed is not consulted. The principal is
            ;; the node the netmap says this address is, which is the whole
            ;; point of putting kekkai in front of a protocol that
            ;; authenticates nothing.
            {:actor (or (:node/actor peer) actor)
             :via :kekkai
             :node (:node/id peer)}))))))

(defn config
  "The `:nfs` section, with the defaults applied."
  [configuration]
  (let [c (:nfs configuration)]
    (when (:enabled? c)
      (let [port (or (:port c) default-port)
            netmap (when-let [n (:netmap c)]
                     (verified-netmap n))]
        (when (and (:policy c) (not (:allow-unsigned-policy? c)))
          ;; The same switch `kekkai-node` puts in front of an unsigned netmap,
          ;; and for the same reason: a hand-written policy map is the operator
          ;; deciding reachability, which is fine as long as nobody can read
          ;; this configuration and believe the control plane decided it.
          (throw (ex-info (str "nfs: :policy is an unsigned, operator-written map. "
                               "Publish a signed :netmap, or say "
                               ":allow-unsigned-policy? true.")
                          {:type :nfs/unsigned-policy})))
        (when (and netmap (nil? (:capability c)))
          (throw (ex-info (str "nfs: :netmap needs :capability — kekkai has no "
                               ":nfs capability, so the export must name the one it rides")
                          {:type :nfs/capability-required})))
        {:enabled? true
         :port port
         :bind (or (:bind c) "127.0.0.1")
         :export (or (:export c) default-export)
         :actor (:actor c)
         :policy (:policy c)
         :netmap netmap
         :actors (:actors c)
         :capability (:capability c)
         :self-node (or (:self-node c) (:netmap/self netmap))}))))

(defn start!
  "Start the export if this deployment configured one. Idempotent."
  [configuration]
  (when-let [{:keys [port bind export actor policy netmap actors capability self-node]}
             (config configuration)]
    (when-not @server
      (when (and (not= "127.0.0.1" bind) (nil? policy) (nil? netmap))
        ;; A non-loopback bind with no netmap is an unauthenticated Drive on
        ;; the network. Refusing is the only honest response; falling back to
        ;; loopback would silently ignore what the operator asked for.
        (throw (ex-info "nfs: a non-loopback bind needs a kekkai policy"
                        {:type :nfs/unauthenticated-bind :bind bind})))
      (when (str/blank? (str actor))
        (throw (ex-info "nfs: :actor names whose Drive is exported"
                        {:type :nfs/actor-required})))
      (reset! server
              (tcp/start!
               {:dir export :port port :bind bind
                :authorize (authorize-fn {:actor actor :policy policy
                                          :netmap netmap :actors actors
                                          :capability capability
                                          :self-node self-node :port port})
                :filesystem-for (fn [principal]
                                  (drive-fs/filesystem (:actor principal)))}))
      @server)))

(defn stop! []
  (when-let [s @server]
    ((:stop! s))
    (reset! server nil)
    true))

(defn status
  "Where the export is, or that there is none. Never the policy itself."
  []
  (if-let [s @server]
    {:schema "cloud.itonami.app.nfs.v1" :running? true
     :port (:port s) :export (:dir s)}
    {:schema "cloud.itonami.app.nfs.v1" :running? false}))

(defn mount-command
  "The one line an operator runs. Printed rather than executed: mounting is
  a change to the machine, and `mount_nfs` needs no root only because the
  mount point is one the user already owns."
  ([] (mount-command (status) "~/CloudItonami"))
  ([{:keys [port export]} at]
   (str "mkdir -p " at " && mount_nfs -o vers=3,tcp,port=" port
        ",mountport=" port ",nolocks,soft 127.0.0.1:" export " " at)))
