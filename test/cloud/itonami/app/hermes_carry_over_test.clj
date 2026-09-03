(ns cloud.itonami.app.hermes-carry-over-test
  "Permission carry-over (owner instruction, 2026-09-03).

  The source permission state is OBSERVED, not assumed: measured 2026-09-03,
  Hermes ships every built-in toolset enabled by default and records only the
  dangerous-pattern approvals a person granted permanently in
  `config.yaml` (`command_allowlist`). These tests pin what that evidence
  becomes in the destination, what it never becomes, and that the inert
  default survives untouched."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.hermes-migration :as migration])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "hermes-carry-over"
                                      (into-array FileAttribute []))))

(defn- write-config! [^File root content]
  (spit (io/file root "config.yaml") content)
  root)

(def ^:private itonami-profile-config
  "The real itonami profile's permission-bearing keys, 2026-09-03."
  "model:\n  provider: openrouter-free\n  default: z-ai/glm-5.3-flash\ncommand_allowlist:\n  - script execution via -e/-c flag\n  - recursive delete\nplugins:\n  enabled: []\n")

(def ^:private codinator-profile-config
  "The real codinator profile's permission-bearing keys, 2026-09-03."
  "model:\n  provider: openrouter-free\ncommand_allowlist:\n  - overwrite project env/config file\nplugins:\n  enabled: []\n")

(def ^:private bare-profile-config
  "Most profiles carry no recorded approvals."
  "model:\n  provider: openrouter-free\n  default: z-ai/glm-5.3-flash\nplugins:\n  enabled: []\n")

(deftest observed-permissions-read-the-real-config-shapes
  (testing "an allowlist-bearing profile records its entries as evidence"
    (let [root (write-config! (temp-dir) itonami-profile-config)
          observed (#'migration/observed-permissions root)]
      (is (= [{:kind "command-allowlist"
               :entries ["script execution via -e/-c flag" "recursive delete"]}]
             (mapv #(dissoc % :note) observed))
          "the entries cross as evidence with their real pattern names")))
  (testing "codinator's single entry reads the same way"
    (let [root (write-config! (temp-dir) codinator-profile-config)
          observed (#'migration/observed-permissions root)]
      (is (= ["overwrite project env/config file"]
             (-> observed first :entries)))))
  (testing "a profile with no approvals observes nothing"
    (let [root (write-config! (temp-dir) bare-profile-config)]
      (is (empty? (#'migration/observed-permissions root)))))
  (testing "no config file observes nothing"
    (is (nil? (#'migration/observed-permissions (temp-dir))))))

(deftest preview-records-observed-permissions-per-profile
  (let [home (temp-dir)
        _ (write-config! home itonami-profile-config)
        manifest (migration/preview {:home home
                                     :migration-id "hermes-carry-preview"})]
    (is (= ["script execution via -e/-c flag" "recursive delete"]
           (-> manifest :profiles first :observed-permissions
               first :entries))
        "the manifest carries the evidence the provision decision reads")))

(deftest carry-over-maps-evidence-to-grants-and-names-the-remainder
  (testing "an allowlist-bearing profile gets the tool family, never omakase"
    (let [{:keys [grants evidence unmapped]}
          (migration/carry-over-grants
           {:observed-permissions
            [{:kind "command-allowlist"
              :entries ["script execution via -e/-c flag" "recursive delete"]}]})]
      (is (= {:writes? true :coding? true :virtual-shell? true :goal? true
              :browser? true :computer? true}
             grants)
          "terminal evidence grants the bounded command family; source toolsets ship enabled so browser/computer cross too")
      (is (= {:command-allowlist ["script execution via -e/-c flag"
                                  "recursive delete"]}
             evidence))
      (is (= ["command_allowlist entries"
              "omakase / general approval"]
             (mapv :source unmapped))
          "the entries and the approval boundary are recorded as unmapped")))
  (testing "empty evidence yields empty grants"
    (let [{:keys [grants evidence unmapped]}
          (migration/carry-over-grants {:observed-permissions []})]
      (is (= {} grants))
      (is (= {} evidence))
      (is (empty? unmapped))
      "carry-over carries what was observed, never a default")))

(deftest provision-carries-grants-only-when-asked
  (let [home (temp-dir)
        _ (write-config! home itonami-profile-config)
        ;; stage! needs a hermes exporter binary to exist; the exports are
        ;; redefined below, so any executable file satisfies the check.
        executable (io/file home "hermes-agent" "venv" "bin" "hermes")
        _ (.mkdirs (.getParentFile executable))
        _ (spit executable "#!/bin/sh\nexit 0\n")
        _ (.setExecutable executable true)
        data-dir (temp-dir)
        preview (migration/preview {:home home
                                    :migration-id "hermes-carry-provision"})]
    (binding [migration/*export-profile!*
              (fn [_ _ profile-id output] (spit output "portable"))
              migration/*write-runtime-context!*
              (fn [_ _ output] (spit output "context"))
              migration/*export-sessions!*
              (fn [_ _ output]
                (spit output
                      (str "{\"id\":\"s1\",\"messages\":["
                           "{\"role\":\"user\",\"content\":\"hi\"}]}")))]
      (let [staged (migration/stage! {:home home :data-dir data-dir
                                      :manifest preview})
            requested (atom [])]
        (with-redefs [cloud.itonami.app.bots/create-hermes-import!
                      (fn [_ _ request] (swap! requested conj request)
                        {:id "bot-1"})]
          (testing "without the flag the Bot stays inert and the default holds"
            (let [result (migration/provision!
                          {:configuration
                           {:providers [{:id "murakumo" :models ["z-ai/glm-5.3-flash"]}]}
                           :session {:user-id "u" :organization-id "o"}
                           :data-dir data-dir :manifest staged})]
              (is (empty? (select-keys (first @requested)
                                       [:carry-over-grants])))
              (is (= "ready-inert" (-> result :profiles first :provision-state)))
              (is (false? (get-in result [:safety :grants-carried-over]))))
            (reset! requested []))
          (testing "with the flag the grants cross and the evidence is recorded"
            (let [result (migration/provision!
                          {:configuration
                           {:providers [{:id "murakumo" :models ["z-ai/glm-5.3-flash"]}]}
                           :session {:user-id "u" :organization-id "o"}
                           :data-dir data-dir :manifest staged
                           :carry-over-permissions true})
                  request (first @requested)
                  carry (:carry-over-grants request)]
              (is (every? true? (vals (select-keys carry
                                                   [:writes? :coding? :virtual-shell? :goal? :browser? :computer?])))
                  "every mapped grant is on")
              (is (nil? (some #{:omakase? :peers?} (keys carry)))
                  "omakase and peers are absent from the carried grants")
              (is (nil? (:omakase? carry)) "omakase is never in the carried grants")
              (is (= ["script execution via -e/-c flag" "recursive delete"]
                     (get-in carry [:source-permission-evidence :command-allowlist]))
                  "the source entries are recorded as evidence on the request")
              (is (= "ready-carry-over" (-> result :profiles first :provision-state)))
              (is (true? (get-in result [:safety :grants-carried-over])))
              (is (= "interactive-ready-source-tool-authority-carried-over"
                     (get-in result [:destination :activation]))))))))))
