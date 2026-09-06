(ns cloud.itonami.app.repo-profile
  "A profile a repository carries: `<repo root>/.itonami/profile.edn`.

  ADR-2609061200. Pure — it is handed the text of a file and returns either an
  accepted profile or a refusal that names why. Finding the file is the
  caller's effect; deciding what it means is here, so the decision can be
  asserted without a filesystem.

  ## A repo file is observed content

  Whoever can push to a repository can write this file. So it DESCRIBES and it
  never GRANTS, and that is enforced by a closed vocabulary rather than by
  review. `bot-allowances.edn` states the same floor for spending — \"no policy
  is not permission\" — and `bot-voice-profiles.edn` heads itself \"THIS FILE IS
  THE ASSIGNMENT POLICY, NOT THE AUTHORITY\". This is the third file in that
  family and it says it the same way.

  Three sets, and every key is in exactly one:

  | set | example | what happens |
  |---|---|---|
  | `describes` | `:bot/name` `:bot/brief` | crosses to the destination |
  | `prefers` | `:bot/model-preference` | crosses, effective only if the destination admits it |
  | everything else | `:bot/tools` `:bot/omakase?` `:bot/wallet-key` | **refused, by name** |

  ## The vocabulary is CLOSED, and that is the point

  An unknown key is refused rather than ignored. An open vocabulary would mean
  that the day a new grant key is added to `bot.cljc` — `:bot/spend-limit`,
  say — every repository that already carried it would start granting it, with
  nothing anywhere reporting the change. Closed costs a diff when the
  vocabulary genuinely grows; open costs a silent widening.

  ## Refused is not ignored

  A refused key is named in the result. Dropping it silently would leave the
  person who wrote it unable to tell a key that was applied from a key that was
  discarded — the same shape this workspace keeps finding, where a thing that
  did not happen looks like a thing that did."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def schema "cloud.itonami.app.repo-profile.v1")

(def file-name
  "The file, relative to the repository root.

  `.itonami/` is shared with the application: `bot-workspace/provision!`
  writes `.itonami/workspace.edn` into a managed workspace. The names are
  disjoint on purpose — the app never writes `profile.edn`, a repository never
  writes `workspace.edn` — so a repository that is ALSO a Bot's managed
  workspace does not have the two overwrite each other."
  ".itonami/profile.edn")

(def describes
  "Keys that cross to the destination as written."
  #{:profile/schema :profile/id
    :bot/name :bot/brief :bot/avatar :bot/context-refs
    :bot/tools-requested})

(def prefers
  "Keys that cross but only take effect if the destination admits them."
  #{:bot/model-preference :cli/layers :cli/config})

(def ^:private max-name 60)
(def ^:private max-brief 2000)
(def ^:private max-id 120)
(def ^:private max-bytes
  "The largest profile this will read. `repository_profile_ci` caps
  `storage-profile.edn` at 64 KiB for the same reason: a file read from a
  repository is a file someone else can make arbitrarily large."
  (* 64 1024))

(defn- refusal [reason message & [data]]
  (merge {:schema schema :accepted? false :reason reason :message message} data))

(defn- blank-string? [v] (or (not (string? v)) (str/blank? v)))

(defn- plain?
  "Whether `v` is built only from EDN's own scalars and collections.

  `:default` catches an UNKNOWN tag. It does not catch a BUILT-IN one:
  `clojure.edn/read` still applies `default-data-readers`, so `#inst` and
  `#uuid` parse into a Date and a UUID and never reach the default handler.
  Measured 2026-09-06 -- the first version of this namespace claimed \"tagged
  literals are denied outright\" and admitted both.

  So the value side is closed too, structurally: a tag that Clojure adds to
  `default-data-readers` tomorrow produces a type that is not on this list and
  is refused for that reason, without this file being edited."
  [v]
  (cond
    (or (nil? v) (string? v) (boolean? v) (number? v) (keyword? v) (symbol? v)) true
    (map? v) (and (every? plain? (keys v)) (every? plain? (vals v)))
    (coll? v) (every? plain? v)
    :else false))

(defn parse
  "Read `text` as EDN, or refuse.

  Tagged literals are denied: unknown ones by `:default`, built-in ones by
  `plain?` on the value that comes back. `repository-qualification/
  read-profile-inventory!` denies the first kind for the reason that applies to
  both -- a reader tag in a file from a repository is type selection by whoever
  wrote the file."
  [text]
  (cond
    (nil? text) (refusal :repo-profile/absent "profile がありません。")

    (> (count (str text)) max-bytes)
    (refusal :repo-profile/too-large
             (str file-name " が大きすぎます（上限 " max-bytes " bytes）。"))

    :else
    (try
      (let [v (edn/read-string
               {:readers {}
                :default (fn [tag _]
                           (throw (ex-info "tagged" {::tag tag})))}
               (str text))]
        (cond
          (not (map? v))
          (refusal :repo-profile/not-a-map
                   (str file-name " の中身が map ではありません。"))

          (not (plain? v))
          (refusal :repo-profile/tagged-literal
                   (str file-name " に tagged literal（`#inst` `#uuid` など）が"
                        "あります。値は EDN のスカラーとコレクションだけにしてください。"))

          :else v))
      (catch #?(:clj Exception :cljs :default) e
        (if (::tag (ex-data e))
          (refusal :repo-profile/tagged-literal
                   (str file-name " に tagged literal があります: #"
                        (::tag (ex-data e))))
          (refusal :repo-profile/unreadable
                   (str file-name " を読めませんでした: "
                        #?(:clj (.getMessage ^Exception e) :cljs (ex-message e)))))))))

(defn- validate-values [m]
  (cond
    (not= schema (:profile/schema m))
    (refusal :repo-profile/schema-mismatch
             (str ":profile/schema は " (pr-str schema) " である必要があります（"
                  (pr-str (:profile/schema m)) " でした）。"))

    (blank-string? (:profile/id m))
    (refusal :repo-profile/id-missing ":profile/id が必要です。")

    (> (count (:profile/id m)) max-id)
    (refusal :repo-profile/id-too-long
             (str ":profile/id が長すぎます（上限 " max-id "）。"))

    (and (contains? m :bot/name) (blank-string? (:bot/name m)))
    (refusal :repo-profile/name-blank ":bot/name が空です。")

    (and (:bot/name m) (> (count (:bot/name m)) max-name))
    (refusal :repo-profile/name-too-long
             (str ":bot/name が長すぎます（上限 " max-name "）。"))

    (and (:bot/brief m) (> (count (str (:bot/brief m))) max-brief))
    (refusal :repo-profile/brief-too-long
             (str ":bot/brief が長すぎます（上限 " max-brief "）。"))

    :else nil))

(defn admit
  "Decide what `text` means.

  Returns `{:accepted? true :describes … :prefers …}` or a refusal. A refusal
  always carries `:reason` as a namespaced keyword, and callers pin that
  literal rather than the message — the message is for a person and may be
  reworded."
  [text]
  (let [parsed (parse text)]
    (if (false? (:accepted? parsed))
      parsed
      (let [m parsed
            known (into describes prefers)
            refused (vec (sort (remove known (keys m))))]
        (cond
          (seq refused)
          (refusal :repo-profile/key-refused
                   (str file-name " に、repository が書いてはならない key があります: "
                        (str/join " " (map pr-str refused))
                        "。この file は記述であって授権ではありません。")
                   {:refused-keys refused})

          :else
          (or (validate-values m)
              {:schema schema
               :accepted? true
               :profile/id (:profile/id m)
               :describes (select-keys m describes)
               :prefers (select-keys m prefers)}))))))
