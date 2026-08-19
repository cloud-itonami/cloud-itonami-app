(ns cloud.itonami.app.repository-profile-ci
  "Cross-repository ADR-0013 profile gate for GitHub Actions. The checked-out
  app profile is read locally; every other inventory entry is read from that
  repository's `main` through GitHub's contents API."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.repository-qualification :as qualification])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def ^:private current-repository "cloud-itonami/cloud-itonami-app")
(def ^:private max-profile-bytes (* 64 1024))

(defn- github-profile [^HttpClient client token repository]
  (let [builder (doto
                 (HttpRequest/newBuilder
                  (URI/create
                   (str "https://api.github.com/repos/" repository
                        "/contents/storage-profile.edn?ref=main")))
                  (.header "Accept" "application/vnd.github+json")
                  (.header "X-GitHub-Api-Version" "2022-11-28")
                  (.header "User-Agent" "cloud-itonami-repository-profile-ci"))
        _ (when (seq token) (.header builder "Authorization" (str "Bearer " token)))
        response (.send client (.build builder)
                        (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "GitHub repository profile request failed"
                      {:type :repository-storage/profile-fetch-failed
                       :repository repository :status (.statusCode response)})))
    (let [{:keys [encoding content]} (json/read-str (.body response)
                                                     :key-fn keyword)
          _ (when-not (= "base64" encoding)
              (throw (ex-info "GitHub repository profile encoding denied"
                              {:type :repository-storage/profile-encoding})))
          bytes (.decode (Base64/getMimeDecoder) ^String content)]
      (when (> (alength bytes) max-profile-bytes)
        (throw (ex-info "GitHub repository profile exceeds size limit"
                        {:type :repository-storage/profile-too-large})))
      (String. bytes StandardCharsets/UTF_8))))

(def ^:dynamic *fetch-profile* github-profile)

(defn audit!
  ([inventory-path] (audit! inventory-path (System/getenv "GITHUB_TOKEN")))
  ([inventory-path token]
   (let [inventory-file (.getCanonicalFile (io/file inventory-path))
         parent (.getParentFile inventory-file)
         entries (qualification/read-profile-inventory! inventory-file)
         client (HttpClient/newHttpClient)
         documents
         (mapv
          (fn [{:keys [repository path]}]
            (try
              {:repository repository
               :profile-text
               (if (= current-repository repository)
                 (slurp (io/file parent path "storage-profile.edn"))
                 (*fetch-profile* client token repository))}
              (catch Exception error
                ;; The type alone cannot tell a missing file from a 403 from a
                ;; DNS failure, and this audit's whole output is which repos
                ;; could not be read. Found by verify-error-provenance.
                {:repository repository
                 :error (or (:type (ex-data error))
                            :repository-storage/profile-unavailable)
                 :error-message (some-> (.getMessage ^Exception error)
                                        str/split-lines first str/trim not-empty
                                        (as-> m (subs m 0 (min 300 (count m)))))})))
          entries)]
     (assoc (qualification/audit-profile-documents documents)
            :inventory-count (count entries)))))

(defn -main [& [inventory-path]]
  (let [result (audit! (or inventory-path
                            "config/repository-storage-inventory.edn"))]
    (prn (select-keys result [:qualified? :inventory-count :failed]))
    (when-not (:qualified? result) (System/exit 1))))
