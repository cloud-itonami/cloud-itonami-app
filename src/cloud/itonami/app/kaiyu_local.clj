(ns cloud.itonami.app.kaiyu-local
  "回遊 for a local-first workspace — and the two ways that is NOT the same
  thing as 回遊 for a website.

  The public sites (shinshi.club / babiniku.net / kotobase.net) measure
  visitors to answer a business question: which surface is read, and where do
  people go next. Here the only 『visitor』 is the person who owns the machine.
  Two consequences follow, and both are enforced rather than documented:

  1. **Nothing is transmitted.** These counters live in this app's own local
     state file and have no writer to any network surface. There is no
     `fetch`, no beacon, no ingress key, and no report endpoint reachable from
     off-host — the loopback surface is the only reader. An analytics
     integration that phoned home from a local-first workspace would be the
     product contradicting its own first sentence.

  2. **It is a feature for the owner, not a metric about them.** So the shape
     is the one a person can use to answer 『自分は何に時間を使っているのか』:
     coarse buckets, no timestamps beyond the UTC day, no ordering that
     reconstructs a session.

  What it shares with the sites is the VOCABULARY (`kotoba-lang/kaiyu`):
  identical dwell buckets, the same whitelist-not-sanitizer rule, the same
  inclusive window. That is the whole reason to use the library here — not to
  compare this workspace with a website, but so that one person reading both
  does not have to hold two meanings of『10 秒未満』in their head.

  The route vocabulary is this app's own surface list. A path not named
  collapses to `other`, exactly as on the sites, which is what keeps a document
  title or a search term from ever reaching the counters."
  (:require [clojure.string :as str]
            [kaiyu.core :as kaiyu]
            [cloud.itonami.app.store :as store]))

(def route-vocabulary
  "The app's own loopback surfaces. Deliberately short: this is the list of
  places a person navigates between, not every path the server answers. An
  unnamed path is `other` — coarser and still correct."
  #{"home" "chat" "mail" "projects" "drive" "calendar" "approvals"
    "connections" "identity" "settings" "work" "business" "docs"})

(def state-key :kaiyu)

(defn route-of
  "First path segment, bounded to the vocabulary."
  [path]
  (kaiyu/normalize-route route-vocabulary
                         (first (remove str/blank? (str/split (or path "/") #"/")))))

(defn- utc-day [] (subs (str (java.time.Instant/now)) 0 10))

(defn record-view
  "Pure: fold one view of `route` on `day` into `kaiyu` state.

  Counters only — a count per (day, route). No timestamp, no order, no
  duration. Reconstructing『どの順で見たか』from this is not merely
  unimplemented, it is not representable, which is the point."
  [kaiyu-state day route]
  (-> (or kaiyu-state {:since day :views {}})
      (update :since #(if (and % (neg? (compare % day))) % day))
      (update-in [:views day route] (fnil inc 0))))

(defn record-view!
  "Fire-and-forget. Never throws into a request: a broken counter must not
  break the page, which is the same rule the sites' beacons follow."
  [path]
  (try
    (let [day (utc-day)
          route (route-of path)]
      (store/transact! (fn [state] (update state state-key record-view day route)))
      nil)
    (catch Throwable _ nil)))

(defn report
  "A window over the local counters, in the same shape the sites' read faces
  produce — so `kaiyu.diagnose` could read it unchanged if a person ever wants
  the same questions asked about their own workspace.

  `:collected-since` is carried for the same reason it is everywhere else: an
  empty window means『まだ測っていない』or『使っていない』, and a report that
  cannot tell them apart is not a report."
  ([] (report (store/snapshot) {}))
  ([state opts]
   (let [{:keys [since views]} (get state state-key)
         win (kaiyu/window (utc-day) opts)
         in-window (filter (fn [[day _]] (and (<= (compare (:from win) day) 0)
                                              (<= (compare day (:to win)) 0)))
                           views)
         totals (reduce (fn [acc [_ routes]]
                          (reduce (fn [a [route n]] (update a route (fnil + 0) n)) acc routes))
                        {} in-window)
         rows (->> totals (sort-by (comp - val)) (mapv (fn [[route n]] {:route route :count n})))]
     {:window win
      :views (kaiyu/section win rows since)
      ;; Named absences, not silent ones: this surface measures neither, and a
      ;; reader deserves to know that rather than infer it from a missing key.
      :dwell (kaiyu/section win [] nil)
      :transitions (kaiyu/section win [] nil)
      :local-only true})))
