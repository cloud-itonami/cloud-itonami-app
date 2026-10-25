;; Serve one actor's Drive over NFS against an isolated data dir.
(require '[cloud.itonami.app.documents :as documents]
         '[cloud.itonami.app.nfs :as nfs-service]
         '[cloud.itonami.app.store :as store]
         '[drive.workspace :as ws])

(def actor "usr-demo")

;; A folder and a document, made through the Drive's own API so what the
;; mount shows is the Drive and not a fixture.
(let [state (store/snapshot)
      workspace (documents/workspace-for state actor)
      root (:drive.workspace/root-id workspace)
      staged (-> workspace
                 (ws/create-folder "folder-notes" root "notes" actor)
                 (ws/create-file "file-readme" root
                                 {:drive/title "README.txt"
                                  :drive/media-type "text/plain"
                                  :drive/created-at (store/now)}
                                 actor))]
  (documents/save-workspace! actor staged))

(def server
  (nfs-service/start! {:nfs {:enabled? true :actor actor
                             :port (Integer/parseInt
                                    (or (first *command-line-args*) "12050"))}}))

(println (str "drive nfs on 127.0.0.1:" (:port server) " export " (:dir server)))
(println (nfs-service/mount-command (nfs-service/status) "/tmp/drive-mnt"))
(flush)
@(promise)
