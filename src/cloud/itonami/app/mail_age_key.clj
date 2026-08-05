(ns cloud.itonami.app.mail-age-key
  "Where the age key that seals filed mail bodies comes from.

  ADR-0021 made a filed message's body age ciphertext in the project's
  git-annex. That left one question open, and it is the one that decides whether
  the feature is usable: where does the key live? An environment variable is not
  an answer for a desktop app — it is an answer for whoever remembers to export
  it, and this app is started by a double-click as often as by a shell.

  ## Four places, in this order

  | Source | Why it is where it is |
  |---|---|
  | `CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS` | an explicit override wins, always |
  | `…_RECIPIENTS_FILE` | the same, for a deployment that ships a file |
  | macOS Keychain | non-interactive and always available to a GUI app |
  | kagi | the self-sovereign vault; the item of record |

  kagi is the **item of record** and the Keychain is its mirror, which is the
  same shape this workspace already uses for B2 and Resend credentials. The
  order runs the other way — Keychain first — because it answers without
  unlocking a vault, and a resolution that can block is the wrong thing to put
  in the path of filing a message.

  ## What is stored, and what this reads

  Two values with very different consequences:

  - the **recipient** (`age1…`) is a public key. It is what this namespace needs,
    because encrypting requires only the recipient.
  - the **identity** (`AGE-SECRET-KEY-1…`) opens every body ever filed. This
    namespace deliberately does NOT read it, and nothing in the app does: the app
    writes mail and never needs to read it back, so holding the identity would be
    holding a key for no purpose. Decryption is `age -d -i`, run by a person who
    fetched the identity themselves.

  That asymmetry is the reason a lost identity is unrecoverable and a lost
  recipient is not. Both are stored; only one is ever fetched here."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def keychain-service "cloud-itonami-app.mail-age")
(def kagi-item "itonami-mail-age")

(def ^:dynamic *environment* #(System/getenv %))

(defn- env [name] (not-empty (*environment* name)))

(defn- run
  "Run a command and return its stdout, or nil.

  Every failure is nil rather than an exception: each of these sources is
  optional by design, and one that is absent must fall through to the next
  rather than stop the resolution."
  [argv & {:keys [directory timeout-seconds] :or {timeout-seconds 10}}]
  (try
    (let [builder (ProcessBuilder. ^java.util.List (vec argv))
          _ (when directory (.directory builder (io/file directory)))
          process (.start builder)
          output (future (slurp (.getInputStream process)))
          finished? (.waitFor process timeout-seconds TimeUnit/SECONDS)]
      (when-not finished? (.destroyForcibly process))
      (when (and finished? (zero? (.exitValue process)))
        (not-empty (str/trim (deref output 2000 "")))))
    (catch Exception _ nil)))

(defn- split-recipients [value]
  (->> (str/split (str value) #"[,\s]+")
       (map str/trim)
       (filter #(str/starts-with? % "age1"))
       vec))

;; ---------------------------------------------------------------------------
;; sources

(defn- from-environment []
  (some-> (env "CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS") split-recipients not-empty))

(defn- from-file []
  (when-let [file (some-> (env "CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS_FILE") io/file)]
    (when (.isFile file)
      (->> (str/split-lines (slurp file))
           (map str/trim)
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           (mapcat split-recipients)
           vec
           not-empty))))

(defn- from-keychain []
  (some-> (run ["security" "find-generic-password"
                "-s" keychain-service "-a" "recipient" "-w"])
          split-recipients
          not-empty))

(defn- kagi-binary
  "kagi's CLI, if this machine has the repository beside it.

  A released install will not, and that is fine — the Keychain mirror above is
  what such an install reads. Looked up rather than configured because the path
  is a fact about the checkout, not a decision anyone should have to make."
  []
  (some #(let [file (io/file %)] (when (.canExecute file) file))
        (keep identity
              [(env "CLOUD_ITONAMI_KAGI_BIN")
               (str (System/getProperty "user.home")
                    "/github/com-junkawasaki/orgs/kotoba-lang/kagi/bin/kagi")])))

(defn- from-kagi
  "The recipient out of the kagi item.

  The item is a kagitaba record — 1Password's own item shape — so what comes back
  is EDN with a `recipient` field beside a concealed `identity` one. Parsed
  rather than regexed so that reading the wrong field would be a hard failure
  instead of a plausible one."
  []
  (when-let [binary (kagi-binary)]
    (when-let [raw (run [(.getPath binary) "get" kagi-item]
                        :directory (.getParent (.getParentFile binary))
                        :timeout-seconds 20)]
      (try
        (let [item (edn/read-string raw)
              fields (mapcat :section/fields (:item/sections item))
              recipient (some #(when (= "recipient" (:field/id %)) (:field/value %))
                              fields)]
          (some-> recipient split-recipients not-empty))
        (catch Exception _
          ;; Not a kagitaba item — an older plain-string entry. Accept it, but
          ;; only if it actually looks like a recipient.
          (not-empty (split-recipients raw)))))))

;; ---------------------------------------------------------------------------

(defn resolve-recipients
  "The recipients, and which source answered.

  The source travels with the answer because 'mail is being filed unencrypted'
  and 'mail is being filed to a key you did not expect' are both configuration
  mistakes, and neither is visible from the recipients alone."
  []
  (or (some (fn [[source f]]
              (when-let [recipients (f)]
                {:recipients recipients :source source}))
            [[:environment from-environment]
             [:file from-file]
             [:keychain from-keychain]
             [:kagi from-kagi]])
      {:recipients []
       :source nil
       :reason (str "age recipient がどこにも設定されていません。"
                    "kagi item `" kagi-item "`、Keychain service `"
                    keychain-service "` (account `recipient`)、または "
                    "CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS を設定してください")}))

(defn recipients
  "Just the recipients. `resolve-recipients` when the source matters."
  []
  (:recipients (resolve-recipients)))

(defn status
  "Whether this deployment can seal a mail body, without revealing a key.

  The recipients are public keys, so naming them is safe and is the point: an
  operator comparing this against the identity they hold is how they find out
  they are filing to a key they cannot open."
  []
  (let [{:keys [recipients source reason]} (resolve-recipients)]
    (cond-> {:schema "cloud.itonami.app.mail-age-key.v1"
             :sealed? (boolean (seq recipients))
             :source (some-> source name)
             :recipients recipients
             :identity-location
             {:kagi kagi-item
              :keychain (str keychain-service " (account: identity)")
              :note "この app は identity を読みません。復号は age -d -i を人が実行します"}}
      reason (assoc :reason reason))))
