(ns cloud.itonami.app.secret-scan-test
  "`.gitleaks.toml`, tested in both directions.

  An allowlist is the one kind of configuration whose failure mode is silence.
  Get it wrong and the scanner still exits 0 — it just stops looking, and a
  gate that cannot fail is worth less than no gate, because people trust it.

  So this asserts both halves:

    1. the repository's own history is clean, which is what the CI job runs;
    2. a real-looking private key is STILL reported — both in an unrelated
       file and inside the very file whose false positive prompted the
       allowlist.

  The second half is not hypothetical. The first version of `.gitleaks.toml`
  allowlisted by `paths`, and this test refused it: `paths` excludes the whole
  file from scanning before any other criterion is considered, so a planted
  key in that file went unreported while the config looked narrow. The
  allowlist now matches the SHAPE of the false positive instead.

  The fixtures are written to a temporary directory and never committed. They
  are random base64 in a PEM envelope — the point is that gitleaks classifies
  them as a private key, not that they are one.

  When gitleaks is absent the test says so and passes: a gate that fails on a
  machine without the tool teaches people to ignore it, and one that goes
  quiet teaches them it ran."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private detector-file "scripts/package-controller-workspaces.py")

(defn- gitleaks-version []
  (try (let [{:keys [exit out]} (shell/sh "gitleaks" "version")]
         (when (zero? exit) (str/trim out)))
       (catch Exception _ nil)))

(defn- pem-shaped-key []
  (let [random (java.security.SecureRandom.)
        encoder (java.util.Base64/getEncoder)
        line #(let [b (byte-array 48)] (.nextBytes random b) (.encodeToString encoder b))]
    (str "-----BEGIN RSA PRIVATE KEY-----\n"
         (str/join "\n" (repeatedly 6 line))
         "\n-----END RSA PRIVATE KEY-----\n")))

(defn- scan-dir
  "Findings from a `gitleaks dir` run over `dir`, using this repository's config."
  [dir]
  (let [report (io/file dir "report.json")
        {:keys [exit]} (shell/sh "gitleaks" "dir" (str dir) "--no-banner" "--redact"
                                 "--config" (.getAbsolutePath (io/file ".gitleaks.toml"))
                                 "--report-format" "json"
                                 "--report-path" (.getAbsolutePath report))]
    ;; exit 1 means findings, which is the expected outcome for the controls.
    (is (contains? #{0 1} exit) (str "gitleaks exited " exit))
    (if (.exists report)
      (json/read-str (slurp report) :key-fn keyword)
      [])))

(deftest the-allowlist-clears-the-detectors-own-markers-and-nothing-else
  (if-let [version (gitleaks-version)]
    (let [temporary (java.nio.file.Files/createTempDirectory
                     "gitleaks-control" (make-array java.nio.file.attribute.FileAttribute 0))
          dir (.toFile temporary)
          key-text (pem-shaped-key)
          nested (io/file dir "scripts")]
      (.mkdirs nested)
      (testing "a planted key is reported in an ordinary file"
        (spit (io/file dir "elsewhere.py") (str "KEY = '''" key-text "'''\n")))
      (testing "and inside the file the allowlist exists for"
        (spit (io/file nested "package-controller-workspaces.py")
              (str (slurp (io/file detector-file)) "\n\nEXAMPLE = '''" key-text "'''\n")))
      (let [findings (scan-dir dir)
            ;; `:File` is absolute here and relative under `gitleaks git`, so
            ;; match the tail rather than pinning a form that depends on how
            ;; the scan was invoked.
            files (set (map :File findings))
            reported? (fn [suffix] (some #(str/ends-with? % suffix) files))]
        (is (= 2 (count findings))
            (str "expected both planted keys to be reported, got " (pr-str files)))
        (is (reported? "elsewhere.py"))
        (is (reported? (.getName (io/file detector-file)))
            "the allowlist blinded the whole file instead of exempting one shape")
        (is (every? #(= "private-key" (:RuleID %)) findings))))
    (println "gitleaks not installed; secret-scan allowlist not exercised")))

(deftest the-repositorys-own-history-is-clean
  ;; What CI actually runs. `--no-git` is deliberately NOT used: the findings
  ;; this allowlist clears live in history, not in the working tree.
  (if (gitleaks-version)
    (let [{:keys [exit out err]} (shell/sh "gitleaks" "git" "--redact" "--no-banner")]
      (is (zero? exit)
          (str "gitleaks reports findings in this repository's history:\n"
               (str/trim (str out "\n" err)))))
    (println "gitleaks not installed; history scan not exercised")))
