(ns cloud.itonami.app.drive-store-migration-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.drive-crypto :as crypto]
            [cloud.itonami.app.drive-store-migration :as migration]
            [cloud.itonami.app.kotobase-objects :as kotobase]
            [drive.object :as object]
            [drive.store.memory :as memory]
            [drive.workspace :as ws]))

(defn- target-store []
  (let [held (atom {})]
    {:held held
     :store
     (reify
       kotobase/IContentAddressed
       (content-ref [_ bytes] (str "cid-" (hash (vec bytes))))
       object/IObjectStore
       (-put-object [_ ref bytes] (swap! held assoc ref (vec bytes)))
       (-get-object [_ ref] (get @held ref))
       (-delete-object [_ _] false)
       (-object-exists? [_ ref] (contains? @held ref)))}))

(deftest migration-seals-legacy-and-commits-only-verified-cids
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "drive-store-migration"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        source (memory/store)
        body (mapv int (.getBytes "legacy plaintext" java.nio.charset.StandardCharsets/UTF_8))
        workspace (-> (ws/workspace "drive-alice" "alice" 100000)
                      (ws/create-file "f1" "root"
                                      {:drive/title "legacy.txt"
                                       :drive/permissions {"alice" :owner}}
                                      "alice"))
        written (object/write-item workspace source "f1" "alice" body
                                   {:object-ref "obj-legacy"})
        state {:drive {:workspaces {"alice" (:workspace written)}}}
        {:keys [held store]} (target-store)]
    (with-redefs [config/data-dir (fn [] (.toFile temporary))]
      (let [{next-state :state report :report}
            (migration/migrate-state state source store)
            item (get-in next-state [:drive :workspaces "alice"
                                     :drive.workspace/items "f1"])
            ref (:drive/object-ref item)
            package (get @held ref)]
        (is (= 1 (:versions report)))
        (is (= 1 (:sealed-legacy report)))
        (is (:drive/encrypted? item))
        (is (crypto/encrypted? package))
        (is (= body (crypto/open "alice" package)))
        (is (= ref (get-in item [:drive/versions 0 :drive.version/object-ref])))))))

(deftest missing-source-refuses-before-returning-a-candidate
  (let [{:keys [store]} (target-store)
        source (memory/store)
        state {:drive {:workspaces
                       {"alice" {:drive.workspace/items
                                 {"f1" {:drive/id "f1" :drive/kind :file
                                        :drive/permissions {"alice" :owner}
                                        :drive/object-ref "missing"
                                        :drive/versions
                                        [{:drive.version/object-ref "missing"}]}}}}}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source object is missing"
                          (migration/migrate-state state source store)))))
