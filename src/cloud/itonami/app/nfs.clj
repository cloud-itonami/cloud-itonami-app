(ns cloud.itonami.app.nfs
  "Serving the Drive as a volume the operating system mounts.

  Three libraries meet here and none of them knows about the others.
  `org-ietf-nfs` speaks NFSv3 over an injected filesystem;
  `cloud.itonami.app.drive-fs` is that filesystem; `kekkai.acl` decides who
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
  - **`kekkai.acl/edge-allowed?`** when a netmap policy is configured: pure,
    deny-by-default, port-granular, and signed upstream. A peer with no
    matching grant is closed before a byte is read.

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
            [nfs.tcp :as tcp]))

(defonce ^:private server (atom nil))

(def ^:const default-port 12049)
(def ^:const default-export "/kotoba")

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
  [{:keys [actor policy self-node port]}]
  (fn [{:keys [remote-address]}]
    (cond
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
      {:enabled? true
       :port (or (:port c) default-port)
       :bind (or (:bind c) "127.0.0.1")
       :export (or (:export c) default-export)
       :actor (:actor c)
       :policy (:policy c)
       :self-node (:self-node c)})))

(defn start!
  "Start the export if this deployment configured one. Idempotent."
  [configuration]
  (when-let [{:keys [port bind export actor policy self-node]} (config configuration)]
    (when-not @server
      (when (and (not= "127.0.0.1" bind) (nil? policy))
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
