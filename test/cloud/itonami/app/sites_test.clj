(ns cloud.itonami.app.sites-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.sites :as sites]
            [cloud.itonami.app.store :as store]))

(def previous (atom nil))

(use-fixtures :each
  (fn [run]
    (reset! previous @store/state)
    (reset! store/state (store/initial-state))
    (doseq [[organization project] [["org" "alpha"] ["org" "beta"]
                                    ["org-a" "alpha"] ["org-b" "alpha"]]]
      (store/transact! assoc-in [:chat-projects [organization project]]
                       {:project-id project :title project}))
    (try (run) (finally (reset! store/state @previous)))))

(deftest sites-are-project-scoped-and-draft-first
  (let [alpha (sites/create! "org" "user" {:project "alpha" :title "Alpha" :slug "home"})
        beta (sites/create! "org" "user" {:project "beta" :title "Beta" :slug "home"})]
    (is (= "draft" (:status alpha)))
    (is (= [(:id alpha)] (mapv :id (:items (sites/list-sites "org" "alpha")))))
    (is (= [(:id beta)] (mapv :id (:items (sites/list-sites "org" "beta")))))
    (is (nil? (sites/published (:id alpha))))))

(deftest editing-invalidates-a-publication-until-republished
  (let [site (sites/create! "org" "user" {:project "alpha" :title "Alpha" :slug "alpha"})
        id (:id site)]
    (sites/publish! "org" "user" "alpha" id)
    (is (= "published" (:status (sites/published id))))
    (sites/update! "org" "user" id {:project "alpha" :html "<h1>changed</h1>"})
    (is (nil? (sites/published id)))
    (is (= "<h1>changed</h1>" (:html (sites/detail "org" "alpha" id))))))

(deftest slug-and-tenant-boundaries-are-enforced
  (sites/create! "org" "user" {:project "alpha" :title "Alpha" :slug "home"})
  (is (thrown? clojure.lang.ExceptionInfo
               (sites/create! "org" "user"
                              {:project "missing" :title "Missing" :slug "missing"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (sites/create! "org" "user" {:project "alpha" :title "Again" :slug "home"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (sites/create! "org" "user" {:project "alpha" :title "Bad" :slug "Bad slug"})))
  (testing "another organization cannot resolve a private site"
    (let [site (sites/create! "org-a" "user" {:project "alpha" :title "A" :slug "a"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sites/detail "org-b" "alpha" (:id site)))))))

(deftest starter-page-escapes-the-site-title
  (let [site (sites/create! "org" "user"
                            {:project "alpha" :title "<em>A & B</em>" :slug "safe"})
        html (:html (sites/detail "org" "alpha" (:id site)))]
    (is (not (.contains html "<em>A & B</em>")))
    (is (.contains html "&lt;em&gt;A &amp; B&lt;/em&gt;"))))
