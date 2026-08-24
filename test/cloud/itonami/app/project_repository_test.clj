(ns cloud.itonami.app.project-repository-test
  "Local Git projects: the catalogue, and what creating one actually does.

  This namespace arrived as an untracked file in a working tree that was 37
  commits behind `main` — on no branch and no remote, one `git checkout` away
  from gone. It landed on its own, without the 26 modified tracked files beside
  it: those are a divergent evolution of this app's mail and server namespaces,
  and replaying them would have rolled back the multi-account mail work `main`
  has since gained. The one thing it genuinely needed from that tree was
  `documents/ensure-folder-path!`, which was ported additively.

  So these tests exist to say what survived the rescue actually works, rather
  than that it compiles."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.project-repository :as projects]
            [cloud.itonami.app.store :as store]))

(defn- scope
  ([] (scope "default"))
  ([project-id]
   {:organization-id "org-test" :user-id "user-test" :project-id project-id}))

(defn- reset-store! []
  (store/transact! (fn [state]
                     (-> state
                         (dissoc :chat-projects :project-workspaces
                                 :drive-artifacts)))))

(use-fixtures :each (fn [run] (reset-store!) (run) (reset-store!)))

(deftest storage-owner-separates-every-triple
  (testing "one editable repository per organization, user AND project — two of
            the three matching is not the same owner"
    (is (not= (projects/storage-owner (scope "a"))
              (projects/storage-owner (scope "b"))))
    (is (not= (projects/storage-owner {:organization-id "org-1" :user-id "u"
                                       :project-id "p"})
              (projects/storage-owner {:organization-id "org-2" :user-id "u"
                                       :project-id "p"})))
    (is (= (projects/storage-owner (scope "a"))
           (projects/storage-owner (scope "a")))
        "and it is stable, or yesterday's repository is unreachable today"))

  (testing "the owner is opaque — it carries no organization or user in the clear"
    (let [owner (projects/storage-owner (scope "secret-project"))]
      (is (str/starts-with? owner "usr-"))
      (is (not (str/includes? owner "org-test")))
      (is (not (str/includes? owner "secret-project"))))))

(deftest the-catalogue-is-empty-before-anything-is-created
  (let [snapshot (projects/local-projects-snapshot (scope))]
    (is (= "cloud.itonami.app.projects.v1" (:schema snapshot)))
    (is (= "local" (:status snapshot)))
    (is (= [] (:items snapshot)))
    (testing "GitHub is named as optional, never as the authority for this answer"
      (is (= "optional" (get-in snapshot [:integration :github :mode]))))))

(deftest conversation-context-is-bounded-and-carries-no-authority
  (store/transact!
   (fn [state]
     (-> state
         (assoc-in [:chat-projects ["org-test" "alpha"]]
                   {:project-id "alpha" :title "Alpha" :description "Launch work"})
         (assoc-in [:project-workspaces ["org-test" "alpha"]]
                   {:repositories [{:name "app" :url "https://example.invalid/app"}]
                    :issues {"i-1" {:id "i-1" :number 1 :title "Ship UI"
                                     :column "ready"}}}))))
  (let [context (projects/project-context (scope "alpha"))
        prompt (projects/project-context-prompt (scope "alpha"))]
    (is (= "Alpha" (:title context)))
    (is (= 1 (:issue-count context)))
    (is (nil? (:directory context)))
    (is (nil? (:tools context)))
    (is (nil? (get-in context [:repositories 0 :url])))
    (is (str/includes? prompt "does not grant tools"))
    (is (str/includes? prompt "Ship UI")))
  (is (nil? (projects/project-context (scope "missing")))))

(deftest creating-a-project-makes-a-real-git-repository
  ;; A project id unique to this test. The directory is
  ;; `projects/<organization-storage-id>/<slug>` and that middle segment is a
  ;; private hash, so the only way to assert "exactly one was made" without
  ;; recomputing it is to pick a slug no other test uses — measured, when
  ;; `mail-projects-test` created its own `alpha` under a different
  ;; organization and this counted two.
  (let [item (projects/create-project! (scope "alpha-on-disk")
                                       {:title "Alpha" :description "first"})]
    (testing "the record says what it is"
      (is (= "alpha-on-disk" (:project-id item)))
      (is (= "Alpha" (:title item)))
      (is (true? (:git-initialized? item))))

    (testing "and there is a repository on disk, not just a row in the store.

              Found by walking for THIS project's slug rather than by rebuilding
              the path: it is `projects/<organization-storage-id>/<slug>`, and
              that middle segment is a private hash. Counting every `.git` under
              the root would be wrong for a different reason — the store is reset
              between tests but the directory is not, so repositories from
              earlier tests and earlier runs are still there."
      (let [directories (->> (file-seq (io/file (config/data-dir) "projects"))
                             (filter #(and (.isDirectory %)
                                           (= (:project-slug item) (.getName %)))))]
        (is (= 1 (count directories)))
        (is (.isDirectory (io/file (first directories) ".git")))
        (is (.isFile (io/file (first directories) ".itonami" "project.edn")))))

    (testing "the catalogue now lists it, with its counts"
      (let [items (:items (projects/local-projects-snapshot (scope)))]
        (is (= 1 (count items)))
        (is (= "alpha-on-disk" (:project-id (first items))))
        (is (= 0 (:repository-count (first items))))
        (is (= 0 (:issue-count (first items))))))))

(deftest creating-the-same-project-twice-is-one-project
  (testing "opening a project repeatedly must not make a row of duplicates"
    (projects/create-project! (scope "beta") {:title "Beta"})
    (projects/create-project! (scope "beta") {:title "Beta"})
    (is (= 1 (count (:items (projects/local-projects-snapshot (scope))))))))

(deftest a-project-id-is-checked-before-anything-is-written
  (testing "blank and over-long ids are refused by name rather than producing a
            repository nobody asked for"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Project ID"
                          (projects/create-project! (scope "   ") {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"80"
                          (projects/create-project!
                           (scope (apply str (repeat 81 "x"))) {})))
    (is (empty? (:items (projects/local-projects-snapshot (scope)))))))

(deftest one-organization-does-not-see-another-s-projects
  (projects/create-project! {:organization-id "org-a" :user-id "u"
                             :project-id "shared-name"}
                            {:title "A"})
  (let [other (projects/local-projects-snapshot
               {:organization-id "org-b" :user-id "u" :project-id "default"})]
    (is (empty? (:items other))
        "the catalogue filters by organization, so a shared project id is not
         a shared project")))

(deftest a-board-answers-for-a-project
  (projects/create-project! (scope "gamma") {:title "Gamma"})
  (let [board (projects/project-board (scope "gamma"))]
    (is (some? board))
    (is (contains? board :issues))))
