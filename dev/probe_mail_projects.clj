(ns probe-mail-projects
  "End to end on real mail: sync jun784, make projects, file it, report the gap.

  Read-only against Gmail — it fetches and files locally. Nothing is sent,
  nothing is labelled provider-side, nothing is deleted."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.mail-account :as account]
            [cloud.itonami.app.mail-projects :as mail-projects]
            [cloud.itonami.app.mail-sync :as mail-sync]
            [cloud.itonami.app.project-repository :as projects]
            [cloud.itonami.app.store :as store]))

(def organization "org-probe")
(def user "user-probe")

(defn- git [directory & args]
  (let [builder (doto (ProcessBuilder. ^java.util.List (into ["/usr/bin/git"] args))
                  (.directory directory))
        process (.start builder)
        output (slurp (.getInputStream process))]
    (.waitFor process)
    output))

(defn- scope [project-id]
  {:organization-id organization :user-id user :project-id project-id})

(defn -main [& _]
  (let [configuration (config/load-config)]
    (account/configure! (:mail-sync configuration))
    (println "== 1. sync jun784 ==")
    (let [accounts (account/accounts nil)]
      (doseq [a accounts]
        (println "  " (:id a) "->" (dissoc (mail-sync/sync-account! a) :cursor))))
    (println "  messages in store:"
             (count (get-in (store/snapshot) [:mail :messages])))

    (println "\n== 2. create local projects ==")
    (doseq [id ["finance" "travel" "shopping"]]
      (let [p (projects/create-project! (scope id) {:title id})]
        (println "  " (:project-id p) "git:" (:git-initialized? p)
                 "slug:" (:project-slug p))))

    (println "\n== 3. rules ==")
    (doseq [r [{:project "finance" :match {:label "finance"}}
               {:project "finance" :match {:from-domain "rakuten-bank.co.jp"}}
               {:project "travel" :match {:from-domain "jal.com"}}
               {:project "shopping" :match {:from-domain "tiktok.com"}}]]
      (let [added (mail-projects/add-rule! organization r)]
        (println "  " (:rule/project (:rule added))
                 (:rule/match (:rule added)))))

    (println "\n== 4. a rule that names a project we do not have ==")
    (try (mail-projects/add-rule! organization
                                  {:project "nonexistent"
                                   :match {:from-domain "x.com"}})
         (println "   UNEXPECTED: accepted")
         (catch clojure.lang.ExceptionInfo e
           (println "   refused:" (ex-message e))))

    (println "\n== 5. apply ==")
    (println "  " (mail-projects/apply-rules! organization))

    (println "\n== 6. overview ==")
    (let [o (mail-projects/overview organization)]
      (println "   messages:" (:messages o)
               " assigned:" (:assigned o)
               " unassigned:" (:unassigned o))
      (doseq [p (:projects o)] (println "   " p)))

    (println "\n== 7. what landed in finance ==")
    (doseq [m (take 4 (:items (mail-projects/project-mail organization "finance")))]
      (println "   " (:received-at m) "|"
               (subs (str (:subject m)) 0 (min 46 (count (str (:subject m)))))))

    (println "\n== 8. the pile no rule caught ==")
    (let [u (mail-projects/unassigned organization)]
      (println "   count:" (:count u))
      (doseq [s (take 6 (:senders u))] (println "   " s)))

    (println "\n== 9. manual assignment survives a re-apply ==")
    (let [id (:id (first (:items (mail-projects/unassigned organization))))]
      (mail-projects/assign! organization id "travel" user)
      (mail-projects/apply-rules! organization user)
      (println "   " id "->"
               (get (mail-projects/assignments organization) id)))

    (println "\n== 10. the artifacts in the project repositories ==")
    (doseq [id ["finance" "travel" "shopping"]]
      (let [directory (->> (file-seq (io/file (config/data-dir) "projects"))
                           (filter #(and (.isDirectory %) (= id (.getName %))))
                           first)]
        (when directory
          (println "  " id)
          (println "     commits :"
                   (str/trim (first (str/split-lines
                                     (git directory "log" "--oneline")))))
          (println "     tracked :"
                   (count (remove str/blank?
                                  (str/split-lines
                                   (git directory "ls-files" "mail")))) "envelopes")
          (println "     ignored :"
                   (count (filter #(.isFile %)
                                  (file-seq (io/file directory ".mail")))) "bodies")
          (println "     body in git? :"
                   (if (str/blank? (git directory "grep" "-r" "本文" "HEAD"))
                     "no" "YES — LEAK")))))

    (println "\n== 11. one envelope, as committed ==")
    (let [directory (->> (file-seq (io/file (config/data-dir) "projects"))
                         (filter #(and (.isDirectory %) (= "finance" (.getName %))))
                         first)
          envelope (->> (file-seq (io/file directory "mail"))
                        (filter #(str/ends-with? (.getName %) ".edn"))
                        first)]
      (when envelope
        (let [written (read-string (slurp envelope))]
          (doseq [k [:mail/from-email :mail/subject :mail/received-at
                     :mail/labels :mail/body-sha256 :mail/body-bytes
                     :filed/project :filed/by :filed/rule]]
            (println "    " k (get written k)))))))
  (shutdown-agents))
