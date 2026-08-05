(ns cloud.itonami.app.project-remote
  "Pushing a project's annexed mail bodies to Backblaze B2.

  ## What this is for

  ADR-0021 put filed mail bodies in the project's git-annex as age ciphertext.
  That made them part of the repository — but the bytes only ever existed on the
  machine that filed them. `git clone` of such a project gets the pointers and
  nothing to resolve them with, and losing the disk loses every body.

  This gives the annex somewhere else to hold them.

  ## Why the bytes may leave this machine at all

  Because they are already sealed. The special remote is `encryption=none`, and
  that is not a gap: the content git-annex is asked to store IS the age
  ciphertext, so B2 receives what B2 could have received anyway if the operator
  had uploaded the `.age` file by hand. Layering git-annex's own GPG encryption
  on top would add a second key to lose for no additional secrecy — and this
  workspace's rule for exactly this case says to annex the ciphertext and let the
  annex key be a ciphertext identity, which an `MD5E-…​.eml.age` key is.

  What B2 learns is therefore: a count of objects, each one's size, and that they
  are age envelopes. Not a sender, not a subject, not a date.

  ## Why every project gets its own prefix

  One bucket is shared by every dataset in this workspace, and `fileprefix` is
  what keeps them apart — without it, every dataset's keys land at the bucket
  root and nothing on the B2 side can say which dataset an object belongs to.
  The prefix here includes the organization's storage id, so two organizations
  filing a project of the same name do not write into each other's space.

  ## Credentials

  Resolved the way the rest of this workspace resolves them: the environment
  first, then the macOS Keychain item `b2:gftdcojp-m365-annex`, whose account is
  the key id and whose password is the application key. Nothing is written to
  the repository — git-annex keeps them in `.git/annex/creds`, which is not
  committed, so a clone must supply them again to enable the remote."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def schema "cloud.itonami.app.project-remote.v1")

(def remote-name "b2")
(def keychain-service "b2:gftdcojp-m365-annex")
(def default-endpoint "s3.us-west-004.backblazeb2.com")
(def default-bucket "gftdcojp-m365-annex")

(def ^:dynamic *environment* #(System/getenv %))

(defn- env [name] (not-empty (*environment* name)))

(defn- run
  "Run a command, returning `{:exit :output}`. Never throws.

  `:merge-stderr?` defaults true because a failing `initremote` or `copy` says
  why on stderr and that reason is the whole value of the result. It must be
  FALSE for anything whose output is counted or parsed: this machine's git
  prints `error: could not read IPC response` from its filesystem monitor, and
  merged in, those lines are indistinguishable from data. Measured here — a
  `git annex find` reporting one file was counted as two, and the derived
  unpushed count then said one body had not reached B2 when all of them had."
  [directory argv & {:keys [environment timeout-seconds merge-stderr?]
                     :or {timeout-seconds 600 merge-stderr? true}}]
  (try
    (let [builder (doto (ProcessBuilder. ^java.util.List (vec argv))
                    (.directory (io/file directory))
                    (.redirectErrorStream (boolean merge-stderr?)))
          _ (doseq [[k v] environment] (.put (.environment builder) k v))
          process (.start builder)
          output (future (slurp (.getInputStream process)))
          finished? (.waitFor process timeout-seconds TimeUnit/SECONDS)]
      (when-not finished? (.destroyForcibly process))
      {:exit (if finished? (.exitValue process) :timeout)
       :output (str/trim (deref output 5000 ""))})
    (catch Exception error
      {:exit :error :output (.getMessage error)})))

;; ---------------------------------------------------------------------------
;; credentials

(defn- keychain-credentials
  "The combined Keychain form: account is the key id, password is the app key.

  Two `security` calls rather than one parse, because the account is metadata
  and the password is not — asking for them separately is what keeps the second
  call the only one that touches a secret."
  []
  (let [account (let [{:keys [exit output]}
                      (run "." ["security" "find-generic-password"
                                "-s" keychain-service]
                           :timeout-seconds 10 :merge-stderr? false)]
                  (when (= 0 exit)
                    (some->> (str/split-lines output)
                             (some #(second (re-matches #"\s*\"acct\"<blob>=\"(.*)\"" %))))))
        secret (let [{:keys [exit output]}
                     (run "." ["security" "find-generic-password"
                               "-s" keychain-service "-w"]
                          :timeout-seconds 10 :merge-stderr? false)]
                 (when (= 0 exit) (not-empty output)))]
    (when (and account secret)
      {:key-id account :app-key secret :source :keychain})))

(defn credentials
  "B2 credentials, or nil. The environment wins so a deployment can override."
  []
  (or (when-let [key-id (env "B2_KEY_ID")]
        (when-let [app-key (env "B2_APP_KEY")]
          {:key-id key-id :app-key app-key :source :environment}))
      (keychain-credentials)))

(defn bucket [] (or (env "B2_BUCKET") default-bucket))
(defn endpoint [] (or (env "B2_ENDPOINT") default-endpoint))

;; ---------------------------------------------------------------------------
;; the remote

(defn- annex-environment [{:keys [key-id app-key]}]
  ;; git-annex's S3 backend reads the AWS names; B2's S3-compatible API takes
  ;; the same pair.
  {"AWS_ACCESS_KEY_ID" key-id "AWS_SECRET_ACCESS_KEY" app-key})

(defn file-prefix
  "Where this project's objects live inside the shared bucket."
  [{:keys [organization-storage-id project-slug]}]
  (str "cloud-itonami-mail/" organization-storage-id "/" project-slug "/"))

(defn- remote-known? [directory]
  (let [{:keys [exit output]} (run directory ["git" "annex" "info" remote-name]
                                   :timeout-seconds 30 :merge-stderr? false)]
    (and (= 0 exit) (str/includes? output "remote:"))))

(defn ensure-remote!
  "Register B2 as this dataset's special remote, or enable an existing one.

  `enableremote` first: a dataset cloned from elsewhere already carries the
  remote's configuration in its git-annex branch and only needs the credentials,
  and calling `initremote` on it would fail with a name collision rather than do
  the right thing."
  [directory project]
  (if-let [creds (credentials)]
    (let [environment (annex-environment creds)
          enabled (run directory ["git" "annex" "enableremote" remote-name]
                       :environment environment :timeout-seconds 120)]
      (if (= 0 (:exit enabled))
        {:ok? true :remote remote-name :action :enabled :credentials (:source creds)}
        (let [{:keys [exit output]}
              (run directory
                   ["git" "annex" "initremote" remote-name
                    "type=S3" "protocol=https"
                    (str "host=" (endpoint)) "port=443"
                    (str "bucket=" (bucket))
                    "signature=v4" "chunk=50MiB"
                    ;; The content is already age ciphertext — see this
                    ;; namespace's docstring for why a second layer is not
                    ;; secrecy, only a second key to lose.
                    "encryption=none"
                    (str "fileprefix=" (file-prefix project))]
                   :environment environment :timeout-seconds 180)]
          (if (= 0 exit)
            {:ok? true :remote remote-name :action :initialized
             :credentials (:source creds)
             :bucket (bucket) :file-prefix (file-prefix project)}
            {:ok? false :error output}))))
    {:ok? false
     :error (str "B2 の資格情報がありません。B2_KEY_ID / B2_APP_KEY を設定するか、"
                 "Keychain service `" keychain-service "` を確認してください")}))

(defn push!
  "Copy this project's annexed content to B2.

  `git annex copy --to`, not `datalad push`: the envelopes are ordinary Git
  objects and there is no git remote to push them to, while the bodies are the
  thing that needs somewhere else to live. Asking DataLad to push would try both
  and report a failure for the half that was never configured."
  [directory project]
  (let [ensured (ensure-remote! directory project)]
    (if-not (:ok? ensured)
      (assoc ensured :schema schema)
      (let [creds (credentials)
            {:keys [exit output]}
            (run directory ["git" "annex" "copy" "--to" remote-name "--quiet"
                            "--jobs" "3"]
                 :environment (annex-environment creds)
                 :timeout-seconds 3600)]
        (merge {:schema schema :remote remote-name :bucket (bucket)
                :file-prefix (file-prefix project)
                :credentials (:source creds)}
               (if (= 0 exit)
                 {:ok? true :pushed? true
                  ;; git-annex is quiet on success, so the useful report is what
                  ;; the remote now holds rather than what the command printed.
                  :output (not-empty output)}
                 {:ok? false :pushed? false :error output}))))))

(defn- count-lines [{:keys [exit output]}]
  (when (= 0 exit)
    (count (remove str/blank? (str/split-lines (str output))))))

(defn status
  "What this project's annex holds, and how much of it B2 does not.

  Counted with `git annex find`, not `git annex info`: `info --fast` reports
  repositories and disk space but no file counts, and the non-fast form walks
  every key. What an operator actually needs is one number — how many bodies
  exist only on this machine — and `--not --in b2` is exactly that question."
  [directory project]
  (let [configured? (remote-known? directory)
        annexed (count-lines (run directory ["git" "annex" "find" "--include" "*"]
                                  :timeout-seconds 120 :merge-stderr? false))
        unpushed (when configured?
                   (count-lines (run directory
                                     ["git" "annex" "find" "--not" "--in" remote-name]
                                     :timeout-seconds 120 :merge-stderr? false)))]
    (cond-> {:schema schema
             :remote remote-name
             :bucket (bucket)
             :file-prefix (file-prefix project)
             :credentials (some-> (credentials) :source name)
             :remote-configured? configured?
             :annexed annexed}
      configured? (assoc :unpushed unpushed
                         ;; The reassuring number is derived, not measured, so
                         ;; that it cannot disagree with the one above it.
                         :pushed (when (and annexed unpushed)
                                   (- annexed unpushed))))))
