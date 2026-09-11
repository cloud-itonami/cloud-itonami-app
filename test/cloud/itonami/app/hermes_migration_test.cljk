(ns cloud.itonami.app.hermes-migration-test
  "The optimistic lock on a LIVE Hermes home.

  `stage!` inventories the source, exports for ~25 minutes, inventories again,
  and refuses when the two revisions differ. Measured 2026-09-01 (ADR-2609012300)
  that refusal fired on every attempt: `cron/jobs.json` is rewritten by the cron
  ticker about every two minutes with byte-identical content, and the inventory
  hashed its mtime. `hermes pause` halts dispatch, not the ticker, so there was
  no way to hold the source still for long enough.

  These tests pin the distinction the fix rests on: an mtime that moved with no
  edit must NOT move the revision, and an edit must."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private inventory
  "The private inventory under test, reached by var so the namespace keeps its
  own boundary."
  (do (require 'cloud.itonami.app.hermes-migration)
      (resolve 'cloud.itonami.app.hermes-migration/profile-inventory)))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "hermes-migration-test"
                                      (into-array FileAttribute []))))

(defn- write! [^File root relative content]
  (let [f (io/file root relative)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- revision [^File root]
  (get-in (inventory {:id "default" :root root}) [:source :revision]))

(defn- touch!
  "Move a file's mtime without changing a byte, the way the cron ticker does."
  [^File f]
  (let [before (.lastModified f)
        moved (+ before 240000)]
    (is (.setLastModified f moved) "the fixture must be able to move an mtime")
    (is (not= before (.lastModified f)) "the mtime must actually have moved")))

;; The `default` profile exports a whitelist of portable roots rather than the
;; whole home, so a fixture has to put its files where that whitelist looks.
;; `cron/` and `config.yaml` are both on it; a file outside it is invisible to
;; the inventory, which is why the control below uses `config.yaml`.

(deftest ticker-rewritten-jobs-json-does-not-move-the-revision
  (let [root (temp-dir)
        jobs (write! root "cron/jobs.json" "{\"jobs\":[{\"name\":\"a\"}]}")]
    (write! root "config.yaml" "provider: openrouter\n")
    (let [before (revision root)]
      (touch! jobs)
      (is (= before (revision root))
          "a jobs.json whose bytes did not change must not move the revision")
      (testing "and the guarantee it protects is still in force"
        (spit jobs "{\"jobs\":[{\"name\":\"a\"},{\"name\":\"b\"}]}")
        (is (not= before (revision root))
            "an edited jobs.json must move the revision")))))

(deftest mtime-still-matters-for-reviewed-configuration
  (let [root (temp-dir)
        config (write! root "config.yaml" "provider: openrouter\n")]
    (write! root "cron/jobs.json" "{\"jobs\":[]}")
    (let [before (revision root)]
      (touch! config)
      (is (not= before (revision root))
          (str "only the ticker-rewritten files are content-hashed; a touched "
               "config.yaml must still move the revision, or this fix would "
               "have widened into 'ignore every mtime'")))))

(deftest a-volatile-file-is-outside-the-lock-entirely
  (let [root (temp-dir)]
    (write! root "cron/jobs.json" "{\"jobs\":[]}")
    (let [before (revision root)
          heartbeat (write! root "cron/ticker_heartbeat" "1")]
      (is (= before (revision root))
          "ticker_heartbeat is volatile: appearing must not move the revision")
      (spit heartbeat "2")
      (is (= before (revision root))
          "nor must changing it"))))
