(ns cloud.itonami.app.hermes-import-data
  "Read the bounded, content-addressed part of a staged Hermes bundle.

  A manifest path is never trusted by itself: every file is resolved below the
  migration directory, required to be a regular file, and checked against the
  sha256 recorded when it was staged.  This namespace has no authority to
  create a Bot or grant a tool; it only projects portable source evidence."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files LinkOption]
           [java.security MessageDigest]))

(def ^:private max-context-chars (* 64 1024))
(def ^:private max-session-lines 20000)

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256-file [^File file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [input (io/input-stream file)]
      (loop []
        (let [n (.read input buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur)))))
    (hex (.digest digest))))

(defn artifact [profile kind]
  (some #(when (= kind (:kind %)) %) (:artifacts profile)))

(defn verified-artifact-file
  "Resolve and verify one artifact below data-dir/bot-imports/<migration-id>."
  [data-dir migration-id artifact]
  (when-not (and (re-matches #"hermes-[A-Za-z0-9_-]{1,100}" (str migration-id))
                 (map? artifact)
                 (= "staged" (:state artifact))
                 (string? (:path artifact))
                 (not (str/blank? (:sha256 artifact))))
    (throw (ex-info "Hermes import artifact is not a staged content-addressed file."
                    {:type :bot-import/invalid-artifact})))
  (let [root (.getCanonicalFile (io/file data-dir "bot-imports" (str migration-id)))
        file (.getCanonicalFile (io/file root (:path artifact)))
        root-path (str (.getPath root) File/separator)]
    (when-not (and (str/starts-with? (.getPath file) root-path)
                   (Files/isRegularFile (.toPath file)
                                        (into-array LinkOption
                                                    [LinkOption/NOFOLLOW_LINKS])))
      (throw (ex-info "Hermes import artifact escaped or is missing."
                      {:type :bot-import/artifact-missing})))
    (when-not (= (:sha256 artifact) (sha256-file file))
      (throw (ex-info "Hermes import artifact digest changed after staging."
                      {:type :bot-import/artifact-changed})))
    file))

(defn runtime-context [data-dir migration-id profile]
  (when-let [record (artifact profile "hermes-runtime-context")]
    (let [text (slurp (verified-artifact-file data-dir migration-id record))]
      (subs text 0 (min max-context-chars (count text))))))

(defn sessions
  "Return the redacted source sessions for one imported profile.

  The source exporter writes one JSON object per line. A corrupt line fails the
  import instead of silently dropping history. The hard line limit prevents a
  malicious manifest from turning a compatibility request into unbounded work."
  [data-dir migration-id profile]
  (when-let [record (artifact profile "hermes-session-export")]
    (with-open [reader (io/reader
                        (verified-artifact-file data-dir migration-id record))]
      (let [rows (doall (take (inc max-session-lines) (line-seq reader)))]
        (when (> (count rows) max-session-lines)
          (throw (ex-info "Hermes session export exceeds the admitted line count."
                          {:type :bot-import/session-limit})))
        (->> rows
             (remove str/blank?)
             (mapv #(json/read-str % :key-fn keyword)))))))

(defn seed-messages
  "Project the latest useful source conversation into Itonami's live transcript.

  Full redacted history stays in the bundle and compatibility API. Only the
  last user/assistant messages of the most recently active session become
  ambient model context; old tool results never become new authority."
  [session-rows max-messages max-message-chars]
  (let [selected (last (sort-by #(str (or (:last_active %)
                                          (:updated_at %)
                                          (:created_at %) ""))
                                session-rows))]
    (->> (:messages selected)
         (keep (fn [message]
                 (let [role (case (str (:role message))
                              "user" :person
                              "assistant" :bot
                              nil)
                       content (:content message)
                       text (cond
                              (string? content) content
                              (sequential? content)
                              (->> content
                                   (keep #(when (map? %) (or (:text %) (:content %))))
                                   (str/join "\n"))
                              :else nil)]
                   (when (and role (not (str/blank? (str text))))
                     {:role role
                      :text (subs (str text) 0
                                  (min max-message-chars (count (str text))))
                      :at (or (:timestamp message) (:created_at message))}))))
         (take-last max-messages)
         vec)))
