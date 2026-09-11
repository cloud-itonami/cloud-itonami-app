(ns cloud.itonami.app.bot-workspace
  "Cloud Itonami-owned workspaces for Bots.

  A managed workspace is an ordinary local Git root so the existing bounded
  workspace tools can operate offline. The same directory is registered with
  folder-sync and reconciled with `Drive/Bots/<bot-id>/Workspace`; an optional
  hosted Drive adapter makes that folder the network rendezvous for another
  device."
  (:require [clojure.java.io :as io]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.folder-sync :as folder-sync])
  (:import [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util UUID]))

(def schema "cloud.itonami.app.bot-workspace.v1")

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn- digest [value]
  (hex (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes (str value) "UTF-8"))))

(defn sync-id [bot-id]
  (str "bot-" (subs (digest bot-id) 0 32)))

(defn- workspace-directory [session bot-id]
  (.getCanonicalFile
   (io/file (config/data-dir) "bot-workspaces"
            (str "org-" (subs (digest (:organization-id session)) 0 20))
            (sync-id bot-id))))

(defn- run-git-init! [directory]
  (when-not (.isDirectory (io/file directory ".git"))
    (let [process (.start (doto (ProcessBuilder.
                                 ^java.util.List
                                 ["/usr/bin/git" "init" "-q" "--initial-branch=main"])
                            (.directory directory)
                            (.redirectErrorStream true)))
          output (slurp (.getInputStream process))
          exit (.waitFor process)]
      (when-not (zero? exit)
        (throw (ex-info "Cloud Itonami workspace を初期化できませんでした。"
                        {:type :bot-workspace/git-init-failed
                         :exit exit :output (subs output 0 (min 1000 (count output)))}))))))

(defn- atomic-edn! [file value]
  (let [temporary (io/file (.getParentFile file)
                           (str "." (.getName file) ".tmp-" (UUID/randomUUID)))]
    (.mkdirs (.getParentFile file))
    (spit temporary (str (pr-str value) "\n"))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))))

(defn remote-config [configuration]
  (or (get-in configuration [:bots :workspace-sync :remote])
      (get-in configuration [:folder-sync :remote])))

(defn root-config [configuration b]
  (cond-> {:id (or (:bot/workspace-sync-id b) (sync-id (:bot/id b)))
           :path (:bot/workspace b)
           :actor (:bot/owner b)
           :drive-path ["Bots" (:bot/id b) "Workspace"]
           :schedule :continuous
           :residency :pinned}
    (remote-config configuration) (assoc :remote (remote-config configuration))))

(defn register! [configuration b]
  (when (and (= :cloud-itonami (:bot/workspace-kind b))
             (:bot/workspace b))
    (folder-sync/register-managed-root! (root-config configuration b)))
  b)

(defn detach! [b]
  (when-let [id (:bot/workspace-sync-id b)]
    (folder-sync/unregister-managed-root! id))
  true)

(defn provision! [configuration session bot-id]
  (let [directory (workspace-directory session bot-id)
        path (.getCanonicalPath directory)
        id (sync-id bot-id)]
    (.mkdirs directory)
    (run-git-init! directory)
    (atomic-edn! (io/file directory ".itonami" "workspace.edn")
                 {:schema schema
                  :bot/id bot-id
                  :organization/id (:organization-id session)
                  :sync/id id
                  :drive/path ["Bots" bot-id "Workspace"]})
    (let [b {:bot/id bot-id
             :bot/owner (:user-id session)
             :bot/organization (:organization-id session)
             :bot/workspace path
             :bot/workspace-kind :cloud-itonami
             :bot/workspace-sync-id id}]
      (register! configuration b)
      b)))

(defn summary [configuration b]
  (when (= :cloud-itonami (:bot/workspace-kind b))
    (register! configuration b)
    (let [root-id (or (:bot/workspace-sync-id b) (sync-id (:bot/id b)))
          root (some #(when (= root-id (:id %)) %)
                     (:roots (folder-sync/status (:bot/owner b))))
          network? (= :http (keyword (or (get-in (root-config configuration b)
                                                  [:remote :kind])
                                         :local)))]
      {:schema schema
       :kind "cloud-itonami"
       :path (:bot/workspace b)
       :drive-path ["Bots" (:bot/id b) "Workspace"]
       :mode (if network? "network" "this-device")
       :state (name (or (:status root) :pending))
       :schedule (name (or (:schedule root) :continuous))
       :at (:at root)
       :counts (:counts root)
       :error (:error root)})))

(defn sync! [configuration b]
  (register! configuration b)
  (folder-sync/sync-managed-root! (:bot/owner b)
                                  (or (:bot/workspace-sync-id b)
                                      (sync-id (:bot/id b)))))
