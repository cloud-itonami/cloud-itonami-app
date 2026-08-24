(ns cloud.itonami.app.conversation-context-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.conversation-context :as context]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.store :as store]))

(def session {:organization-id "org-context" :user-id "alice"})

(defn- with-store [f]
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (f)
      (finally (reset! store/state previous)))))

(deftest references-are-bounded-canonical-and-ordered
  (is (= [{:kind "project" :target "alpha"}
          {:kind "folder" :target "folder-1"}]
         (context/normalize-refs
          [{:kind :project :target " alpha "}
           {:kind "project" :target "alpha"}
           {:kind "folder" :target "folder-1"}]))))
  (is (thrown? clojure.lang.ExceptionInfo
               (context/normalize-refs [{:kind "account" :target "secret"}])))
  (is (thrown? clojure.lang.ExceptionInfo
               (context/normalize-refs
                (repeat 13 {:kind "project" :target "alpha"}))))

(deftest multiple-sources-produce-bounded-receipts-not-authority
  (with-store
    (fn []
      (doseq [[id title] [["alpha" "Alpha"] ["beta" "Beta"]]]
        (store/transact! assoc-in [:chat-projects ["org-context" id]]
                         {:project-id id :title title :description (str title " notes")}))
      (let [resolved (context/resolve-refs
                      session [{:kind "project" :target "alpha"}
                               {:kind "project" :target "beta"}])]
        (is (= 2 (count (:refs resolved))))
        (is (= 2 (count (:receipts resolved))))
        (is (every? #(re-matches #"[0-9a-f]{64}" (:digest %))
                    (:receipts resolved)))
        (is (str/includes? (:prompt resolved) "Alpha notes"))
        (is (str/includes? (:prompt resolved) "Beta notes"))
        (is (str/includes? (:prompt resolved) "does not grant tools"))
        (is (nil? (:tools resolved)))
        (is (nil? (:accounts resolved)))))))

(deftest prompt-and-each-source-stay-inside-the-provider-envelope
  (with-store
    (fn []
      (doseq [id ["a" "b" "c" "d"]]
        (store/transact! assoc-in [:chat-projects ["org-context" id]]
                         {:project-id id :title id
                          :description (apply str (repeat 20000 id))}))
      (let [resolved (context/resolve-refs
                      session (mapv #(hash-map :kind "project" :target %)
                                    ["a" "b" "c" "d"]))]
        (is (<= (count (:prompt resolved)) context/max-prompt-chars))
        (is (every? #(<= (:chars %) context/max-source-chars)
                    (:receipts resolved)))))))

(deftest sheets-are-datasets-and-must-resolve-as-such
  (with-store
    (fn []
      (with-redefs [documents/documents
                    (fn [& _] [{:id "sheet-1" :name "Numbers"
                                :resource-kind ":sheets/workbook"}])
                    documents/folders (fn [& _] {})
                    documents/content
                    (fn [& _] {:item {:id "sheet-1" :name "Numbers"}
                               :resource-kind ":sheets/workbook"
                               :resource {:tabs []}})]
        (is (= "dataset" (:kind (first (:sources (context/catalog session))))))
        (is (= "dataset"
               (get-in (context/resolve-refs
                        session [{:kind "dataset" :target "sheet-1"}])
                       [:receipts 0 :kind])))))))

(deftest catalog-and-resolution-are-organization-scoped
  (with-store
    (fn []
      (store/transact! assoc-in [:chat-projects ["org-context" "visible"]]
                       {:project-id "visible" :title "Visible"})
      (store/transact! assoc-in [:chat-projects ["other" "hidden"]]
                       {:project-id "hidden" :title "Hidden"})
      (let [folder (:item (documents/create-folder! "Research" "alice"))
            sources (:sources (context/catalog session))]
        (is (some #(= "visible" (:target %)) sources))
        (is (some #(= (:id folder) (:target %)) sources))
        (is (not-any? #(= "hidden" (:target %)) sources))
        (is (str/includes?
             (:prompt (context/resolve-refs
                       session [{:kind "folder" :target (:id folder)}]))
             "Research")))
      (testing "a saved reference is checked again rather than trusted"
        (is (thrown? clojure.lang.ExceptionInfo
                     (context/resolve-refs session
                                           [{:kind "project" :target "hidden"}])))))))
