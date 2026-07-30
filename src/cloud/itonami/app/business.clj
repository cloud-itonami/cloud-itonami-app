(ns cloud.itonami.app.business
  "Business — 事業 as the unit the analysis planes can actually meet on.

  Three planes describe this workspace's businesses and none of them can join
  to another. BMC/Lean is keyed by `:canvas/product` (12 products). System
  dynamics is keyed by an entity `:id` in `loop-system-dynamics`. The fleet is
  keyed by `:repo/path` and `:company/lei` (repo-taxonomy 5,311 / cloud-itonami-lei
  185). Their grains disagree too: in the BMC plane `cloud-itonami` is ONE of
  twelve products, while in this app it is the whole portfolio of 1,213 actors.

  A `Business` is the entity that holds one key from each plane, so that
  「この事業の仮説と、それを検証する repo と、その repo の成熟度」 becomes one
  lookup instead of three incomparable ones. Per ADR-2607309600 the entity comes
  BEFORE the analysis views: without it every view invents its own notion of a
  business and the split is reproduced rather than closed.

  ## What this namespace will not do

  **It never writes to an analysis plane.** `canvas-ledger.edn` is a governed
  append-only event log, `repo-taxonomy.edn` is generated, and the ADR base
  datoms are marked 書き換え禁止. This namespace reads them and writes only its
  own `:businesses` partition in the local store. `binds-only-locally?` exists
  so a test can hold that line.

  **It never fabricates a face value.** A face is `:unbound` (no key),
  `:unresolvable` (a key, but no workspace checkout to resolve it against),
  `:missing` (resolvable and not found), `:unreadable` (found and would not
  parse) or `:resolved`. An absent plane is never rendered as an empty one —
  the same rule `funding` applies to an unknown balance and `fleet` applies to
  an unreachable probe.

  **It never treats the 1,213-entry directory as a list of businesses.** Only
  blueprints an operator actually declared (`cloud.itonami.app.operator`) can be
  bound, and the ones bound to no business are reported as a separate 未割当
  bucket rather than mixed in.

  ## The five faces

  ADR-2607309600 named four (canvas / repos / lei / model). `:adoptions` is a
  fifth, added here because `cloud.itonami.app.operator` shipped repo-keyed
  参与 records after that ADR's audit was taken: an adopted blueprint is a repo
  the operator declared they run, which is a stronger fact than a repo path that
  merely exists, and collapsing the two would lose it.

  ## Scope caveat this namespace inherits

  A business belongs to an organization, like a funding account. Operator
  adoptions do NOT: `operator/profile` is a single installation-wide record. So
  the 未割当 bucket is computed across the installation, not the organization,
  and says so in its own payload rather than implying a scoping that is not
  there."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.operator :as operator]
            [cloud.itonami.app.store :as store])
  (:import [java.util UUID]))

(def schema "cloud.itonami.app.business.v1")

(def face-order
  "Presentation order. Canvas first because a business with no canvas has no
  hypothesis, and everything after it is evidence about one."
  [:canvas :model :leverage :adoptions :repos :lei])

(def face-labels
  {:canvas "Canvas (BMC / Lean)"
   :model "Loops (system dynamics / XMILE)"
   :leverage "Leverage (Meadows band ranking)"
   :adoptions "参与している blueprint"
   :repos "Repos"
   :lei "法人実体 (LEI)"})

(def face-sources
  "Where each face is resolved from, relative to the workspace root. Shown in
  the UI so an unresolved face names the file it looked for."
  {:canvas "90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn"
   :model "(business が指定した XMILE モデルへの相対パス)"
   :leverage "(business が指定した leverage ledger への相対パス)"
   :adoptions "(このアプリの store — workspace checkout を要しません)"
   :repos "manifest/repo-taxonomy.edn"
   :lei "orgs/cloud-itonami/cloud-itonami-lei-<lei>/blueprint.edn"})

(def slug-pattern #"[a-z0-9][a-z0-9._-]{1,62}[a-z0-9]")

;; ---------------------------------------------------------------------------
;; store
;; ---------------------------------------------------------------------------

(defn- refuse [type detail] (throw (ex-info detail {:type type})))

(defn- require-organization! [session]
  ;; :identity/organization-required (409), not :identity/unauthenticated (401)
  ;; as `funding` uses for the same shape. There IS a session here; what is
  ;; missing is an organization, and answering 401 tells the client to log in
  ;; again, which fixes nothing. The fix is to set an Organization ID.
  (or (:organization-id session)
      (refuse :identity/organization-required
              "business には organization に属する session が必要です")))

(defn- business-path [id] [:businesses id])

(defn business
  "One business, only when it belongs to this session's organization. nil rather
  than a throw, so a caller can tell 'no such business' from 'refused'."
  [session id]
  (let [record (get-in (store/snapshot) (business-path id))]
    (when (and record (= (:organization-id session) (:organization-id record)))
      record)))

(defn businesses
  "Every business in this session's organization, oldest first."
  [session]
  (->> (vals (get-in (store/snapshot) [:businesses] {}))
       (filter #(= (:organization-id session) (:organization-id %)))
       (sort-by :business/created-at)
       vec))

;; ---------------------------------------------------------------------------
;; workspace — the checkout the analysis planes live in, if there is one
;; ---------------------------------------------------------------------------

(defn workspace
  "The superproject checkout this app may read the analysis planes out of.

  Absent by default, and absent in a released install: cloud-itonami-app ships
  on its own and cannot assume a west checkout beside it, which is exactly why
  `fleet` ships its catalog as a resource. Rather than guess a path, an
  unconfigured workspace makes every plane-backed face `:unresolvable` and names
  the setting — a face that reads 'not found' when nobody said where to look
  would be a measurement of nothing."
  [configuration]
  (let [configured (some-> (get-in configuration [:business :workspace-root])
                           str str/trim not-empty)
        file (some-> configured io/file)]
    (cond
      (nil? configured)
      {:configured? false :state :unset
       :detail (str "workspace checkout が未設定です。"
                    ":business :workspace-root に superproject の path を設定すると、"
                    "canvas / repo / 法人実体 の面を解決します")}

      (not (.isDirectory file))
      {:configured? true :state :missing :root configured
       :detail (str "設定された workspace checkout がありません: " configured)}

      :else
      {:configured? true :state :present :root (.getPath file) :file file})))

(def ^:private unreadable ::unreadable)

(defn- read-edn
  "The parsed form, `nil` when the file is absent, or `::unreadable`. A parse
  failure is reported rather than folded into absence: 'the plane is not there'
  and 'the plane is corrupt' need different fixes."
  [file]
  (when (and file (.isFile file))
    (try (edn/read-string (slurp file))
         (catch Exception _ unreadable))))

(defn- plane-file [ws relative]
  (some-> (:file ws) (io/file relative)))

(defn- canvas-products
  "Every `:canvas/product` in the BMC base datoms, as a set."
  [ws]
  (let [v (read-edn (plane-file ws (face-sources :canvas)))]
    (cond
      (nil? v) nil
      (identical? unreadable v) unreadable
      :else (into #{} (comp (filter map?) (keep :canvas/product)) v))))

(defn- taxonomy-index
  "`:repo/path` -> `:repo/kind`, from the generated repo taxonomy."
  [ws]
  (let [v (read-edn (plane-file ws (face-sources :repos)))]
    (cond
      (nil? v) nil
      (identical? unreadable v) unreadable
      :else (into {} (comp (filter map?)
                           (keep (fn [m] (when-let [p (:repo/path m)]
                                           [p (:repo/kind m)]))))
                  v))))

(defn- lei-record?
  "Whether the workspace carries the legal-entity blueprint for this LEI.

  Only presence. Joining to the financial (`market-intel`) and ToS datasets on
  `:company/lei` is a later phase; reporting a revenue figure here would mean
  reading 5,200 entities to answer a question this view does not ask."
  [ws lei]
  (when-let [root (:file ws)]
    (.isFile (io/file root (str "orgs/cloud-itonami/cloud-itonami-lei-"
                                (str/lower-case lei) "/blueprint.edn")))))

;; ---------------------------------------------------------------------------
;; face resolution
;; ---------------------------------------------------------------------------

(defn- unresolvable [face key* ws]
  {:face face :label (face-labels face) :source (face-sources face)
   :key key* :bound? true :state :unresolvable :detail (:detail ws)})

(defn- scalar-face
  "A face bound to one key, resolved by `found?` against an already-read plane."
  [face key* ws plane found?]
  (cond
    (nil? key*) {:face face :label (face-labels face) :source (face-sources face)
                 :key nil :bound? false :state :unbound
                 :detail "未紐付け"}
    (not= :present (:state ws)) (unresolvable face key* ws)
    (identical? unreadable plane)
    {:face face :label (face-labels face) :source (face-sources face)
     :key key* :bound? true :state :unreadable
     :detail (str (face-sources face) " を読めませんでした")}
    (nil? plane)
    {:face face :label (face-labels face) :source (face-sources face)
     :key key* :bound? true :state :missing
     :detail (str (face-sources face) " が workspace にありません")}
    (found? plane key*)
    {:face face :label (face-labels face) :source (face-sources face)
     :key key* :bound? true :state :resolved :detail "解決しました"}
    :else
    {:face face :label (face-labels face) :source (face-sources face)
     :key key* :bound? true :state :missing
     :detail (str key* " が " (face-sources face) " に見つかりません")}))

(defn- collection-face
  "A face bound to many keys. `:partial` when some resolve and some do not —
  reporting the whole face as resolved because one entry was found is how a
  half-bound business starts reading as a complete one."
  [face keys* resolve-one]
  (if (empty? keys*)
    {:face face :label (face-labels face) :source (face-sources face)
     :key [] :bound? false :state :unbound :detail "未紐付け"}
    (let [results (mapv (fn [k] (assoc (resolve-one k) :key k)) keys*)
          states (set (map :state results))
          state (cond
                  (= #{:resolved} states) :resolved
                  (contains? states :resolved) :partial
                  (= 1 (count states)) (first states)
                  :else :partial)
          n (count (filter #(= :resolved (:state %)) results))]
      {:face face :label (face-labels face) :source (face-sources face)
       :key keys* :bound? true :state state
       :detail (str n "/" (count keys*) " 件が解決")
       :items results})))

(defn- path-face
  "A face bound to a path under the workspace root, resolved by existence."
  [face key* ws]
  (cond
    (nil? key*) {:face face :label (face-labels face) :source (face-sources face)
                 :key nil :bound? false :state :unbound :detail "未紐付け"}
    (not= :present (:state ws)) (unresolvable face key* ws)
    (.isFile (io/file (:file ws) key*))
    {:face face :label (face-labels face) :source key*
     :key key* :bound? true :state :resolved
     :detail (str "ファイルを確認: " key*)}
    :else
    {:face face :label (face-labels face) :source key*
     :key key* :bound? true :state :missing
     :detail (str key* " が workspace にありません")}))

(defn- model-file-face [key* ws] (path-face :model key* ws))

(defn- adoption-face [repos]
  (collection-face
   :adoptions repos
   (fn [repo]
     (let [a (operator/adoption repo)]
       (cond
         (nil? a) {:state :missing :detail "参与が表明されていません"}
         (= :withdrawn (:adoption/stage a))
         {:state :missing :detail "参与は取り下げられています"}
         :else {:state :resolved
                :detail (str "stage " (name (:adoption/stage a)))
                :stage (:adoption/stage a)
                :endpoint (:adoption/endpoint a)})))))

(defn resolve-faces
  "Every face of `b`, with the state of each. `planes` carries one delay per
  plane file so a portfolio of N businesses reads each file once, not N times."
  [b {:keys [canvas taxonomy]} ws]
  [(scalar-face :canvas (:business/canvas b) ws (force canvas) #(contains? %1 %2))
   (model-file-face (:business/model b) ws)
   ;; Same shape as :model — a path under the workspace root, resolved by
   ;; existence. A separate binding rather than a path guessed next to the model:
   ;; a leverage ranking and a stock-flow model are different artifacts, and
   ;; inferring one's location from the other's is the kind of guess `fleet`
   ;; refuses when it will not invent an endpoint.
   (path-face :leverage (:business/leverage b) ws)
   (adoption-face (vec (:business/adoptions b)))
   (collection-face :repos (vec (:business/repos b))
                    (fn [path]
                      (let [idx (force taxonomy)]
                        (cond
                          (not= :present (:state ws))
                          {:state :unresolvable :detail (:detail ws)}
                          (identical? unreadable idx)
                          {:state :unreadable :detail "repo-taxonomy を読めませんでした"}
                          (nil? idx)
                          {:state :missing :detail "repo-taxonomy が workspace にありません"}
                          (contains? idx path)
                          {:state :resolved :detail (str "kind " (get idx path))
                           :kind (get idx path)}
                          :else {:state :missing :detail "repo-taxonomy にありません"}))))
   (scalar-face :lei (:business/lei b) ws ws (fn [w lei] (boolean (lei-record? w lei))))])

;; ---------------------------------------------------------------------------
;; portfolio (read-only)
;; ---------------------------------------------------------------------------

(defn- coverage
  "How much of this business is actually observed. Deliberately two numbers and
  not one score: 3/5 bound says what the owner has declared, 2/3 resolved says
  what the workspace can confirm, and averaging them into a single percentage
  would hide which of the two is missing."
  [faces]
  (let [bound (filter :bound? faces)]
    {:faces (count faces)
     :bound (count bound)
     :resolved (count (filter #(= :resolved (:state %)) bound))
     :unresolvable (count (filter #(= :unresolvable (:state %)) bound))}))

(defn portfolio
  "Every business in this session's organization, each with the state of its
  five faces, plus the adoptions bound to no business.

  Read-only with respect to every analysis plane. The only writes this namespace
  performs are `create!` and `bind!`, on its own partition."
  [configuration session]
  (let [organization-id (require-organization! session)
        ws (workspace configuration)
        planes {:canvas (delay (canvas-products ws))
                :taxonomy (delay (taxonomy-index ws))}
        records (businesses session)
        rows (mapv (fn [b]
                     (let [fs (resolve-faces b planes ws)]
                       (assoc b :faces fs :coverage (coverage fs))))
                   records)
        bound (into #{} (mapcat :business/adoptions) records)
        live (remove #(= :withdrawn (:adoption/stage %)) (operator/adoptions))
        unassigned (filterv #(not (contains? bound (:adoption/repo %))) live)]
    {:schema schema
     :organization-id organization-id
     :workspace ws
     :businesses rows
     :unassigned
     {:count (count unassigned)
      ;; Named, not silently org-scoped: operator adoptions are installation-wide.
      :scope :installation
      :caveat (str "参与記録は organization ではなくインストール単位です。"
                   "この未割当は組織を跨いで数えています")
      :adoptions (mapv #(select-keys % [:adoption/repo :adoption/stage
                                        :adoption/declared-by :adoption/declared-on])
                       unassigned)}
     :counts {:businesses (count rows)
              :unassigned-adoptions (count unassigned)
              :fully-resolved (count (filter #(= (:faces (:coverage %))
                                                 (:resolved (:coverage %)))
                                             rows))}}))

(defn counts
  "The nav badge. Businesses only — an installation with no business shows 0
  rather than borrowing the 1,213-entry directory's size."
  [session]
  {:businesses (count (businesses session))})

;; ---------------------------------------------------------------------------
;; writes (this namespace's own partition, nothing else)
;; ---------------------------------------------------------------------------

(defn create!
  "Create a business in this session's organization.

  `slug` is how the business is named in prose, ADRs and commit messages, so it
  is required and unique within the organization. Case is canonicalised down
  rather than refused: `Cloud-Itonami` and `cloud-itonami` naming two businesses
  that read as one is a worse outcome than a slug that came back lowercased.

  Nothing else is derived: which repo or canvas belongs to which business is a
  judgement, and a constructor that guessed one from a name prefix would be
  inventing the very binding this entity exists to record."
  [session {:keys [slug name note]}]
  (let [organization-id (require-organization! session)
        slug (some-> slug str str/trim str/lower-case not-empty)]
    (when-not slug
      (refuse :business/slug-missing "business の slug が必要です"))
    (when-not (re-matches slug-pattern slug)
      (refuse :business/slug-invalid
              "slug は小文字英数と . _ - で、3〜64 文字です"))
    (when (some #(= slug (:business/slug %)) (businesses session))
      (refuse :business/slug-taken (str "slug は既に使われています: " slug)))
    (let [id (str "business-" (UUID/randomUUID))
          record {:schema schema
                  :business/id id
                  :business/slug slug
                  :business/name (or (some-> name str str/trim not-empty) slug)
                  :business/note (some-> note str str/trim not-empty)
                  :organization-id organization-id
                  :business/created-by (:user-id session)
                  :business/created-at (store/now)
                  :business/adoptions []
                  :business/repos []}]
      (store/transact! assoc-in (business-path id) record)
      record)))

(defn- normalize-list [v]
  (->> (cond (nil? v) [] (sequential? v) v :else [v])
       (map #(some-> % str str/trim not-empty))
       (remove nil?)
       distinct
       vec))

(defn bind!
  "Set or clear this business's join keys.

  Only keys present in `bindings` are touched; an explicit nil or empty value
  clears one. Binding is not validated against the workspace on purpose —
  recording that a business belongs to a canvas the checkout does not have is
  a true statement about the business and a `:missing` face, and refusing it
  would make the entity unusable in exactly the released install that has no
  checkout."
  [session id bindings]
  (require-organization! session)
  (let [record (business session id)]
    (when-not record
      (refuse :business/not-found "この organization に該当する business がありません"))
    (let [updates
          (cond-> {}
            (contains? bindings :canvas)
            (assoc :business/canvas (some-> (:canvas bindings) str str/trim
                                            not-empty
                                            (str/replace #"^:" "") keyword))
            (contains? bindings :lei)
            (assoc :business/lei (some-> (:lei bindings) str str/trim not-empty
                                         str/upper-case))
            (contains? bindings :model)
            (assoc :business/model (some-> (:model bindings) str str/trim not-empty))
            (contains? bindings :leverage)
            (assoc :business/leverage (some-> (:leverage bindings) str str/trim
                                              not-empty))
            (contains? bindings :adoptions)
            (assoc :business/adoptions (normalize-list (:adoptions bindings)))
            (contains? bindings :repos)
            (assoc :business/repos (normalize-list (:repos bindings))))
          next-record (-> (merge record updates)
                          (assoc :business/updated-at (store/now)
                                 :business/updated-by (:user-id session)))]
      (store/transact! assoc-in (business-path id) next-record)
      next-record)))

(defn binds-only-locally?
  "Every store path this namespace writes. A test asserts the set, so adding a
  write to an analysis plane has to change this vector and be noticed."
  []
  [[:businesses]])
