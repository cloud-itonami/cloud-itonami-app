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
            [shadow.resource :as resource]
            [cloud.itonami.app.commands :as commands]
            [cloud.itonami.mobile.terminal :as terminal]
            [cloud.itonami.mobile.view :as view]))

;; Compiled in, not read from the page: the deployed edge and `wrangler dev`
;; are two builds of one source, and a base URL written into a function would
;; make them two sources.
(goog-define api-base "https://cloud-itonami-app-edge.04-feasts-minded.workers.dev")

;; The ingress the COMMAND surface talks to, which is not the edge.
;;
;; The edge (`api-base`) serves the fleet directory and nothing else; the
;; commands go to the itonami resident through its own ingress. Empty by
;; default and on purpose: `agent.itonami.cloud` was NXDOMAIN when this landed
;; (measured 2026-09-06), and compiling in a hostname that does not resolve
;; would make an unreachable ingress look like a configured one. An empty base
;; is a state the screen can describe; a wrong base is one it cannot.
(goog-define agent-base "")

;; The two tables, read from the classpath AT COMPILE TIME.
;;
;; `commands.cljc` keeps `io/resource` for the JVM and takes the text directly
;; on a host that has no classpath -- `bin/itonami` does exactly this, and so
;; does this bundle. Inlining rather than fetching is the difference between an
;; app that can tell you what commands exist while offline and one that cannot.
(defonce ^:private tables-installed
  (do (commands/install-sources!
       {commands/resource-name (resource/inline "cloud-itonami-app.commands.edn")
        commands/alias-resource-name (resource/inline "cloud-itonami-app.cli-aliases.edn")})
      true))

;; ---------------------------------------------------------------------------
;; where this device sends, and what it holds
;; ---------------------------------------------------------------------------

(def ^:private storage-key "cloud.itonami.mobile.pairing.v1")

(defn- read-pairing
  "The base and token this device holds, or empty.

  Every access is guarded. `localStorage` throws outright in some contexts
  (private windows, a WebView with site data blocked), and a screen that cannot
  render because it could not read a convenience value is worse than one that
  renders unpaired."
  []
  (try
    (let [raw (.getItem js/localStorage storage-key)]
      (if (str/blank? raw)
        {}
        (js->clj (js/JSON.parse raw) :keywordize-keys true)))
    (catch :default _ {})))

(defn- write-pairing! [m]
  (try (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js m)))
       (catch :default _ nil)))

(def ^:private page-limit
  "How many actors one screen asks for. The edge caps at 200; a phone list of
  1,215 cards is not a screen, so this asks for a page and the view says how
  many it is not showing."
  50)

(defonce ^:private state
  ;; `:query` is the search field. `:applied-query` is what the results on
  ;; screen came from. They differ while someone is typing, and the view must
  ;; describe results with the second one.
  (r/atom (let [{:keys [base token]} (read-pairing)]
            {:phase :loading :query "" :applied-query nil
             :actors nil :matched nil :total nil :shown nil :error nil
             ;; the command surface
             :pane :fleet
             :line "" :transcript [] :busy? false
             :base (or base (not-empty agent-base))
             :token token
             :base-draft (or base (not-empty agent-base) "")
             :token-draft ""
             :paired? (boolean (and (not (str/blank? (or base agent-base)))
                                    (not (str/blank? token))))})))

;; How many reads have been started. The last one started is the only one whose
;; answer may be shown.
;;
;; Without it the app applies whichever response arrives, and responses do not
;; arrive in the order they were asked for: two searches in quick succession on
;; a slow network can land newest-first, leaving the older answer on screen.
;;
;; This one is a precaution and not a measurement — the overtaking above has
;; not been observed here. The sentence that WAS measured, and that this does
;; not fix, was the view describing an old result set with the query someone
;; was still typing; `:applied-query` fixes that.
;;
;; (`defonce` takes no docstring in ClojureScript, which is why this is a
;; comment.)
(defonce ^:private issued (atom 0))

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
  (let [unfiltered? (str/blank? query)
        token (swap! issued inc)
        current? #(= token @issued)]
    ;; `:query` is not written here: the field owns it, and a read in flight
    ;; must not be able to change what someone is typing.
    (swap! state assoc :phase :loading :error nil)
    (-> (js/fetch (search-url query))
        (.then (fn [^js res]
                 (if (.-ok res)
                   (.json res)
                   (throw (ex-info "http" {:kind :http :detail (.-status res)})))))
        (.then (fn [body]
                 (let [{:keys [actors matched]} (js->clj body :keywordize-keys true)]
                   (cond
                     ;; A read that has been superseded is dropped whole. Not
                     ;; even its error is shown: it is an answer to a question
                     ;; the screen is no longer asking.
                     (not (current?)) nil

                     (and (vector? actors) (number? matched))
                     (swap! state
                            (fn [s]
                              (cond-> (assoc s :phase :ready
                                             :actors actors
                                             :matched matched
                                             :applied-query query
                                             :shown (count actors)
                                             :error nil)
                                unfiltered? (assoc :total matched))))

                     :else (throw (ex-info "shape" {:kind :shape}))))))
        (.catch (fn [e]
                  (when (current?)
                    (let [kind (or (:kind (ex-data e)) :network)
                          detail (or (:detail (ex-data e)) (.-message e))]
                      (swap! state assoc :phase :error
                             :error (failure kind detail)))))))))

(defn- transcribe! [e]
  (swap! state update :transcript terminal/append e))

(defn- send!
  "Issue one planned request and transcribe the answer.

  The three failures stay three. A non-2xx is the server answering; an
  unreachable ingress is not an answer at all; and a body that does not parse
  is a third thing again. `bin/itonami` keeps 1 and 2 apart for this reason and
  the screen keeps them apart the same way."
  [{:keys [method path body]} label]
  (let [{:keys [base token]} @state
        url (str base path)]
    (swap! state assoc :busy? true)
    (-> (js/fetch url
                  (clj->js (cond-> {:method (str/upper-case (name method))
                                    :headers (cond-> {"accept" "application/json"}
                                               token (assoc "authorization"
                                                            (str "Bearer " token))
                                               body (assoc "content-type"
                                                           "application/json"))}
                             body (assoc :body (js/JSON.stringify (clj->js body))))))
        (.then (fn [^js res]
                 (-> (.text res)
                     (.then (fn [text]
                              (transcribe!
                               (terminal/entry :answered
                                               (if (str/blank? text) "(空の応答)" text)
                                               {:status (.-status res)
                                                :label label})))))))
        (.catch (fn [e]
                  (transcribe!
                   (terminal/entry :failed
                                   (str url " に届きませんでした（"
                                        (or (.-message e) e) "）。")
                                   {:label label}))))
        (.finally (fn [] (swap! state assoc :busy? false))))))

(defn- run-line!
  "Plan a typed line and act on the plan.

  Nothing is decided here: `terminal/plan` returns which of the four outcomes
  this line is, and each one has exactly one thing that happens next."
  [line]
  (let [{:keys [outcome message suggestions request label] :as planned}
        (terminal/plan line)]
    (when-not (= :empty outcome)
      (transcribe! (terminal/entry :sent line))
      (swap! state assoc :line "")
      (case outcome
        :request (if (:paired? @state)
                   (send! request label)
                   (transcribe!
                    (terminal/entry
                     :refused
                     (str "`" label "` は組み立てられましたが、この端末はまだ"
                          "対になっていないので送っていません。")
                     {:reason :not-paired})))
        :refused (transcribe! (terminal/entry :refused message
                                              (select-keys planned [:reason])))
        :unavailable (transcribe! (terminal/entry :unavailable message
                                                  {:suggestions suggestions}))
        nil))))

(defn- root []
  (view/screen
   @state
   {:on-pane (fn [p] (swap! state assoc :pane p))
    :on-line (fn [^js e] (swap! state assoc :line (.. e -target -value)))
    :on-submit (fn [^js e]
                 (when (or (nil? (.-key e)) (= "Enter" (.-key e)))
                   (.preventDefault e)
                   (when-not (:busy? @state)
                     (run-line! (:line @state)))))
    :on-base (fn [^js e] (swap! state assoc :base-draft (.. e -target -value)))
    :on-token (fn [^js e] (swap! state assoc :token-draft (.. e -target -value)))
    :on-pairing (fn [base token]
                  (let [m {:base (str/trim (or base "")) :token (str/trim (or token ""))}]
                    (write-pairing! m)
                    (swap! state merge m
                           {:paired? (boolean (and (seq (:base m)) (seq (:token m))))})))
    :on-query (fn [^js e] (swap! state assoc :query (.. e -target -value)))
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
