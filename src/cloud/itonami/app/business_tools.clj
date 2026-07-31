(ns cloud.itonami.app.business-tools
  "The 事業 surface, over MCP, as a client of the running server.

  `business` (the entity) and `server` (the routes) already own the descriptors'
  behaviour. This restates neither: every tool here is one HTTP call to a route
  that exists, so a refusal the route makes is the refusal an agent sees, with
  the same wording.

  ## Why HTTP and not the store

  `store/state` is read once per process. An MCP server calling `business/bind!`
  in-process would write onto a snapshot taken when IT started, and the resident
  app's next `transact!` would drop it. The CLI met this first and went the same
  way; see `app-client`.

  ## Why these three and not more

  `business/portfolio`, `create!` and `bind!` are the whole entity. The Canvas
  and Loops panes read projections and run a simulator — worth exposing, and a
  different shape (they take a business and return an analysis, not a record),
  so they are not folded in here on the assumption that they are the same kind
  of thing.

  ## What an agent may do here

  All of it. The business entity writes only to the app's own `:businesses`
  partition — `business/binds-only-locally?` is the test that holds that line —
  so nothing here reaches an analysis plane, a governed ledger, or money. That
  is why this surface takes an agent session while `payment-tools` does not
  (ADR-0009)."
  (:require [clojure.string :as str]
            [cloud.itonami.app.app-client :as client]))

(def tools
  [{:name "business_list"
    :description
    (str "Every 事業 (business) in this organization with the state of its six "
         "join faces — canvas, XMILE model, leverage ledger, adopted "
         "blueprints, repos, legal entity. A face is unbound (no key), "
         "unresolvable (a key but no workspace checkout), missing, unreadable "
         "or resolved; an absent plane is never reported as an empty one. Also "
         "returns adoptions bound to no business.")
    :parameters {:type "object" :properties {}}}

   {:name "business_create"
    :description
    (str "Create a 事業. Nothing is derived: which repo or canvas belongs to "
         "which business is a judgement, so this records a name and returns an "
         "id to bind faces onto. The slug is unique within the organization and "
         "is lower-cased rather than refused.")
    :parameters {:type "object"
                 :required ["slug"]
                 :properties
                 {:slug {:type "string"
                         :description "3-64 chars, lower-case alphanumeric with . _ -"}
                  :name {:type "string" :description "Display name. Defaults to the slug."}
                  :note {:type "string" :description "Free text."}}}}

   {:name "business_bind"
    :description
    (str "Set or clear a 事業's join keys. Only the keys you pass are touched — "
         "an explicitly empty value clears one, and omitting a key leaves it "
         "alone. Binding is NOT validated against the workspace on purpose: "
         "recording that a business belongs to a canvas this checkout does not "
         "have is a true statement about the business and shows as a missing "
         "face.")
    :parameters {:type "object"
                 :required ["id"]
                 :properties
                 {:id {:type "string" :description "business id from business_create or business_list."}
                  :repos {:type "array" :items {:type "string"}
                          :description "Repo paths, e.g. orgs/cloud-itonami/cloud-itonami-isic-6499."}
                  :adoptions {:type "array" :items {:type "string"}
                              :description "Adopted blueprint repo names."}
                  :canvas {:type "string" :description "A :canvas/product, e.g. cloud-itonami."}
                  :model {:type "string" :description "Path to an .xmile file, relative to the workspace root."}
                  :leverage {:type "string" :description "Path to a leverage ledger, relative to the workspace root."}
                  :lei {:type "string" :description "Legal Entity Identifier."}}}}])

(def ^:private tool-names (into #{} (map :name) tools))

(defn tool? [tool-name] (contains? tool-names tool-name))

(defn available?
  "Published only when a session actually resolves against the running server.

  A tool that is certain to refuse is a worse contract than an absent one: it
  invites a client to try and says nothing about why. Same posture as
  `payment-tools/available?`."
  [configuration]
  (client/available? configuration))

(defn- as-list [v]
  (cond
    (nil? v) nil
    (sequential? v) (vec v)
    (string? v) (->> (str/split v #",") (map str/trim) (remove str/blank?) vec)
    :else [v]))

(defn call-tool
  "Run one business tool. Throws `ex-info`; `mcp/invoke` turns that into an
  error result carrying the server's own type and message."
  [configuration tool-name {:keys [id slug name note repos adoptions canvas
                                   model leverage lei] :as arguments}]
  (case tool-name
    "business_list" (client/request! configuration :get "/api/business")

    "business_create"
    (client/request! configuration :post "/api/business"
                     {:slug slug :name name :note note})

    "business_bind"
    (let [;; Only keys the caller passed. `bind!` reads a PRESENT key as an
          ;; instruction and an empty value as "clear this face", so sending the
          ;; whole shape every time would unbind whatever this call did not
          ;; mention.
          body (cond-> {}
                 (contains? arguments :repos) (assoc :repos (as-list repos))
                 (contains? arguments :adoptions) (assoc :adoptions (as-list adoptions))
                 (contains? arguments :canvas) (assoc :canvas canvas)
                 (contains? arguments :model) (assoc :model model)
                 (contains? arguments :leverage) (assoc :leverage leverage)
                 (contains? arguments :lei) (assoc :lei lei))]
      (when-not id
        (throw (ex-info "id が必要です" {:type :business-tools/missing-id})))
      (when (empty? body)
        (throw (ex-info (str "bind する面を 1 つ以上指定してください "
                             "(repos / adoptions / canvas / model / leverage / lei)")
                        {:type :business-tools/nothing-to-bind})))
      (client/request! configuration :post (str "/api/business/" id "/bind") body))

    (throw (ex-info (str "unknown business tool: " tool-name)
                    {:type :business-tools/unknown-tool}))))
