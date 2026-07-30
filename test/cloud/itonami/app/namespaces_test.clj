(ns cloud.itonami.app.namespaces-test
  "Every namespace under src/ must load.

  This gate exists because four of them do not, and nobody knew. They were found by
  accident while trying to write a test for one of them, and the reason they had gone
  unnoticed is instructive: nothing requires them, so no suite ever loaded them, and
  clj-kondo's phantom-var warnings -- which named the exact vars -- read as lint noise
  rather than as 'this file cannot compile'.

  A namespace that does not compile is worse than an untested one. An untested
  namespace might work; this kind cannot run at all, while still being shipped, counted
  in the repository's size, and read by people as if it were live code.

  THE EXCLUSION LIST IS PART OF THE TEST, IN BOTH DIRECTIONS. Each entry is asserted to
  still fail. If someone fixes or deletes one and leaves it listed here, this test says
  so -- because an exclusion list nobody prunes is how a gate like this quietly stops
  gating. Fixing a namespace should require deleting a line from this file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def known-broken
  "namespace -> why it does not compile, measured 2026-07-31.

  None of these is required by anything in src/; all four are unreachable as well as
  uncompilable. Whether each should be completed or deleted is a product-scope decision,
  which is why they are recorded here rather than repaired by writing the vars they
  happen to be missing."
  {'cloud.itonami.app.agent-control
   "calls store/update-agent-control! at four sites; cloud.itonami.app.store defines no such var"

   'cloud.itonami.app.account-link-sync
   "calls identity/wallet-links; the identity library defines no such var"

   'cloud.itonami.app.mail-sync
   "calls identity/provider-access-token!; the identity library defines no such var"

   'cloud.itonami.app.bitcoin-wallet
   "requires cloud.itonami.app.bitcoin, which is not in this repository"})

(defn- source-namespaces []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % ".clj"))
       (map #(-> % (str/replace #"^src/" "") (str/replace #"\.clj$" "")
                 (str/replace "_" "-") (str/replace "/" ".") symbol))
       sort))

(defn- loads? [n]
  (try (require n) true (catch Throwable _ false)))

;; ---------------------------------------------------------------------------

(deftest there-are-namespaces-to-check
  (testing "guards against the gate passing vacuously when run from a directory
            where src/ is not visible"
    (is (< 40 (count (source-namespaces))))))

(deftest every-namespace-loads-except-the-ones-named-here
  (let [broken (remove loads? (source-namespaces))
        unexpected (remove known-broken broken)]
    (is (empty? unexpected)
        (str "these namespaces do not compile and are not in known-broken: "
             (str/join ", " unexpected)
             ". A namespace that cannot load is shipped, counted and read as live code "
             "while being unable to run."))))

(deftest every-known-broken-namespace-is-still-broken
  (testing "an exclusion nobody prunes is how this gate stops gating: fixing a
            namespace must require deleting its line from known-broken"
    (doseq [[n why] known-broken]
      (is (not (loads? n))
          (str n " now loads. Remove it from known-broken -- its recorded reason was: "
               why)))))

(deftest nothing-in-src-requires-a-known-broken-namespace
  (testing "these compile-failures are invisible precisely because nothing pulls them
            in. If something starts requiring one, that requirer breaks too, and this
            says so before the suite fails somewhere less obvious."
    (let [sources (->> (file-seq (io/file "src"))
                       (filter #(.isFile ^java.io.File %))
                       (remove #(str/includes? (.getPath ^java.io.File %) "agent_control"))
                       (remove #(str/includes? (.getPath ^java.io.File %) "account_link_sync"))
                       (remove #(str/includes? (.getPath ^java.io.File %) "mail_sync"))
                       (remove #(str/includes? (.getPath ^java.io.File %) "bitcoin_wallet")))]
      (doseq [n (keys known-broken)
              ;; the :require form, not a mention in prose -- these namespaces are
              ;; discussed in comments in fleet.clj and mcp.clj, which is fine
              :let [needle (str "[" n " :as")]]
        (doseq [f sources]
          (is (not (str/includes? (slurp f) needle))
              (str (.getPath ^java.io.File f) " requires " n ", which does not compile")))))))
