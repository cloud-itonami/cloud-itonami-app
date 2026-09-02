(ns cloud.itonami.app.media-tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.media-tools :as media-tools]
            [cloud.itonami.app.policy :as policy]))

(defn- error-data [f]
  (ex-data (try (f) (catch Exception error error))))

(defn- shipped-config []
  (config/load-config))

(deftest the-tools-are-the-two-and-only-generate-writes
  (is (= #{"video_generate" "video_status"} media-tools/tool-names))
  (is (media-tools/write-tool? "video_generate"))
  (is (not (media-tools/write-tool? "video_status")))
  (is (not (media-tools/tool? "disk_space_status"))))

(deftest a-video-cannot-leave-on-a-provider-chat-cannot-leave-on
  (testing "the shipped distribution admits nothing that leaves the machine"
    (let [{:keys [allowed? blocking]} (media-tools/admission (shipped-config))]
      (is (false? allowed?))
      ;; The blockers are the provider policy's own names, so a person reads
      ;; the same reason in the Bot picker and in the tool refusal.
      (is (some #{:unreviewed :cloud-egress-disabled :credential-missing} blocking)
          (pr-str blocking))))
  (testing "and a call refuses with those names rather than reaching the network"
    (is (= :media/provider-not-admitted
           (:type (error-data #(media-tools/call-tool! (shipped-config) "video_generate"
                                                       {:prompt "a cat"})))))))

(deftest a-reviewed-provider-without-a-video-endpoint-is-not-admitted-either
  (let [cfg (-> (shipped-config)
                (assoc-in [:routing :cloud-enabled?] true)
                (update :providers
                        (fn [ps] (mapv #(if (= "murakumo" (:id %))
                                          (-> % (assoc :enabled? true :reviewed? true)
                                              (dissoc :media))
                                          %)
                                       ps))))]
    (with-redefs [policy/credentialed? (constantly true)]
      (let [{:keys [allowed? blocking]} (media-tools/admission cfg)]
        (is (false? allowed?))
        (is (= [:media-endpoint-missing] blocking))))))

(deftest the-shipped-endpoint-is-murakumo-over-tls
  (let [p (media-tools/provider (shipped-config))]
    (is (= "https://murakumo.cloud/api/v1/videos" (get-in p [:media :videos-url])))
    (is (= "awai-network/hokusai" (get-in p [:media :video-model])))))

(deftest results-name-the-model-and-how-to-continue
  (is (= {:id "j1" :status "queued" :status-url nil
          :model "awai-network/hokusai" :poll-with "video_status"}
         (media-tools/generate-result {:id "j1" :status "queued"})))
  (is (= :media/no-job-id
         (:type (error-data #(media-tools/generate-result {:status "queued"})))))
  (testing "a completed job without a content url is given the derived one"
    (is (= "https://murakumo.cloud/api/v1/videos/j1/content"
           (:content-url (media-tools/status-result
                          "https://murakumo.cloud/api/v1/videos" "j1"
                          {:id "j1" :status "completed"}))))))
