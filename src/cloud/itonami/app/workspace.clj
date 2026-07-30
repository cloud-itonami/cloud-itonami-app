(ns cloud.itonami.app.workspace
  "Read-only adapters for the existing Kotoba and Cloud Itonami workspace systems."
  (:require [calendar.model :as calendar]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.store :as store]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [drive.model :as drive]
            [github.workflow :as github]
            [kotoba.shell.launcher :as shell]
            [mail.inbound :as inbound]
            [mail.mailbox :as mailbox])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset Charset StandardCharsets]
           [java.nio.file Files LinkOption Path]
           [java.time Duration Instant ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Base64]
           [java.util.concurrent TimeUnit]))

(defonce cache (atom {}))
(defonce runtime-config (atom {}))
(def cache-duration (Duration/ofSeconds 60))
(def jst (ZoneId/of "Asia/Tokyo"))

(defn invalidate! []
  (reset! cache {}))

(defn configure! [configuration]
  (reset! runtime-config (:workspace configuration))
  (invalidate!))

(defn workspace-root []
  (io/file (or (System/getenv "CLOUD_ITONAMI_WORKSPACE")
               (.getCanonicalPath (io/file "../../..")))))

(defn- archive-root []
  (io/file (or (System/getenv "CLOUD_ITONAMI_M365_ARCHIVE")
               (:m365-archive @runtime-config)
               (str (io/file (workspace-root) "m365-archive")))))

(defn- annex-pointer? [file]
  (try
    (with-open [reader (io/reader file)]
      (str/starts-with? (or (.readLine reader) "") "/annex/objects/"))
    (catch Exception _ false)))

(defn- inbox-timestamp [filename]
  (when-let [[_ raw] (re-find #"^(\d{8}T\d{6}Z)_" filename)]
    (try
      (-> (Instant/from (.parse (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssX") raw))
          (.atZone jst)
          (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))
      (catch Exception _ raw))))

(defn- read-prefix [file limit]
  (try
    (with-open [input (io/input-stream file)]
      (String. (.readNBytes input limit) StandardCharsets/ISO_8859_1))
    (catch Exception _ "")))

(defn- decode-quoted-printable [value charset header?]
  (let [source (.getBytes (if header? (str/replace value "_" " ") value)
                          StandardCharsets/ISO_8859_1)
        output (ByteArrayOutputStream.)]
    (loop [index 0]
      (when (< index (alength source))
        (let [current (bit-and 0xff (aget source index))]
          (cond
            (and (= current 61) (< (+ index 2) (alength source))
                 (re-matches #"[0-9A-Fa-f]{2}"
                             (String. source (inc index) 2 StandardCharsets/US_ASCII)))
            (do (.write output
                        (Integer/parseInt
                         (String. source (inc index) 2 StandardCharsets/US_ASCII) 16))
                (recur (+ index 3)))

            (and (= current 61) (< (inc index) (alength source))
                 (#{10 13} (bit-and 0xff (aget source (inc index)))))
            (recur (+ index (if (and (= 13 (bit-and 0xff (aget source (inc index))))
                                     (< (+ index 2) (alength source))
                                     (= 10 (bit-and 0xff (aget source (+ index 2)))))
                              3 2)))

            :else (do (.write output current) (recur (inc index)))))))
    (String. (.toByteArray output) (Charset/forName charset))))

(defn- decode-transfer [value encoding charset]
  (try
    (case (some-> encoding str/lower-case)
      "base64" (String. (.decode (Base64/getMimeDecoder) value)
                        (Charset/forName charset))
      "quoted-printable" (decode-quoted-printable value charset false)
      (String. (.getBytes value StandardCharsets/ISO_8859_1)
               (Charset/forName charset)))
    (catch Exception _ "")))

(defn- decode-mime-words [value]
  (let [value (or value "")
        encoded? (str/includes? value "=?")
        decoded
        (if encoded?
          (str/replace
           value
           #"(?i)=\?([^?]+)\?([bq])\?([^?]*)\?="
           (fn [[_ charset encoding encoded]]
             (try
               (if (= "b" (str/lower-case encoding))
                 (String. (.decode (Base64/getDecoder) encoded)
                          (Charset/forName charset))
                 (decode-quoted-printable encoded charset true))
               (catch Exception _ "(復号できない文字列)"))))
          (try
            (String. (.getBytes value StandardCharsets/ISO_8859_1)
                     StandardCharsets/UTF_8)
            (catch Exception _ value)))]
    (-> decoded (str/replace #"\s+" " ") str/trim)))

(defn- message-headers [content]
  (let [header-block (first (str/split content #"\r?\n\r?\n" 2))]
    (->> (str/split (str/replace header-block #"\r?\n[ \t]+" " ") #"\r?\n")
         (keep (fn [line]
                 (when-let [[_ name value] (re-matches #"(?i)^([^:]+):\s*(.*)$" line)]
                   [(keyword (str/lower-case name)) (decode-mime-words value)])))
         (into {}))))

(defn- address-parts [value]
  (let [value (or value "")
        [_ display email] (or (re-matches #"\s*\"?([^\"<]*)\"?\s*<([^>]+)>\s*" value)
                              [nil nil (second (re-find #"([^\s<>]+@[^\s<>]+)" value))])]
    {:display (or (not-empty (str/trim (or display ""))) email "送信者不明")
     :email (or email "unknown@local.invalid")}))

(defn- charset-from [content-type]
  (or (second (re-find #"(?i)charset=\"?([^\";\s]+)" (or content-type "")))
      "UTF-8"))

(defn- text-part [content]
  (let [[top-headers top-body] (str/split content #"\r?\n\r?\n" 2)
        top (message-headers top-headers)
        multipart? (str/includes? (str/lower-case (or (:content-type top) "")) "multipart/")
        [_ content-type-suffix part-headers part-body]
        (when multipart?
          (re-find #"(?is)Content-Type:\s*text/plain([^\r\n]*)\r?\n(.*?)\r?\n\r?\n(.*?)(?:\r?\n--[^\r\n]+|$)"
                   (or top-body "")))
        headers (if multipart? (message-headers (or part-headers "")) top)
        body (if multipart? part-body top-body)
        content-type (if multipart?
                       (str "text/plain" (or content-type-suffix ""))
                       (:content-type top))]
    (decode-transfer (or body "") (:content-transfer-encoding headers)
                     (charset-from content-type))))

(defn- message-text
  "The message's body as one line of readable text."
  [content]
  (-> (or (text-part content) "")
      (str/replace #"(?s)<[^>]+>" " ")
      (str/replace #"[\r\n\t ]+" " ")
      str/trim))

(defn- message-snippet
  "The first 220 characters, which is what a list row has room for.

  Only for display. The message itself keeps the whole body — this used to
  be all there was, and `mail.mailbox/search` reads what is in the message,
  so a word in the fifth paragraph was not findable by anything: not by the
  interface, which filtered this string, and not by the model, which had
  been handed this string as the body."
  [content]
  (let [text (message-text content)]
    (subs text 0 (min 220 (count text)))))

(defn inbox-mailbox
  "The archive's 受信トレイ as a `mail.mailbox`.

  Lifted out of `inbox-snapshot`, which built exactly this and then threw it
  away to return a list of view maps. The box is what `mail.mailbox` answers
  questions about — threads, labels, unread counts, search over the body —
  and none of those were askable while the only thing that left this
  namespace was the projection.

  Read-only, and the same for everyone: these are files on disk. What one
  person has read, starred or filed is not in here — that is per principal
  and lives in `cloud.itonami.app.mailbox`, laid over the top."
  []
  (let [directory (io/file (archive-root) "mail/受信トレイ")
        files (if (.isDirectory directory)
                (->> (.listFiles directory)
                     (filter #(.isFile %))
                     (sort-by #(.getName %) #(compare %2 %1))
                     (take 40))
                [])
        entries
        (mapv
           (fn [file]
             (let [available? (not (annex-pointer? file))
                   content (if available? (read-prefix file 65536) "")
                   headers (message-headers content)
                   sender (address-parts (:from headers))
                   id (.getName file)
                   received-at (inbox-timestamp id)
                   snippet (message-snippet content)
                   received (inbound/from-parts
                             {:provider :m365-archive
                              :provider-message-id id
                              :from (:email sender)
                              :to ["local@cloud-itonami.invalid"]
                              :subject (or (not-empty (:subject headers)) "(件名なし)")
                              ;; The body, not the snippet. `mailbox/search`
                              ;; searches the message's parts, so what goes
                              ;; in here is the difference between searching
                              ;; the mail and searching its first line.
                              :text (or (not-empty (message-text content))
                                        "本文はアーカイブされています。")
                              :headers headers
                              :received-at received-at})]
               (assoc
                (mailbox/message-entry
                 id (or (:message-id headers) id)
                 (:mail.inbound/message received)
                 {:received-at received-at
                  :size-bytes (.length file)
                  :labels #{:inbox}
                  :read? true})
                :available? available?
                :sender sender
                :snippet snippet)))
           files)
        box (reduce mailbox/deliver
                    (mailbox/mailbox "local-inbox"
                                     "local@cloud-itonami.invalid")
                    entries)]
    (assoc box
           :mailbox/source "m365-archive / mail / 受信トレイ"
           :mailbox/file-count (if (.isDirectory directory)
                                 (count (filter #(.isFile %) (.listFiles directory)))
                                 0))))

(defn entry-view
  "One message as this app hands it out.

  Here rather than in `inbox-snapshot` because `cloud.itonami.app.mailbox`
  renders the same entries after laying a principal's marks over them, and
  two functions turning the same entry into JSON would be two answers to
  what a message is."
  [entry]
  (let [message (:mailbox.message/message entry)
        sender (get entry :sender)]
    {:id (:mailbox.message/id entry)
     :thread (:mailbox.message/thread-id entry)
     :subject (:mail/subject message)
     :from (:display sender)
     :from-email (:email sender)
     :received-at (:mailbox.message/received-at entry)
     :snippet (get entry :snippet)
     :size-bytes (:mailbox.message/size-bytes entry)
     :available? (get entry :available?)
     :read? (boolean (:mailbox.message/read? entry))
     :labels (vec (sort (map name (:mailbox.message/labels entry))))}))

(defn inbox-snapshot []
  (let [box (inbox-mailbox)
        archive-items
        (mapv (fn [entry]
                (-> (entry-view entry)
                    (assoc :provider :m365-archive)
                    (update :labels #(vec (distinct (conj % "archive"))))))
              (mailbox/search box "" {:label :inbox}))
        synced-messages (vals (get-in (store/snapshot)
                                      [:mail-sync :messages]))
        synced (->> synced-messages
                    (sort-by :received-at #(compare %2 %1))
                    (take 100)
                    (mapv #(-> %
                               (select-keys [:id :provider :subject :from
                                             :from-email :received-at :snippet
                                             :labels :size-bytes])
                               (assoc :available? true))))]
    {:source "m365-archive / Gmail / Microsoft Graph"
     :model "kotoba-lang/mail"
     :mode "oauth-delta+archive"
     :sealed-receiver "net-kotobase/mail-worker"
     :sync (get-in (store/snapshot) [:mail-sync :providers])
     :count (+ (:mailbox/file-count box)
               (count synced-messages))
     :items (vec (concat synced archive-items))}))

(defn- shallow-files [directory]
  (if-not (.isDirectory directory)
    []
    (with-open [paths (Files/walk (.toPath directory) 3
                                  (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator paths))
           (filter #(Files/isRegularFile ^Path %
                                         (make-array LinkOption 0)))
           (take 80)
           (mapv #(.toFile ^Path %))))))

(defn- media-type [filename]
  (or (try (Files/probeContentType (.toPath (io/file filename)))
           (catch Exception _ nil))
      "application/octet-stream"))

(defn drive-snapshot []
  (let [directory (io/file (archive-root) "onedrive")
        files (shallow-files directory)
        model (reduce
               (fn [state file]
                 (let [relative (str (.relativize (.toPath directory) (.toPath file)))]
                   (drive/add-item
                    state
                    (drive/file relative
                                {:drive/title (.getName file)
                                 :drive/media-type (media-type (.getName file))
                                 :drive/object-ref relative
                                 :drive/folder (or (some-> file .getParentFile .getName) "onedrive")
                                 :drive/size-bytes (.length file)
                                 :drive/available? (not (annex-pointer? file))}))))
               (drive/drive "m365-onedrive")
               files)]
    {:source "m365-archive / onedrive"
     :model "kotoba-lang/drive"
     :mode "archive"
     :count (count (:drive/items model))
     :items (mapv (fn [item]
                    {:id (:drive/id item)
                     :name (:drive/title item)
                     :folder (:drive/folder item)
                     :media-type (:drive/media-type item)
                     :size-bytes (:drive/size-bytes item)
                     :available? (:drive/available? item)})
                  (vals (:drive/items model)))}))

(defn- process-result
  ([command] (process-result command {}))
  ([command environment]
  (try
    (let [builder (doto (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true))
          _ (doseq [[key value] environment]
              (.put (.environment builder) key value))
          process (.start builder)
          output-future (future (slurp (.getInputStream process)))
          completed? (.waitFor process 8 TimeUnit/SECONDS)]
      (if completed?
        {:exit (.exitValue process) :output (deref output-future 1000 "")}
        (do (.destroyForcibly process)
            {:exit 124 :output "GitHub request timed out"})))
    (catch Exception error
      {:exit 127 :output (.getMessage error)}))))

(defn projects-snapshot []
  (let [scope (github/scope "kotoba-lang" "kotoba")
        query github/projects-v2-query
        delegated-token (local-identity/access-token :github)
        {:keys [exit output]}
        (process-result ["gh" "api" "graphql"
                         "-f" (str "query=" query)
                         "-F" "owner=kotoba-lang"
                         "-F" "repo=kotoba"]
                        (if delegated-token {"GH_TOKEN" delegated-token} {}))]
    (if (zero? exit)
      (let [body (json/read-str output :key-fn keyword)
            model (github/snapshot scope {} body)]
        {:source "kotoba-lang/com-github"
         :mode (if delegated-token "delegated" "live")
         :status "connected"
         :scope (:graph scope)
         :views ["Table" "Board" "Roadmap"]
         :items (:projects model)})
      {:source "kotoba-lang/com-github"
       :mode "live"
       :status (if (str/includes? output "INSUFFICIENT_SCOPES")
                 "permission-required"
                 "unavailable")
       :scope (:graph scope)
       :views ["Table" "Board" "Roadmap"]
       :items []
       :message (if (str/includes? output "INSUFFICIENT_SCOPES")
                  "GitHub Projects の読み取りには read:project 権限が必要です。"
                  "GitHub Projects に接続できませんでした。")})))

(defn- iso-range []
  (let [start (-> (ZonedDateTime/now jst)
                  .toLocalDate
                  (.atStartOfDay jst))]
    [(.toString (.toInstant start))
     (.toString (.toInstant (.plusDays start 7)))]))

(defn calendar-snapshot []
  (let [[from to] (iso-range)
        result (shell/dispatch
                ["native-host" "provider"
                 "--target" "macos"
                 "--provider-command" "calendar/list-events"
                 "--host-arg" "--from" "--host-arg" from
                 "--host-arg" "--to" "--host-arg" to
                 "--policy-edn" "{:allow [\"calendar/read\"] :deny []}"])
        raw (get-in result [:kotoba.cli/data :kotoba.shell/stdout])]
    (if (:kotoba.cli/ok? result)
      (let [payload (json/read-str (str/trim raw) :key-fn keyword)
            events (->> (:events payload)
                        (map (fn [item]
                               (calendar/event
                                (:id item)
                                {:calendar/title (:title item)
                                 :calendar/start (:start item)
                                 :calendar/end (:end item)
                                 :calendar/source (:calendar item)
                                 :calendar/all-day? (:allDay item)})))
                        (reduce calendar/add-event (calendar/calendar "local"))
                        calendar/events-in-order
                        (take 16)
                        (mapv (fn [event]
                                {:id (:calendar/id event)
                                 :title (:calendar/title event)
                                 :start (:calendar/start event)
                                 :end (:calendar/end event)
                                 :calendar (:calendar/source event)
                                 :all-day? (:calendar/all-day? event)})))]
        {:source "kotoba-lang/shell / macOS EventKit"
         :model "kotoba-lang/calendar"
         :status (:authorization payload)
         :range {:from from :to to}
         :items events
         :days (->> (range 7)
                    (mapv (fn [offset]
                            (let [day (-> (ZonedDateTime/now jst)
                                          .toLocalDate
                                          (.plusDays offset)
                                          str)]
                              {:date day
                               :items (filterv #(str/starts-with? (str (:start %)) day)
                                               events)}))))})
      {:source "kotoba-lang/shell / macOS EventKit"
       :model "kotoba-lang/calendar"
       :status "unavailable"
       :range {:from from :to to}
       :items []
       :message "カレンダーを読み取れませんでした。"})))

(defn build-snapshot []
  {:schema "cloud.itonami.app.workspace.v1"
   :generated-at (.toString (Instant/now))
   :inbox (inbox-snapshot)
   :projects (projects-snapshot)
   :drive (drive-snapshot)
   :scheduler (calendar-snapshot)})

(defn snapshot
  ([] (snapshot :workspace build-snapshot))
  ([cache-key loader]
   (let [{:keys [created-at data]} (get @cache cache-key)
        fresh? (and created-at
                    (neg? (compare (Duration/between created-at (Instant/now))
                                   cache-duration)))]
    (if fresh?
      data
      (let [data (loader)]
        (swap! cache assoc cache-key {:created-at (Instant/now) :data data})
        data)))))

(defn clear-cache! []
  (reset! cache {}))
