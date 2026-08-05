(ns cloud.itonami.app.mail-gmail-test
  "Reading more of an inbox than its first page.

  `full-sync!` asked Gmail for threads once and used what came back. Gmail
  answers at most 500 and tells you there is more with a `nextPageToken`, which
  nothing read — so a full sync of a large inbox took the newest hundred,
  reported success, and said nothing about the rest. That is the failure these
  pin: not an error, a silent ceiling."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.mail-gmail :as gmail]
            [gmail.threads :as threads]))

(def ^:private list-inbox-threads
  #'cloud.itonami.app.mail-gmail/list-inbox-threads)

(defn- paged
  "A Gmail `threads.list` that answers from `pages`, recording each call."
  [pages calls]
  (fn [options]
    (swap! calls conj (select-keys options [:max-results :page-token :q]))
    (let [index (count (filter :page-token @calls))
          index (if (:page-token options) index 0)]
      (nth pages (min index (dec (count pages)))))))

(deftest pagination-is-followed-until-the-budget-is-met
  (let [calls (atom [])
        pages [{:threads (mapv (fn [i] {:id (str "a" i)}) (range 500))
                :nextPageToken "p2"}
               {:threads (mapv (fn [i] {:id (str "b" i)}) (range 500))
                :nextPageToken "p3"}
               {:threads (mapv (fn [i] {:id (str "c" i)}) (range 500))
                :nextPageToken "p4"}]]
    (with-redefs [threads/list-threads (paged pages calls)]
      (let [found (list-inbox-threads "token" 1000)]
        (is (= 1000 (count found))
            "a budget of 1000 must actually reach 1000, not stop at one page")
        (testing "and the last call asks only for what is still missing, rather
                  than fetching 500 to use none of it"
          (is (= [500 500] (map :max-results @calls))))))))

(deftest a-page-cap-of-500-is-respected
  (let [calls (atom [])]
    (with-redefs [threads/list-threads
                  (paged [{:threads [{:id "a"}] :nextPageToken nil}] calls)]
      (list-inbox-threads "token" 5000)
      (is (= 500 (:max-results (first @calls)))
          "Gmail refuses more than 500 per call, so asking for the whole budget
           at once fails the request rather than returning it"))))

(deftest no-token-ends-the-walk
  (let [calls (atom [])]
    (with-redefs [threads/list-threads
                  (paged [{:threads [{:id "a"} {:id "b"}] :nextPageToken nil}]
                         calls)]
      (is (= 2 (count (list-inbox-threads "token" 1000))))
      (is (= 1 (count @calls))
          "one page with no continuation is the whole inbox"))))

(deftest an-empty-page-ends-the-walk-even-with-a-token
  (testing "a token that keeps coming back with nothing would loop forever, and
            the loop would look like a slow sync rather than a bug"
    (let [calls (atom [])]
      (with-redefs [threads/list-threads
                    (fn [options]
                      (swap! calls conj options)
                      {:threads [] :nextPageToken "always-more"})]
        (is (empty? (list-inbox-threads "token" 1000)))
        (is (= 1 (count @calls)))))))

(deftest the-query-stays-the-inbox-on-every-page
  (let [calls (atom [])
        pages [{:threads [{:id "a"}] :nextPageToken "p2"}
               {:threads [{:id "b"}] :nextPageToken nil}]]
    (with-redefs [threads/list-threads (paged pages calls)]
      (list-inbox-threads "token" 1000)
      (is (every? #(= "in:inbox" (:q %)) @calls)
          "dropping the query on the second page would start pulling archived
           mail into the inbox projection"))))
