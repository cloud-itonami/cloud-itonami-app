(ns cloud.itonami.app.secret-request
  "Which credentials this application knows how to be handed, and what may be
  said about one.

  ## Why a Bot never receives a credential

  The AWAI Grok Bots clean-room states the property this namespace exists to
  keep: its capability sandbox has *no shell, filesystem, arbitrary evaluation,
  or secret forwarding* (`network-awai/local-murakumo`,
  `docs/grok-bots-clean-room.md`, ADR-2608300200). A bot there reaches an
  external service through a governed tool, never by holding the service's key.

  This application already agrees about tokens obtained by OAuth: the Bot picks
  a connection, `identity` resolves it out of the Keychain at the call site, and
  the token is never a value the model saw. What it had no answer for is the
  credential a person types — a Cloudflare API token has no authorization flow
  to run, so the only place it could arrive was an environment variable, and a
  desktop application started by a double-click has no exported environment.

  Measured 2026-09-09, that is exactly how it failed: a Bot asked for a
  Cloudflare token in prose, the person pasted it into the conversation, and
  the deploy still did not start — because a chat message is not an environment
  variable. The token was then in the transcript, which is the one place it
  must never be: the transcript is what the next turn sends to a model.

  ## What a request is

  A request names a credential; it never carries one. `catalogue` is the closed
  set of credentials this build can be handed, and it is closed **on purpose**:
  a Bot that could invent a request could ask for a password, and a field
  rendered by this application looks trustworthy no matter who asked for it.
  A card can only be offered for an entry that is written here, so the worst a
  prompt-injected model can do is ask for one of the credentials this build
  already knows it needs.

  Nothing in this namespace touches a value. `admit` says whether a value is
  the shape of the thing that was asked for and returns a keyword either way;
  it does not return, log, hash or echo the value it was given."
  (:require [kotoba.lang.text :as str]))

;; ── the closed set ───────────────────────────────────────────────────────

(def catalogue
  "The credentials a Bot may ask for, by id.

  Each entry says four things that a person needs in order to answer safely:
  what it is for (`:secret/purpose`), where it will be kept
  (`:secret/holder`), where to get one (`:secret/issue-url`), and what the
  application will do with it (`:secret/tools`).

  `:secret/environment` is the variable the same value can arrive in. It stays
  first in the resolution order — an operator who exports it is being explicit,
  and a stored item must not silently outrank that.

  `:secret/shape` is what a value of this kind looks like. It is used twice,
  and the second use is the reason it must be narrow rather than permissive:
  once to refuse a mistyped value at the moment it is offered, and once to
  refuse a message that carries the value into the transcript."
  {"cloudflare-api-token"
   {:secret/id "cloudflare-api-token"
    :secret/title "Cloudflare API トークン"
    :secret/purpose
    "Cloudflare の Domain / DNS を読み、Passkey で承認済みの提案だけを実行します。"
    :secret/holder "この端末の macOS キーチェーン"
    :secret/issue-url "https://dash.cloudflare.com/profile/api-tokens"
    :secret/environment "CLOUDFLARE_API_TOKEN"
    :secret/service "cloud-itonami-app.secret"
    :secret/account "cloudflare-api-token"
    ;; Cloudflare issues 40 characters of `[A-Za-z0-9_-]`. Narrow enough that a
    ;; sentence cannot match it, and deliberately not narrower: a 40-character
    ;; lowercase hex string — a git revision — matches too, so pasting a sha
    ;; while this card is open is refused as if it were the token. That is the
    ;; trade taken on purpose. A refused message is retyped; a credential in a
    ;; transcript is sent to a model.
    :secret/shape "[A-Za-z0-9_-]{40}"
    :secret/tools ["domain_search" "domain_check" "domain_registrations"
                   "domain_registration_status" "domain_dns_records"]}

   "cloudflare-account-id"
   {:secret/id "cloudflare-account-id"
    :secret/title "Cloudflare アカウント ID"
    ;; Not a credential. It is the other half of the same blockage: an account
    ;; id is a coordinate, it is public inside the dashboard, and holding it
    ;; grants nothing. It is in this catalogue because a desktop application
    ;; started by a double-click cannot be given an environment variable, and
    ;; that is true of a coordinate as much as of a key -- so the affordance is
    ;; the same one. What differs is `:secret/concealed?`, and everything that
    ;; reads from it: the field is not masked, and the transcript guard does not
    ;; refuse a message that contains one, because typing your own account id
    ;; into a conversation is not a mistake.
    :secret/concealed? false
    :secret/purpose
    "どの Cloudflare アカウントの Domain / DNS を読むかを決めます。"
    :secret/holder "この端末の macOS キーチェーン"
    :secret/issue-url "https://dash.cloudflare.com/"
    :secret/environment "CLOUDFLARE_ACCOUNT_ID"
    :secret/service "cloud-itonami-app.secret"
    :secret/account "cloudflare-account-id"
    :secret/shape "[0-9a-f]{32}"
    :secret/tools ["domain_search" "domain_check" "domain_registrations"]}

   "murakumo-service-token"
   {:secret/id "murakumo-service-token"
    :secret/title "Murakumo サービストークン"
    ;; Read by `bot-import/grok-token`. No card OFFERS it yet -- the import is
    ;; not a Bot tool, so there is no turn to stop -- and it is written here
    ;; rather than left as a bare `getenv` because the entry is what makes a
    ;; stored value reachable at all: a desktop app started by a double-click
    ;; has no exported environment, so the refusal that import raises was
    ;; correct and unanswerable. When an offer path is added it will name this
    ;; entry rather than invent one.
    :secret/purpose
    "AWAI Grok Bots の常駐ループ（/api/v1/grok-bots/bots）を読み、既存の Bot を取り込みます。"
    :secret/holder "この端末の macOS キーチェーン"
    :secret/issue-url "https://itonami.cloud/"
    :secret/environment "MURAKUMO_SERVICE_TOKEN"
    :secret/service "cloud-itonami-app.secret"
    :secret/account "murakumo-service-token"
    :secret/shape "[A-Za-z0-9_.\\-]{24,200}"
    :secret/tools []}})

(defn requirement
  "The catalogue entry for `id`, or nil.

  A lookup rather than a constructor: a request that is not in the catalogue is
  not a request this application makes, and there is no way to build one."
  [id]
  (get catalogue (some-> id str)))

(defn requirement!
  [id]
  (or (requirement id)
      (throw (ex-info "この application が扱える資格情報ではありません。"
                      {:type :secret/unknown-requirement :secret id}))))

(defn ids [] (into (sorted-set) (keys catalogue)))

(defn concealed?
  "Is this a credential, or a coordinate?

  Defaults to true, so an entry added without thinking about it is masked and
  guarded. The only way to get the weaker treatment is to have written
  `:secret/concealed? false` and said why."
  [requirement]
  (not (false? (:secret/concealed? requirement))))

;; ── what may be said about one ───────────────────────────────────────────

(defn public
  "A requirement as the client may see it.

  Every field here is a fact about the KIND of credential — none of them is a
  fact about a value, and there is no field a value could be put in. That is
  the property the card relies on: rendering this cannot leak, because nothing
  reachable from here has ever held the secret."
  [requirement]
  (when requirement
    {:id (:secret/id requirement)
     :title (:secret/title requirement)
     :concealed? (concealed? requirement)
     :purpose (:secret/purpose requirement)
     :holder (:secret/holder requirement)
     :issue-url (:secret/issue-url requirement)
     :environment (:secret/environment requirement)
     :tools (vec (:secret/tools requirement))}))

;; ── admission ────────────────────────────────────────────────────────────

(defn- shaped?
  "Does `value` match this requirement's shape, in full?

  Anchored by construction rather than by the pattern: `re-matches` requires
  the whole string, so a catalogue entry cannot weaken this by forgetting to
  write `^` and `$`, and a token surrounded by prose does not count as one."
  [requirement value]
  (boolean (and (string? value)
                (some-> (:secret/shape requirement)
                        re-pattern
                        (re-matches value)))))

(def max-value-length
  "Longer than any credential in the catalogue and short enough that a body
  cannot be smuggled in as one. Checked before the shape so that a megabyte of
  input is refused without being run through a regular expression."
  4096)

(defn admit
  "May this value be stored for this requirement?

  Returns `{:admitted? true}` or `{:admitted? false :reason <keyword>}` — never
  the value, never a prefix of it, never its length. A caller that wants to
  report what went wrong reports the reason."
  [requirement value]
  (cond
    (not (string? value)) {:admitted? false :reason :not-a-string}
    (str/blank? value) {:admitted? false :reason :empty}
    (not= value (str/trim value)) {:admitted? false :reason :surrounding-whitespace}
    (> (count value) max-value-length) {:admitted? false :reason :too-long}
    (not (shaped? requirement value)) {:admitted? false :reason :wrong-shape}
    :else {:admitted? true}))

(def refusals
  "What to tell the person, per reason.

  `:wrong-shape` says what was expected rather than what was received. Echoing
  the received value — even truncated — would put it in the one place this
  whole namespace exists to keep it out of."
  {:not-a-string "値が受け取れませんでした。もう一度入力してください。"
   :empty "値が空です。"
   :surrounding-whitespace
   "前後に空白か改行が入っています。貼り付けた範囲を見直してください。"
   :too-long "この資格情報にしては長すぎます。貼り付けた範囲を見直してください。"
   :wrong-shape
   "この資格情報の形と一致しません。別の値を貼っていないか確認してください。"})

(defn refusal [reason]
  (get refusals reason "この値は保存できませんでした。"))

;; ── the transcript guard ─────────────────────────────────────────────────

(defn carries-value?
  "Does this chat message consist of a value one of these requirements asked
  for?

  Asked of the whole trimmed message, so a sentence that merely mentions a
  token does not match and a bare pasted token does. Only ever consulted while
  a request is open — a shape is a weak signal and it is not used as a general
  scanner over the conversation.

  The consequence of a false positive is a refused message; the consequence of
  a false negative is a credential in the transcript. They are not the same
  size, and this errs in the direction of the refused message."
  [requirements text]
  (let [candidate (str/trim (str text))]
    (boolean (some #(and (concealed? %) (shaped? % candidate))
                   requirements))))
