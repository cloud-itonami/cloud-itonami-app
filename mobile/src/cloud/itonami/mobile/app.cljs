(ns cloud.itonami.mobile.app
  "The mobile app: mount, state, and the one network call.

  Everything the screen looks like is in `cloud.itonami.mobile.view`, which is
  pure. This namespace is the part that cannot be — the atom, `js/fetch`, and
  the DOM node — kept small enough to read in one sitting for that reason.

  The app runs inside a kotoba-shell WKWebView (iOS) or WebView (Android) at
  the app's own origin: `kotoba-webbundle://app` and
  `https://appassets.androidplatform.net` respectively. It is NOT the loopback
  JVM server the desktop app carries: there is no JVM on either platform, which
  is the whole reason the server moved to the edge (ADR-2608081500) and the
  whole reason this bundle exists (ADR-2608311000)."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom :as rdom]
            [cloud.itonami.mobile.view :as view]))

;; Compiled in, not read from the page: the deployed edge and `wrangler dev`
;; are two builds of one source, and a base URL written into a function would
;; make them two sources.
(goog-define api-base "https://cloud-itonami-app-edge.04-feasts-minded.workers.dev")

(def ^:private page-limit
  "How many actors one screen asks for. The edge caps at 200; a phone list of
  1,215 cards is not a screen, so this asks for a page and the view says how
  many it is not showing."
  50)

(defonce ^:private state
  (r/atom {:phase :loading :query "" :actors nil :matched nil :total nil
           :shown nil :error nil}))

(defn- search-url [query]
  (let [u (js/URL. "/api/fleet/search" api-base)]
    (.set (.-searchParams u) "limit" (str page-limit))
    (when-not (str/blank? query)
      (.set (.-searchParams u) "text" (str/trim query)))
    (.-href u)))

(defn- failure
  "A message that says which failure this is.

  A non-2xx answer and an unreachable host are different facts about the world
  and the screen shows which one it has. Collapsing them into 'error' is how a
  reachable API returning 500 and a phone with no network end up looking like
  the same problem to whoever is holding it."
  [kind detail]
  {:kind kind
   :message (case kind
              :http (str "目録が " detail " を返しました。")
              :network (str "目録に届きませんでした（" detail "）。"
                            "端末がネットワークに繋がっているか確認してください。")
              :shape "目録の応答を読めませんでした。")})

(defn- load!
  "Read the fleet for `query` and put the answer in the atom.

  `:total` is only written on an unfiltered read. A filtered read knows how
  many matched and nothing about how many exist, and writing `matched` into
  `total` would make the screen state a number it did not measure."
  [query]
  (let [unfiltered? (str/blank? query)]
    (swap! state assoc :phase :loading :query query :error nil)
    (-> (js/fetch (search-url query))
        (.then (fn [^js res]
                 (if (.-ok res)
                   (.json res)
                   (throw (ex-info "http" {:kind :http :detail (.-status res)})))))
        (.then (fn [body]
                 (let [{:keys [actors matched]} (js->clj body :keywordize-keys true)]
                   (if (and (vector? actors) (number? matched))
                     (swap! state
                            (fn [s]
                              (cond-> (assoc s :phase :ready
                                             :actors actors
                                             :matched matched
                                             :shown (count actors)
                                             :error nil)
                                unfiltered? (assoc :total matched))))
                     (throw (ex-info "shape" {:kind :shape}))))))
        (.catch (fn [e]
                  (let [kind (or (:kind (ex-data e)) :network)
                        detail (or (:detail (ex-data e)) (.-message e))]
                    (swap! state assoc :phase :error
                           :error (failure kind detail))))))))

(defn- root []
  (view/screen
   @state
   {:on-query (fn [^js e] (swap! state assoc :query (.. e -target -value)))
    :on-search (fn [^js e]
                 ;; One handler for the button's click and the keyboard's
                 ;; Enter. A key event that is not Enter is not a search.
                 (when (or (nil? (.-key e)) (= "Enter" (.-key e)))
                   (.preventDefault e)
                   (load! (:query @state))))
    :on-retry (fn [_] (load! (:query @state)))}))

(defn init []
  (rdom/render [root] (.getElementById js/document "app"))
  (load! ""))
