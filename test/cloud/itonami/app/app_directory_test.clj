(ns cloud.itonami.app.app-directory-test
  "The slot table, and the two things that keep a hand-written table honest.

  The gate is `every-connector-this-build-carries-has-a-job`: it runs against
  the REAL registry, so a connector added to a later build turns this red. The
  fallback is `other-row`, which keeps that same connector reachable for a
  person in the meantime. Both are asserted, because a fallback nothing
  exercises and a gate nothing can fail are the same kind of nothing."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [cloud.itonami.app.app-directory :as directory]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.connectors :as connectors]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.web :as web]))

(defn- row
  ([id] (row id {}))
  ([id overrides]
   (merge {:id id :name (str/upper id) :enabled-tool-count 3
           :configurable? true :authable? true :connected? false}
          overrides)))

(deftest availability-answers-the-three-cases-and-keeps-them-apart
  (testing "usable"
    (is (= :usable (directory/availability (row "com.google.gmail"))))
    (is (true? (directory/usable? (row "com.google.gmail")))))
  (testing "no enabled tool in this build"
    (is (= :no-tools (directory/availability (row "a" {:enabled-tool-count 0}))))
    (is (= :no-tools (directory/availability (row "a" {:configurable? false})))
        "a connector with no OAuth client to belong to has no enabled tools here"))
  (testing "no OAuth client on this machine"
    (is (= :no-client (directory/availability (row "a" {:authable? false})))))
  (testing "the two unofferable reasons have different notes"
    ;; They send a person to different places — one is something an operator
    ;; turns on in this build, the other is something they configure for this
    ;; machine — so one shared note would be wrong for whichever it was not
    ;; written about.
    (is (not= (get directory/availability-notes :no-tools)
              (get directory/availability-notes :no-client)))
    (is (every? seq (vals directory/availability-notes)))
    (is (nil? (get directory/availability-notes :usable))
        "a usable row says its tool count, not a reason it is not usable"))
  (testing "precedence: no tools is reported before no client"
    ;; Both true at once is the common case for a connector this build does not
    ;; enable. Reporting the client first would send somebody to configure
    ;; OAuth for a connector that would still do nothing afterwards.
    (is (= :no-tools (directory/availability
                      (row "a" {:enabled-tool-count 0 :authable? false}))))))

(deftest a-slot-row-carries-the-structure-and-not-the-selection
  (let [catalog [(row "com.google.calendar") (row "com.google.gmail")
                 (row "com.microsoft.graph")]
        rows (directory/rows catalog)
        by-id (into {} (map (juxt :id identity)) rows)]
    (testing "one row per job, in the declared order"
      (is (= ["calendar" "mail"] (mapv :id rows))
          "slots whose apps this build does not carry are dropped, not empty"))
    (testing "an app may fill more than one job"
      ;; Microsoft 365 is this deployment's mail AND its calendar. A table that
      ;; made it choose would be wrong about the other one.
      (is (contains? (set (map :id (:candidates (by-id "calendar"))))
                     "com.microsoft.graph"))
      (is (contains? (set (map :id (:candidates (by-id "mail"))))
                     "com.microsoft.graph")))
    (testing "nothing on the wire says what is selected"
      ;; A `:chosen` computed here would be right when the page loaded and
      ;; wrong from the first click.
      (let [wire (pr-str rows)]
        (is (not (str/includes? wire ":chosen")))
        (is (not (str/includes? wire ":picked")))))
    (testing "each candidate carries why it can or cannot be picked"
      (is (= #{"usable"} (set (map :availability (:candidates (by-id "mail")))))))))

(deftest an-unclassified-app-is-still-reachable
  ;; The fallback, exercised. Without this the gate below could be satisfied by
  ;; a `rows` that silently dropped anything it did not recognise, and the day
  ;; a connector arrived unclassified it would vanish from the picker instead
  ;; of appearing in その他.
  (let [catalog [(row "com.google.gmail") (row "com.example.newthing")]
        rows (directory/rows catalog)
        other (first (filter #(= "other" (:id %)) rows))]
    (is (some? other) "an unclassified connector produced the fallback row")
    (is (= ["com.example.newthing"] (mapv :id (:candidates other))))
    (is (= ["com.example.newthing"] (directory/unassigned catalog))))
  (testing "and the fallback row is absent when there is nothing in it"
    (is (nil? (first (filter #(= "other" (:id %))
                             (directory/rows [(row "com.google.gmail")])))))))

(deftest every-connector-this-build-carries-has-a-job
  ;; The gate. Against the real registry rather than a fixture: a fixture would
  ;; be a copy of the table asserting itself.
  (let [catalog (connectors/catalog-rows nil)]
    (is (seq catalog) "the registry is not empty — otherwise this passes vacuously")
    (is (= [] (directory/unassigned catalog))
        (str "connectors with no slot in `app-directory/slots`: "
             (pr-str (directory/unassigned catalog))
             ". They are still offered under その他; give them a job here."))))

(deftest the-table-itself-is-well-formed
  (testing "no slot is empty and every field is filled"
    (doseq [slot directory/slots]
      (is (seq (:slot/id slot)))
      (is (seq (:slot/title slot)))
      (is (seq (:slot/detail slot)))
      (is (seq (:slot/apps slot)) (str (:slot/id slot) " names no app"))))
  (testing "slot ids are unique, and none of them is the fallback's"
    (let [ids (mapv :slot/id directory/slots)]
      (is (= (count ids) (count (set ids))))
      (is (not (contains? (set ids) (:slot/id directory/other-slot))))))
  (testing "every named app is one this build carries"
    ;; The other direction of the gate above. A slot naming an app that does
    ;; not exist renders as an absent candidate — silently, because `rows`
    ;; keeps only what the catalog has.
    (let [carried (into #{} (map #(str (:id %))) (connectors/catalog-rows nil))
          named (directory/assigned-ids)]
      (is (= #{} (into #{} (remove carried) named))
          (str "slots name apps this build does not carry: "
               (pr-str (sort (remove carried named))))))))

(deftest the-summary-counts-the-deployment-and-not-the-person
  (let [catalog [(row "a") (row "b" {:enabled-tool-count 0})
                 (row "c" {:authable? false})]
        note (directory/summary catalog)]
    (is (str/includes? note "1 件はこのビルド"))
    (is (str/includes? note "1 件は OAuth"))
    (testing "and says nothing about how many apps somebody picked"
      ;; That is client state. One sentence assembled out of both halves would
      ;; put half of it in each place.
      (is (not (str/includes? note "追加します"))))
    (testing "a deployment with nothing wrong says nothing"
      (is (= "" (directory/summary [(row "a")]))))))

(defn- with-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-directory"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))
                    store/transact! (fn [f & args] (apply swap! store/state f args))]
        (f))
      (finally (reset! store/state previous)))))

(deftest the-screen-is-sent-both-renderings-of-one-catalog
  (with-store
    (fn []
      (let [payload (bots/overview nil {:user-id "alice" :organization-id "org-1"
                                        :kind :passkey})]
        (is (seq (:catalog payload)) "the flat grid")
        (is (seq (:app-slots payload)) "and the same set, by job")
        (testing "every grid row carries the decision both renderings read"
          ;; It used to be recomputed in the browser, inside the grid alone.
          (is (every? :availability (:catalog payload)))
          (is (every? #(contains? #{"usable" "no-tools" "no-client"} (:availability %))
                      (:catalog payload))))
        (testing "a row that cannot be picked carries its reason with it"
          (doseq [row (:catalog payload)
                  :when (not= "usable" (:availability row))]
            (is (seq (:availability-note row))
                (str (:id row) " is unofferable and does not say why"))))
        (testing "the slots name only apps the catalog has"
          (let [carried (into #{} (map #(str (:id %))) (:catalog payload))]
            (doseq [slot (:app-slots payload), c (:candidates slot)]
              (is (contains? carried (:id c))))))))))

(deftest the-screen-can-render-what-it-is-sent
  ;; The server can be right about all of the above and the person still sees
  ;; the old flat grid: a payload nothing reads changes nothing. Asserted
  ;; against the shipped sources, as this repository already asserts its other
  ;; interaction-layer invariants.
  (let [interaction (slurp (io/resource "cloud/itonami/app/interaction.js"))]
    (is (str/includes? interaction "const renderBotsSlots"))
    (is (str/includes? interaction "data['app-slots']")
        "the payload is read")
    (testing "both views are redrawn together"
      (is (str/includes? interaction
                         "const renderBotsPicks = () => { renderBotsSlots(); renderBotsServiceGrid(); };")))
    (testing "and the grid no longer decides availability for itself"
      ;; The exact expressions that used to live here. Their absence is the
      ;; de-duplication; without this assertion a later edit could put the
      ;; second copy back and nothing would say so.
      (is (not (str/includes? interaction "const hasTools = service")))
      (is (not (str/includes? interaction "service['authable?'] !== false")))
      (is (str/includes? interaction "service.availability === 'usable'"))))
  (testing "and there is a place on the page to render it into"
    (is (str/includes? web/app-css ".bots-slot__select"))
    (is (str/includes? web/app-css ".bots-slot__chip"))
    (let [html (web/page-html {:brand {:name "Test"}})]
      (is (str/includes? html "id=\"bots-slots\""))
      (is (str/includes? html "id=\"bots-slot-note\""))
      (is (str/includes? html "id=\"bots-service-grid\"")
          "the grid is still there: the slot rows are a second view, not a replacement"))))
