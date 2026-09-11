(ns cloud.itonami.app.repository-profile-ci
  "Cross-repository ADR-0013 profile gate for GitHub Actions. The checked-out
  app profile is read locally; every other inventory entry is read from that
  repository's `main` through GitHub's contents API."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [promesa.core :as p]
            [cloud.itonami.app.http-client-async :as http]
            [cloud.itonami.app.repository-qualification :as qualification])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def ^:private current-repository "cloud-itonami/cloud-itonami-app")
(def ^:private max-profile-bytes (* 64 1024))

(defn- github-profile [_client token repository]
  (let [headers (cond-> {"Accept" "application/vnd.github+json"
                         "X-GitHub-Api-Version" "2022-11-28"
                         "User-Agent" "cloud-itonami-repository-profile-ci"}
                  (seq token) (assoc "Authorization" (str "Bearer " token)))
        headers headers]
    (p/let [response (http/request
                      {:url (str "https://api.github.com/repos/" repository
                                 "/contents/storage-profile.edn?ref=main")
                       :method :get
                       :headers headers})]
      (let [status (:status response)]
        (when-not (= 200 status)
          (throw (ex-info "GitHub repository profile request failed"
                          {:type :repository-storage/profile-fetch-failed
                           :repository repository :status status})))
        (let [{:keys [encoding content]} (json/read-str (:body response)
                                                        :key-fn keyword)
              _ (when-not (= "base64" encoding)
                  (throw (ex-info "GitHub repository profile encoding denied"
                                  {:type :repository-storage/profile-encoding})))
              bytes (.decode (Base64/getMimeDecoder) ^String content)]
          (when (> (alength bytes) max-profile-bytes)
            (throw (ex-info "GitHub repository profile exceeds size limit"
                            {:type :repository-storage/profile-too-large})))
          (String. bytes StandardCharsets/UTF_8))))))

(def ^:dynamic *fetch-profile* github-profile)

(defn audit!
  "Audit every inventory entry. Returns a PROMISE of the audit result.

  Asynchronous because `*fetch-profile*` is: the app is moving its outbound
  HTTP to one shape that exists on both the JVM and ClojureScript, and only the
  asynchronous one does (ADR-0079; Node has no in-process synchronous HTTP).
  `-main` derefs, because a process boundary is where a promise stops being
  one."
  ([inventory-path] (audit! inventory-path (System/getenv "GITHUB_TOKEN")))
  ([inventory-path token]
   (let [inventory-file (.getCanonicalFile (io/file inventory-path))
         parent (.getParentFile inventory-file)
         entries (qualification/read-profile-inventory! inventory-file)
         client nil
         ;; `try/catch` could not stay: a rejected promise arrives after the
         ;; form it was created in has returned, so the catch would never run
         ;; and one unreachable repository would take the whole audit down
         ;; instead of being reported as unreadable. `p/catch` is the same
         ;; intent on a value that has not arrived yet.
         document
         (fn [{:keys [repository path]}]
           (-> (p/let [text (if (= current-repository repository)
                              (slurp (io/file parent path "storage-profile.edn"))
                              (*fetch-profile* client token repository))]
                 {:repository repository :profile-text text})
               (p/catch
                (fn [error]
                  ;; The type alone cannot tell a missing file from a 403 from a
                  ;; DNS failure, and this audit's whole output is which repos
                  ;; could not be read. Found by verify-error-provenance.
                  {:repository repository
                   :error (or (:type (ex-data error))
                              :repository-storage/profile-unavailable)
                   :error-message (some-> (ex-message error)
                                          str/split-lines first str/trim not-empty
                                          (as-> m (subs m 0 (min 300 (count m)))))}))))]
     (p/let [documents (p/all (mapv document entries))]
       (assoc (qualification/audit-profile-documents documents)
              :inventory-count (count entries))))))

(defn -main [& [inventory-path]]
  ;; The process boundary is where a promise stops being one. Blocking here is
  ;; correct and blocking anywhere above it is not -- this is the last frame
  ;; before the exit code.
  (let [result @(audit! (or inventory-path
                            "config/repository-storage-inventory.edn"))]
    (prn (select-keys result [:qualified? :inventory-count :failed]))
    (when-not (:qualified? result) (System/exit 1))))
