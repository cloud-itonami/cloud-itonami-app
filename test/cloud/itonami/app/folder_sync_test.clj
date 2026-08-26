(ns cloud.itonami.app.folder-sync-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.folder-sync :as sync])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(defn- utf8-bytes [value]
  (.getBytes ^String value StandardCharsets/UTF_8))

(defn- text [^bytes value]
  (String. value StandardCharsets/UTF_8))

(defrecord MemoryRemote [files sequence]
  sync/RemoteDrive
  (remote-snapshot [_]
    (into {} (map (fn [[path entry]] [path (dissoc entry :bytes)])) @files))
  (remote-bytes [_ entry]
    (:bytes (some (fn [[_ value]] (when (= (:id entry) (:id value)) value))
                  @files)))
  (remote-put! [_ path content media-type]
    (let [n (swap! sequence inc)
          entry {:id (str "remote-" n) :etag (str "etag-" n)
                 :bytes content :media-type media-type
                 :size-bytes (alength ^bytes content)}]
      (swap! files assoc path entry)
      (dissoc entry :bytes)))
  (remote-trash! [_ entry]
    (swap! files
           (fn [current]
             (into {} (remove (fn [[_ value]] (= (:id entry) (:id value))))
                   current)))
    true))

(defn- memory-remote
  ([] (memory-remote {}))
  ([entries]
   (->MemoryRemote
    (atom (into {} (map-indexed
                    (fn [index [path value]]
                      [path {:id (str "seed-" index)
                             :etag (str "seed-etag-" index)
                             :bytes (utf8-bytes value)
                             :media-type "text/plain"
                             :size-bytes (count (utf8-bytes value))}])
                    entries)))
    (atom 0))))

(defn- temporary-config []
  (let [base (.toFile (Files/createTempDirectory
                       "itonami-folder-sync-test"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        root (io/file base "root")]
    (.mkdirs root)
    {:base base
     :root root
     :config {:id "test-root" :path (.getPath root)
              :state-file (.getPath (io/file base "sync-state.edn"))}}))

(defn- write-text! [root path value]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file value)
    file))

(deftest first-sync-merges-both-sides-without-a-silent-winner
  (let [{:keys [root config]} (temporary-config)
        remote (memory-remote {"remote.txt" "from web"})]
    (write-text! root "local.txt" "from disk")
    (let [result (sync/sync-root! config remote)]
      (is (= ["local.txt"] (:pushed result)))
      (is (= ["remote.txt"] (:pulled result)))
      (is (= "from web" (slurp (io/file root "remote.txt"))))
      (is (= "from disk" (text (:bytes (get @(:files remote) "local.txt"))))))

    (testing "the next pass has a durable common ancestor"
      (let [result (sync/sync-root! config remote)]
        (is (= #{"local.txt" "remote.txt"} (set (:unchanged result))))))))

(deftest one-sided-updates-and-deletes-propagate-recoverably
  (let [{:keys [root config]} (temporary-config)
        remote (memory-remote)]
    (write-text! root "edited.txt" "v1")
    (write-text! root "remote-delete.txt" "keep then delete")
    (sync/sync-root! config remote)

    (write-text! root "edited.txt" "v2")
    (swap! (:files remote) dissoc "remote-delete.txt")
    (let [result (sync/sync-root! config remote)]
      (is (= ["edited.txt"] (:pushed result)))
      (is (= ["remote-delete.txt"] (:local-trashed result)))
      (is (not (.exists (io/file root "remote-delete.txt"))))
      (is (= "v2" (text (:bytes (get @(:files remote) "edited.txt")))))
      (is (= 1 (count (filter #(.isFile %)
                             (file-seq (io/file root ".itonami-trash")))))))

    (testing "a local deletion trashes the remote object"
      (Files/delete (.toPath (io/file root "edited.txt")))
      (let [result (sync/sync-root! config remote)]
        (is (= ["edited.txt"] (:remote-trashed result)))
        (is (nil? (get @(:files remote) "edited.txt")))))))

(deftest concurrent-edits-are-preserved-until-an-explicit-local-resolution
  (let [{:keys [root config]} (temporary-config)
        remote (memory-remote)]
    (write-text! root "decision.md" "base")
    (sync/sync-root! config remote)
    (write-text! root "decision.md" "local edit")
    (sync/remote-put! remote "decision.md" (utf8-bytes "web edit") "text/markdown")

    (let [result (sync/sync-root! config remote)
          conflict-files (filter #(.isFile %)
                                 (file-seq (io/file root ".itonami-conflicts")))]
      (is (= ["decision.md"] (:conflicts result)))
      (is (= "local edit" (slurp (io/file root "decision.md"))))
      (is (= ["web edit"] (mapv slurp conflict-files))))

    (testing "editing after seeing the conflict explicitly resolves it"
      (write-text! root "decision.md" "resolved")
      (let [result (sync/sync-root! config remote)]
        (is (= ["decision.md"] (:pushed result)))
        (is (= "resolved"
               (text (:bytes (get @(:files remote) "decision.md")))))))))

(deftest unsafe-remote-paths-are-refused-before-writing
  (let [{:keys [root config base]} (temporary-config)
        remote (memory-remote {"../escape.txt" "no"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"safe relative path"
                          (sync/sync-root! config remote)))
    (is (not (.exists (io/file base "escape.txt"))))
    (is (empty? (sync/local-snapshot root 1024)))))

(deftest remote-writes-cannot-follow-a-local-symbolic-link
  (let [{:keys [root config base]} (temporary-config)
        outside (io/file base "outside")
        link (io/file root "linked")
        remote (memory-remote {"linked/escape.txt" "no"})]
    (.mkdirs outside)
    (Files/createSymbolicLink (.toPath link) (.toPath outside)
                              (make-array java.nio.file.attribute.FileAttribute 0))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"symbolic link"
                          (sync/sync-root! config remote)))
    (is (not (.exists (io/file outside "escape.txt"))))))

(deftest a-local-edit-during-a-remote-read-becomes-a-conflict
  (let [{:keys [root config]} (temporary-config)
        remote (memory-remote)]
    (write-text! root "raced.txt" "base")
    (sync/sync-root! config remote)
    (sync/remote-put! remote "raced.txt" (utf8-bytes "web edit") "text/plain")
    (let [racing-remote
          (reify sync/RemoteDrive
            (remote-snapshot [_] (sync/remote-snapshot remote))
            (remote-bytes [_ entry]
              (write-text! root "raced.txt" "late local edit")
              (sync/remote-bytes remote entry))
            (remote-put! [_ path content media-type]
              (sync/remote-put! remote path content media-type))
            (remote-trash! [_ entry] (sync/remote-trash! remote entry)))
          result (sync/sync-root! config racing-remote)]
      (is (= ["raced.txt"] (:conflicts result)))
      (is (= "late local edit" (slurp (io/file root "raced.txt"))))
      (is (= ["web edit"]
             (mapv slurp (filter #(.isFile %)
                                 (file-seq (io/file root ".itonami-conflicts")))))))))

(deftest remote-files-obey-the-size-limit-before-download
  (let [{:keys [config]} (temporary-config)
        remote (memory-remote {"large.bin" "12345"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"exceeds the configured sync limit"
                          (sync/sync-root! (assoc config :maximum-file-bytes 4)
                                           remote)))))

(deftest unsigned-byte-vectors-from-json-remotes-are-materialized
  (let [{:keys [root config]} (temporary-config)
        delegate (memory-remote {"vector.bin" "abc"})
        vector-remote
        (reify sync/RemoteDrive
          (remote-snapshot [_] (sync/remote-snapshot delegate))
          (remote-bytes [_ entry]
            (mapv #(bit-and (int %) 0xff)
                  (sync/remote-bytes delegate entry)))
          (remote-put! [_ path content media-type]
            (sync/remote-put! delegate path content media-type))
          (remote-trash! [_ entry] (sync/remote-trash! delegate entry)))]
    (is (= ["vector.bin"] (:pulled (sync/sync-root! config vector-remote))))
    (is (= "abc" (slurp (io/file root "vector.bin"))))))

(deftest folder-roots-expose-continuous-manual-and-paused-schedules
  (let [{:keys [config]} (temporary-config)
        base-root (assoc config :actor "did:example:alice")]
    (try
      (is (true? (sync/start! {:folder-sync
                               {:enabled? true
                                :roots [(assoc base-root :schedule :manual)]}})))
      (is (false? (:running? (sync/status "did:example:alice"))))
      (is (= :manual (get-in (sync/status "did:example:alice") [:roots 0 :schedule])))
      (is (empty? (sync/sync-configured! "did:example:alice" #{:continuous})))
      (is (= :paused
             (get-in (sync/set-root-mode! "did:example:alice" "test-root"
                                          :paused :pinned)
                     [:roots 0 :schedule])))
      (is (empty? (sync/sync-configured! "did:example:alice" #{:continuous :manual})))
      (finally
        (sync/stop!)))))

(deftest ordinary-folder-sync-refuses-placeholder-residency
  (let [{:keys [config]} (temporary-config)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"File Provider"
         (sync/start! {:folder-sync
                       {:roots [(assoc config :actor "did:example:alice"
                                      :residency :online-only)]}})))))

(deftest configured-roots-always-have-a-local-owner
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires an actor"
                        (sync/start! {:folder-sync
                                      {:roots [{:id "hosted"
                                                :path "/tmp"
                                                :remote {:kind :http
                                                         :base-url "https://example.com"
                                                         :bearer-token-env "TOKEN"}}]}}))))
