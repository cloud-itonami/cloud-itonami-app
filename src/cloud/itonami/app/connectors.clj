(ns cloud.itonami.app.connectors
  "External services, as a registry rather than a literal.

  `identity/provider-catalog` used to be a map written into this application:
  three providers, each with one scope list. Adding a fourth meant editing a
  2,174-line namespace, and the single scope list meant a deployment that only
  wanted to search a mailbox also held permission to file and relabel it —
  `gmail.modify` covers reading, so one entry could not say less.

  The catalogue is now derived from `connector.registry` (ADR-2608097000).
  Each connector repository declares its own tools, and each tool declares the
  scopes IT needs, so what is requested follows what is enabled. Adding an
  integration is adding a dependency.

  ## The property that makes this safe to land

  Deriving a wider grant than the application previously requested would, on
  the next reconnect, ask people to approve access nobody decided to want. So
  the default enabled set is computed, not chosen: **a tool is on by default
  only if every scope it needs was already inside this application's
  pre-registry grant.** `historical-grant` below records that grant for no
  other purpose, `scope-implications` records the three places where one scope
  already contains another, and `connectors-test` asserts the resulting scope
  set is a subset. Wiring the registry in cannot widen anybody's grant.

  Everything outside that boundary — Drive file *contents*, calendar writes,
  the whole of GitHub's `repo`, and Slack/Notion/Google Chat, which this
  application never reached at all — is present in the registry and off until
  an operator turns it on. That is the point: the narrowing is now expressible,
  and so is the widening, and both are visible."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [connector.consent :as consent]
            [connector.model :as cm]
            [connector.provider :as cp]
            [connector.registry :as creg]
            [github.connector :as github]
            [google-calendar.connector :as calendar]
            [google-chat.connector :as gchat]
            [google-drive.connector :as drive]
            [google-gmail.connector :as gmail]
            [microsoft-graph.connector :as graph]
            [notion.connector :as notion]
            [slack.connector :as slack]))

(def all
  "Every connector this build carries. The set is a dependency list, not a
  decision: what a deployment actually offers is `enabled`."
  (creg/registry [calendar/provider drive/provider gmail/provider
                  graph/provider github/provider slack/provider
                  notion/provider gchat/provider]))

;; ── the pre-registry grant ──────────────────────────────────────────────
;;
;; Copied from `identity/provider-catalog` as it stood before this namespace
;; existed. It is recorded ONLY to bound the default enabled set, and it is
;; deliberately not the thing consent is computed from — that is the registry.
;; When every deployment has been reconnected under registry-computed scopes
;; this can go, and the default becomes an ordinary configuration value.

(def historical-grant
  {:google #{"openid" "email" "profile"
             "https://www.googleapis.com/auth/gmail.modify"
             "https://www.googleapis.com/auth/gmail.send"
             "https://www.googleapis.com/auth/drive.metadata.readonly"
             "https://www.googleapis.com/auth/calendar.readonly"}
   :github #{"read:user" "user:email" "read:org" "read:project"}
   :microsoft #{"openid" "email" "profile" "offline_access"
                "User.Read" "Mail.ReadWrite" "Mail.Send"
                "Files.Read" "Calendars.ReadBasic"}})

(def scope-implications
  "Scopes already contained in a scope the application held.

  Three, each verified against the provider's own documentation rather than
  assumed from the name: `gmail.modify` covers reading (which is exactly why
  one entry could not ask for less), and Microsoft's `Mail.ReadWrite` covers
  `Mail.Read`. `Calendars.Read` is NOT here — it is wider than the
  `Calendars.ReadBasic` this application held, so Microsoft's calendar tools
  are off by default even though Google's are on."
  {"https://www.googleapis.com/auth/gmail.readonly"
   "https://www.googleapis.com/auth/gmail.modify"
   "Mail.Read" "Mail.ReadWrite"
   "Mail.ReadBasic" "Mail.ReadWrite"})

(def client-overlay
  "What a descriptor cannot know: this application's own naming.

  The keys are client-id env vars because that is what identifies an OAuth
  CLIENT, and a client is what a grant belongs to — Drive, Gmail and Calendar
  are three connectors sharing one Google client and therefore one catalogue
  entry, which is the same grouping `connector.consent` performs.

  `:extra-scopes` is for scopes the APPLICATION needs and no tool does:
  `profile` is read by the userinfo call that fills in a display name, so no
  connector declares it and this application must."
  {"GOOGLE_CLIENT_ID" {:provider :google
                       :name "Google Workspace"
                       :credential-service "gftd.google"
                       :extra-scopes ["profile"]}
   "GITHUB_CLIENT_ID" {:provider :github
                       :name "GitHub"
                       :credential-service "gftd.github"}
   "M365_CLIENT_ID" {:provider :microsoft
                     :name "Microsoft 365"
                     :credential-service "gftd.m365"
                     :extra-scopes ["profile"]}
   "SLACK_CLIENT_ID" {:provider :slack
                      :name "Slack"
                      :credential-service "gftd.slack"}
   "NOTION_CLIENT_ID" {:provider :notion
                       :name "Notion"
                       :credential-service "gftd.notion"}})

(defn- provider-key [descriptor]
  (get-in client-overlay
          [(get-in descriptor [:connector/auth :connector.auth/client-id-env])
           :provider]))

(defn- covered?
  "Whether `scope` was inside `granted`, directly or by implication."
  [granted scope]
  (boolean (or (contains? granted scope)
               (when-let [implier (get scope-implications scope)]
                 (contains? granted implier)))))

(defn default-enabled-tools
  "The tools a fresh deployment offers: exactly those whose scopes this
  application already held.

  Computed rather than listed, so it cannot drift from `historical-grant`, and
  so a connector added later starts off rather than silently widening a grant
  nobody re-approved."
  ([] (default-enabled-tools all))
  ([registry]
   (into #{}
         (for [pr (creg/providers registry)
               :let [d (cp/descriptor pr)
                     granted (get historical-grant (provider-key d))]
               :when granted
               t (cm/tools d)
               :when (every? #(covered? granted %) (:connector/scopes t))]
           (:connector/name t)))))

(defn enabled
  "The registry a deployment actually offers.

  `configuration` may name connector ids and tool names under
  `[:connectors :enabled]`; absent, the computed default applies. An explicit
  empty list is respected — a deployment that wants no external services says
  so and gets none.

  `:tools` REPLACES the computed default; `:also` ADDS to it. Both are needed,
  and the distinction is the point of this namespace restated at the
  configuration layer. An operator who wants one widener — say Microsoft's
  calendar — would otherwise have to paste the whole default set beside it,
  and that literal is exactly what `default-enabled-tools` exists to avoid: it
  is computed from `historical-grant`, so a connector added to a later build
  arrives already on for the tools that grant covered. A pasted list freezes
  the set at the day it was pasted, and nothing reports that it has frozen.
  With `:also` the default stays computed and the deployment names only its
  own departure from it — which is what `widened-scopes` then reports.

  `:also` is honoured against an explicitly empty `:tools` too: naming a tool
  is a decision, and an empty list is a decision about the default, not a veto
  of a later line in the same map."
  ([] (enabled nil))
  ([configuration]
   (let [tools (get-in configuration [:connectors :enabled :tools] ::default)
         tools (if (= ::default tools) (default-enabled-tools) (set tools))
         tools (into tools (get-in configuration [:connectors :enabled :also]))
         ids (or (some-> (get-in configuration [:connectors :enabled :connectors]) set)
                 (into #{} (map cp/id) (creg/providers all)))]
     (creg/select all ids tools))))

;; ── the identity-shaped catalogue ───────────────────────────────────────

(defn- catalogue-entry [group]
  (let [env (:connector.consent/client-id-env group)
        overlay (get client-overlay env)
        auth (:connector.consent/auth group)]
    (when overlay
      [(:provider overlay)
       (cond-> {:name (:name overlay)
                :credential-service (:credential-service overlay)
                :client-id-env env
                :client-secret-env (:connector.auth/client-secret-env auth)
                :authorization-endpoint (:connector.auth/authorization-endpoint auth)
                :token-endpoint (:connector.auth/token-endpoint auth)
                :scopes (vec (sort (into (set (:extra-scopes overlay))
                                         (:connector.consent/scopes group))))}
         (:connector.auth/profile-endpoint auth)
         (assoc :profile-endpoint (:connector.auth/profile-endpoint auth))

         (seq (:connector.auth/extra auth))
         (assoc :authorization-extra (:connector.auth/extra auth)))])))

(defn provider-catalog
  "`identity/provider-catalog`, derived.

  One entry per OAuth client, with the scopes the ENABLED tools need. Same
  shape as the literal it replaces, so every caller — the connect flow, the
  refresh path, the Settings list — is untouched.

  A connector whose client id env is not in `client-overlay` is not in this
  catalogue and cannot be connected from Settings. That is deliberate: the
  keychain service name is this application's to choose, and inventing one for
  a provider nobody has configured would put an item in somebody's keychain
  that nothing reads."
  ([] (provider-catalog nil))
  ([configuration]
   (into {} (keep catalogue-entry) (consent/groups (enabled configuration)))))

(defn granted-scopes
  "The scopes `provider-key` would be asked for. Used by the test that proves
  the derivation never widens the pre-registry grant."
  ([provider] (granted-scopes nil provider))
  ([configuration provider]
   (set (:scopes (get (provider-catalog configuration) provider)))))

(defn grant-summary
  "What the Settings screen should say, per tool, for the enabled set."
  ([] (grant-summary nil))
  ([configuration] (consent/grant-summary (enabled configuration))))

(defn catalog-rows
  "A directory listing of every connector this build carries, with whether it
  is on. Shown so an operator can see what could be turned on — a registry
  nobody can see the rest of is a literal with extra steps."
  ([] (catalog-rows nil))
  ([configuration]
   (let [on (set (creg/tool-names (enabled configuration)))]
     (mapv (fn [d]
             {:id (:connector/id d)
              :name (:connector/name d)
              :summary (:connector/summary d)
              :configurable? (some? (provider-key d))
              ;; The OAuth client this connector is authorized under. Drive,
              ;; Gmail and Calendar share one, so a surface that asks per
              ;; connector asks three times for one consent — which is what a
              ;; Bot needs to know before it offers to connect anything.
              :provider (provider-key d)
              :tools (mapv (fn [t]
                             {:name (:connector/name t)
                              :effect (:connector/effect t)
                              :enabled? (contains? on (:connector/name t))
                              :description (:connector/description t)
                              :scopes (vec (:connector/scopes t))})
                           (cm/tools d))})
           (creg/descriptors all)))))

(defn widened-scopes
  "Scopes the current configuration would request that the pre-registry grant
  did not contain. Empty under the default; non-empty is not an error, it is an
  operator having turned something on, and this is what tells them which
  approval that will surface as."
  ([] (widened-scopes nil))
  ([configuration]
   (into {}
         (keep (fn [[provider granted]]
                 (let [asked (granted-scopes configuration provider)
                       extra (remove #(covered? granted %) asked)]
                   (when (seq extra) [provider (vec (sort extra))]))))
         historical-grant)))

(defn describe
  "One line per provider for a startup log: which connectors, how many tools,
  and whether anything is wider than it used to be."
  ([] (describe nil))
  ([configuration]
   (let [reg (enabled configuration)
         widened (widened-scopes configuration)]
     (str "connectors: " (count (creg/providers reg)) " enabled, "
          (count (creg/tool-names reg)) " tools"
          (if (seq widened)
            (str "; WIDER than the pre-registry grant: "
                 (str/join ", " (for [[p s] widened] (str (name p) " +" (count s)))))
            "; no scope wider than the pre-registry grant")))))

(defn unknown-provider-scopes
  "Providers in `historical-grant` that no enabled connector covers.

  Reported rather than assumed away: if a provider disappeared from the
  registry, its catalogue entry would vanish and Settings would quietly stop
  offering it."
  ([] (unknown-provider-scopes nil))
  ([configuration]
   (set/difference (set (keys historical-grant))
                   (set (keys (provider-catalog configuration))))))
