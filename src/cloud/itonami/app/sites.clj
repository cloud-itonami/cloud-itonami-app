(ns cloud.itonami.app.sites
  "Project-scoped static sites.

  Editing and publishing are separate state transitions. Published HTML is
  served with a CSP sandbox by the HTTP adapter, so authored markup cannot
  inherit the authenticated application's authority."
  (:require [clojure.string :as str]
            [cloud.itonami.app.store :as store])
  (:import [java.util UUID]))

(def schema "cloud.itonami.app.sites.v1")
(def maximum-html-bytes (* 256 1024))

(defn- fail! [type message]
  (throw (ex-info message {:type type})))

(defn- clean-project [value]
  (or (not-empty (str/trim (str value)))
      (fail! :site/project-required "Projectを選択してください。")))

(defn- clean-title [value]
  (let [title (not-empty (str/trim (str value)))]
    (when-not title (fail! :site/title-required "Site名を入力してください。"))
    (when (> (count title) 120)
      (fail! :site/title-too-long "Site名は120文字以内にしてください。"))
    title))

(defn- clean-slug [value]
  (let [slug (-> (str value) str/trim str/lower-case)]
    (when-not (re-matches #"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?" slug)
      (fail! :site/invalid-slug "slugは英小文字・数字・ハイフンで指定してください。"))
    slug))

(defn- escape-html [value]
  (str/escape (str value) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- starter-html [title]
  (let [title (escape-html title)]
    (str "<!doctype html>\n<html lang=\"ja\">\n<head>\n"
         "  <meta charset=\"utf-8\">\n"
         "  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
         "  <title>" title "</title>\n"
         "  <style>body{font-family:system-ui,sans-serif;margin:0;background:#f5f7fb;color:#172033}"
         "main{max-width:760px;margin:12vh auto;padding:48px;background:white;border-radius:24px;box-shadow:0 20px 60px #14213d18}"
         "h1{font-size:clamp(2rem,6vw,4rem);margin:0 0 16px}p{font-size:1.15rem;line-height:1.8}</style>\n"
         "</head>\n<body><main><h1>" title "</h1><p>Cloud Itonami Sitesで公開しました。</p></main></body>\n</html>")))

(defn- site-path [organization-id project-id site-id]
  [:sites organization-id project-id site-id])

(defn- public-site [site]
  (select-keys site [:id :project-id :title :slug :status :created-at :updated-at
                     :published-at :url]))

(defn list-sites [organization-id project-id]
  (let [project-id (clean-project project-id)]
    {:schema schema
     :project-id project-id
     :items (->> (vals (get-in (store/snapshot)
                               [:sites organization-id project-id] {}))
                 (map public-site)
                 (sort-by (juxt :title :id))
                 vec)}))

(defn create! [organization-id user-id {:keys [project title slug]}]
  (let [project-id (clean-project project)
        title (clean-title title)
        slug (clean-slug slug)]
    (locking store/state
      (let [state (store/snapshot)
            existing (vals (get-in state [:sites organization-id project-id] {}))]
        (when-not (get-in state [:chat-projects [organization-id project-id]])
          (fail! :site/project-not-found "Projectが見つかりません。"))
        (when (some #(= slug (:slug %)) existing)
          (fail! :site/slug-conflict "このProjectでは同じslugが使われています。"))
        (let [id (str "site-" (UUID/randomUUID))
              now (store/now)
              site {:id id :organization-id organization-id :project-id project-id
                    :title title :slug slug :html (starter-html title)
                    :status "draft" :created-by user-id :updated-by user-id
                    :created-at now :updated-at now :url (str "/s/" id)}]
          (store/transact! assoc-in (site-path organization-id project-id id) site)
          (public-site site))))))

(defn site! [organization-id project-id site-id]
  (or (get-in (store/snapshot)
              (site-path organization-id (clean-project project-id) site-id))
      (fail! :site/not-found "Siteが見つかりません。")))

(defn detail [organization-id project-id site-id]
  (select-keys (site! organization-id project-id site-id)
               [:id :project-id :title :slug :html :status :created-at :updated-at
                :published-at :url]))

(defn update! [organization-id user-id site-id {:keys [project title html]}]
  (let [project-id (clean-project project)
        current (site! organization-id project-id site-id)
        title (if (some? title) (clean-title title) (:title current))
        html (if (some? html) (str html) (:html current))]
    (when (> (alength (.getBytes html java.nio.charset.StandardCharsets/UTF_8))
             maximum-html-bytes)
      (fail! :site/html-too-large "HTMLは256KB以内にしてください。"))
    (let [updated (assoc current :title title :html html :status "draft"
                         :updated-by user-id :updated-at (store/now))]
      (store/transact! assoc-in (site-path organization-id project-id site-id) updated)
      (detail organization-id project-id site-id))))

(defn publish! [organization-id user-id project-id site-id]
  (let [current (site! organization-id project-id site-id)
        now (store/now)
        published (assoc current :status "published" :published-at now
                         :updated-at now :updated-by user-id)]
    (store/transact! assoc-in
                     (site-path organization-id (:project-id current) site-id)
                     published)
    (public-site published)))

(defn published [site-id]
  (some (fn [[_ projects]]
          (some (fn [[_ sites]]
                  (let [site (get sites site-id)]
                    (when (= "published" (:status site)) site)))
                projects))
        (:sites (store/snapshot))))
