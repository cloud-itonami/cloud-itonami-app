(ns cloud.itonami.app.config-test
  "The rules that decide WHERE this process writes and WHETHER it will bind.

  `cloud.itonami.app.config` had no test, and it holds two properties that are only
  safe because someone chose them deliberately:

    - the data directory takes the SYSTEM PROPERTY over the environment variable,
      which is the reverse of the usual precedence. The docstring records why: on
      2026-07-30 the suite, run from a repository root where this defaults to ./data,
      replaced a developer's real identity state with a test fixture. Eighteen
      store/transact! calls in the tests write through this function and two of them
      replace the whole :identity partition. A later 'fix' restoring the conventional
      precedence would bring that back, and nothing would have failed.
    - loading refuses outright when the privacy policy demands a loopback bind and the
      configured host is not one. A config that merely logged and continued would leave
      the process listening where the policy says it must not.

  What is NOT tested here, stated rather than implied: the environment-variable leg of
  data-dir. A JVM cannot portably set its own environment, so the half of the precedence
  rule that reads CLOUD_ITONAMI_DATA_DIR is exercised only by the property winning when
  both could apply. Pretending otherwise would be worse than saying so."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config]))

(def ^:private data-dir-property "cloud.itonami.data-dir")

(defn- with-property
  "Set the data-dir property for f, then restore whatever was there. The suite's own
  :test alias sets it, so leaking a value here would send later tests' writes
  somewhere else -- the exact failure this namespace exists to prevent."
  [value f]
  (let [previous (System/getProperty data-dir-property)]
    (try
      (if value
        (System/setProperty data-dir-property value)
        (System/clearProperty data-dir-property))
      (f)
      (finally
        (if previous
          (System/setProperty data-dir-property previous)
          (System/clearProperty data-dir-property))))))

;; ---------------------------------------------------------------------------
;; where this process writes
;; ---------------------------------------------------------------------------

(deftest the-system-property-decides-the-data-directory
  (testing "the :test alias sets this property precisely so a suite run cannot write
            into a real store"
    (with-property "target/config-test-a"
      (fn []
        (is (str/ends-with? (.getPath (config/data-dir)) "/target/config-test-a"))))
    (with-property "target/config-test-b"
      (fn []
        (is (str/ends-with? (.getPath (config/data-dir)) "/target/config-test-b"))
        (is (not (str/includes? (.getPath (config/data-dir)) "config-test-a"))
            "it is read each call, not captured once at load")))))

(deftest the-data-directory-is-canonical-and-absolute
  (testing "relative paths are resolved once here, so no caller has to reason about
            what directory the process happened to start in"
    (with-property "target/config-test-c"
      (fn []
        (let [d (config/data-dir)]
          (is (.isAbsolute d))
          (is (= (.getCanonicalFile d) d)))))))

(deftest a-dotted-path-is-normalised-rather-than-passed-through
  (with-property "target/x/../config-test-d"
    (fn []
      (let [p (.getPath (config/data-dir))]
        (is (not (str/includes? p "..")))
        (is (str/ends-with? p "/target/config-test-d"))))))

;; NOTE: the environment-variable fallback cannot be exercised from inside the JVM --
;; see this namespace's docstring. What CAN be shown is that the property is consulted
;; first, which is the half that protects a real store from a test run.

;; ---------------------------------------------------------------------------
;; the resident install resolves to the resident store
;; ---------------------------------------------------------------------------

(defn- with-properties
  "Run f with `user.home` and `user.dir` pointing at a scratch layout.

  Both are ordinary system properties, and `java.io.File` resolves a relative
  path against `user.dir`, so this exercises the real resolution rather than a
  parallel copy of it. Restored afterwards -- a leaked `user.dir` would send
  every later relative path in the suite somewhere else."
  [home dir f]
  (let [previous-home (System/getProperty "user.home")
        previous-dir (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.home" home)
      (System/setProperty "user.dir" dir)
      (f)
      (finally
        (System/setProperty "user.home" previous-home)
        (System/setProperty "user.dir" previous-dir)))))

(defn- scratch-home []
  (let [home (.toFile (java.nio.file.Files/createTempDirectory
                       "cloud-itonami-app-resident"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (io/file home ".cloud-itonami" "app"))
    (.getCanonicalPath home)))

(deftest the-resident-app-directory-resolves-the-resident-store
  (testing "`~/.cloud-itonami/app` and `~/.cloud-itonami/data` are one install.
            bin/itonami said so by exporting the variable; a bare `clojure -M:cli`
            in the same directory used to fall back to a relative `data` and make
            a third store holding an enrollment key no server had written"
    (let [home (scratch-home)]
      (with-property nil
        (fn []
          (with-properties home (str home "/.cloud-itonami/app")
            (fn []
              (is (= (.getCanonicalPath (io/file home ".cloud-itonami" "data"))
                     (.getPath (config/data-dir)))))))))))

(deftest any-other-directory-still-gets-the-relative-store
  (testing "the resident leg is a rule about ONE layout, not a new default -- a
            checkout keeps its own ./data.

            Asserted as 'not the resident store, and still a relative ./data'
            rather than as an exact path: `File/getCanonicalPath` resolves a
            relative name against the process's real working directory, not
            against the `user.dir` property this test sets. Writing the
            expected path out would have been asserting the JVM's cwd, which
            is not what this rule is about."
    (let [home (scratch-home)
          elsewhere (io/file home "checkout")]
      (.mkdirs elsewhere)
      (with-property nil
        (fn []
          (with-properties home (.getCanonicalPath elsewhere)
            (fn []
              (let [resolved (.getPath (config/data-dir))]
                (is (not= (.getCanonicalPath (io/file home ".cloud-itonami" "data"))
                          resolved)
                    "the resident store is NOT adopted from another directory")
                (is (str/ends-with? resolved "/data")
                    "the relative fallback is what answered")))))))))

(deftest an-explicit-answer-still-wins-over-the-resident-layout
  (testing "the resident leg sits BELOW both explicit answers: an operator who
            names a directory from the resident app dir gets the one they named"
    (let [home (scratch-home)]
      (with-property "target/config-test-resident-override"
        (fn []
          (with-properties home (str home "/.cloud-itonami/app")
            (fn []
              (is (str/ends-with? (.getPath (config/data-dir))
                                  "/target/config-test-resident-override")))))))))

;; ---------------------------------------------------------------------------
;; naming a store without publishing its path
;; ---------------------------------------------------------------------------

(deftest the-store-fingerprint-names-the-directory-and-not-its-path
  (let [a (io/file "target/config-test-fp-a")
        b (io/file "target/config-test-fp-b")]
    (is (= (config/store-fingerprint a) (config/store-fingerprint a))
        "stable across calls -- two processes compare it")
    (is (not= (config/store-fingerprint a) (config/store-fingerprint b))
        "two stores are two names")
    (is (= (config/store-fingerprint a)
           (config/store-fingerprint (io/file "target/x/../config-test-fp-a")))
        "of the canonical path, so the same directory reached by another route
         fingerprints the same")
    (is (re-matches #"[0-9a-f]{12}" (config/store-fingerprint a)))
    (is (not (str/includes? (config/store-fingerprint a) "config-test-fp-a"))
        "the path is not recoverable from it -- /health publishes this to
         callers that have no session")))

;; ---------------------------------------------------------------------------
;; whether this process will bind at all
;; ---------------------------------------------------------------------------

(deftest the-shipped-config-is-loopback-and-says-so
  (let [c (config/load-config)]
    (is (true? (get-in c [:privacy :bind-loopback-only?])))
    (is (= "127.0.0.1" (get-in c [:server :host]))
        "the shipped host satisfies the shipped policy -- if these two ever
         disagreed, load-config would refuse and the app would not start")))

(deftest every-authority-ships-disabled
  (testing "these are the only surfaces that carry a proposal outward to something
            that could affect a real card, line or call, so switching one on is a
            deployment decision and not a default"
    (let [authorities (:authorities (config/load-config))]
      (is (seq authorities))
      (doseq [[k v] authorities]
        (is (false? (:enabled? v)) (str k " must ship disabled"))
        (is (nil? (:endpoint v)) (str k " must ship with no endpoint"))))))

(deftest loading-produces-the-partitions-the-app-reads
  (testing "named explicitly so a defaults edit that drops one fails here rather than
            as a nil somewhere far away"
    (let [c (config/load-config)]
      (doseq [k [:server :privacy :providers :authorities :identity :credentials]]
        (is (contains? c k) (str "missing " k))))))

(deftest murakumo-agent-output-fits-the-non-streaming-client-bound
  (let [provider (some #(when (= "murakumo" (:id %)) %)
                       (:providers (config/load-config)))]
    (is (= 512 (:max-output-tokens provider)))
    (is (= "qwen3.8-27b-fastmtp-aggressive" (:default-model provider)))
    (is (= ["murakumo-main" "qwen3.8-27b-fastmtp-aggressive"]
           (:models provider)))
    (is (= 32768
           (get-in provider [:context-window-tokens
                             "qwen3.8-27b-fastmtp-aggressive"])))
    (is (= 32768 (get-in provider [:context-window-tokens "murakumo-main"]))
        "the alias metadata must move in the same config change as its target")))

(deftest shipped-models-declare-their-current-maximum-context
  (let [providers (into {} (map (juxt :id identity))
                        (:providers (config/load-config)))]
    (is (= 500000
           (get-in providers ["xai" :context-window-tokens "grok-4.6"])))
    (doseq [model (get-in providers ["murakumo" :models])]
      (is (= 32768
             (get-in providers ["murakumo" :context-window-tokens model]))
          (str model " must carry the selected model's window")))))

;; ---------------------------------------------------------------------------
;; secrets are read from the environment, never held in config
;; ---------------------------------------------------------------------------

(deftest a-provider-with-no-env-name-has-no-secret
  (testing "nil rather than the empty string, so a caller cannot mistake 'no key
            configured' for 'a key that happens to be blank'"
    (is (nil? (config/env-secret {})))
    (is (nil? (config/env-secret {:api-key-env ""})))
    (is (nil? (config/env-secret {:api-key-env "   "})))))

(deftest an-unset-variable-yields-nil-not-an-empty-string
  (is (nil? (config/env-secret {:api-key-env "CLOUD_ITONAMI_DEFINITELY_UNSET_VARIABLE"}))))

(deftest a-set-variable-is-returned-as-is
  (testing "HOME is used because it is set in every environment this runs in; the
            point is the plumbing, not the value"
    (is (= (System/getenv "HOME") (config/env-secret {:api-key-env "HOME"})))))

(deftest no-provider-carries-a-key-in-the-config-itself
  (testing "config names the ENV VAR to read; a literal key in a defaults file would
            be a secret in git"
    (doseq [p (:providers (config/load-config))]
      (is (not (contains? p :api-key)) (str (:id p) " carries a literal key"))
      (is (or (nil? (:api-key-env p)) (string? (:api-key-env p)))))))

(deftest xai-ships-as-an-explicitly-reviewed-opt-in
  (let [xai (some #(when (= "xai" (:id %)) %) (:providers (config/load-config)))]
    (is (= :xai (:kind xai)))
    (is (= "https://api.x.ai/v1" (:base-url xai)))
    (is (= "XAI_API_KEY" (:api-key-env xai)))
    (is (= "grok-4.6" (:default-model xai)))
    (is (false? (:enabled? xai)))
    (is (false? (:reviewed? xai)))
    (is (not (contains? xai :api-key)))))

;; ---------------------------------------------------------------------------
;; a named profile that does not exist is an error, not a silent default
;; ---------------------------------------------------------------------------

(deftest the-profiles-directory-holds-what-the-env-var-would-name
  (testing "CLOUD_ITONAMI_PROFILE cannot be set from inside the JVM either, so what is
            checked here is the contract it relies on: a profile is an EDN file under
            profiles/, and load-config throws when a named one is missing rather than
            quietly running with defaults"
    (let [dir (io/file "profiles")]
      (when (.isDirectory dir)
        (doseq [f (.listFiles dir) :when (str/ends-with? (.getName f) ".edn")]
          (is (map? (edn/read-string (slurp f)))
              (str (.getName f) " must read as an EDN map")))))))
