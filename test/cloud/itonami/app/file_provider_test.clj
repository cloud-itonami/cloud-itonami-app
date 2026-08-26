(ns cloud.itonami.app.file-provider-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.file-provider :as provider]
            [cloud.itonami.app.store :as store]
            [drive.store.memory :as memory])
  (:import (java.nio.charset StandardCharsets)))

(def actor "user-file-provider")

(defn- utf8 [text] (.getBytes ^String text StandardCharsets/UTF_8))
(defn- text [value] (String. (byte-array (map unchecked-byte value))
                            StandardCharsets/UTF_8))

(defn- with-state [f]
  (let [state (atom (store/initial-state))]
    (with-redefs [store/snapshot (fn [] @state)
                  store/transact! (fn [g & args] (apply swap! state g args))]
      (f state (memory/store)))))

(deftest finder-round-trip-and-modes-use-the-portable-policy
  (with-state
    (fn [_ object-store]
      (let [folder (provider/create! actor provider/root-id "検証" true object-store)
            file (provider/create! actor (:id folder) "hello.txt" false object-store)
            uploaded (provider/upload! actor (:id file) (utf8 "hello") object-store)]
        (is (= provider/root-id (:parentID folder)))
        (is (:directory folder))
        (is (= (:id folder) (:parentID file)))
        (is (= "hello" (text (:bytes (provider/materialize actor (:id file)
                                                           object-store)))))
        (is (= "automatic" (:residency uploaded)))
        (is (= "pinned" (:residency
                          (provider/set-mode! actor (:id file) :manual :pinned))))
        (is (= [(:id file)]
               (mapv :id (:items (provider/children actor (:id folder))))))
        (is (= "renamed.txt"
               (:name (provider/modify! actor (:id file) provider/root-id
                                        "renamed.txt"))))
        (is (= #{(:id folder) (:id file)}
               (set (map :id (:items (provider/children actor provider/root-id))))))
        (provider/set-mode! actor (:id file) :paused :online-only)
        (is (thrown? clojure.lang.ExceptionInfo
                     (provider/materialize actor (:id file) object-store)))
        (is (true? (provider/delete! actor (:id file))))
        (is (= [(:id folder)]
               (mapv :id (:items (provider/children actor provider/root-id)))))))))
