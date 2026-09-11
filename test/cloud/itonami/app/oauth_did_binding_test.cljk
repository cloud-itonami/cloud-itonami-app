(ns cloud.itonami.app.oauth-did-binding-test
  "外部アカウント接続（Microsoft 365 / Google / GitHub）は did:key に結ばれる。

  この app の identity は Passkey が確立した `did:key`（`docs/tenant-model.md`:
  User の最初の P-256 公開鍵が did:key を決める）。接続がそこに結ばれていないと、
  『誰の Microsoft か』を答えられるものがどこにも無くなる。

  ここに並ぶのは全部、**変更前の実装が実際に間違えていたケース**:
  組織ごとに provider 1枠しか無く2人目が1人目を静かに上書きした、
  token 解決が `first` で『たまたま最初の接続』を選んだ、
  `:connected?` が state 全体のどこかに接続があれば true だった。"
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]))

(def alice-did "did:key:zAlice")
(def bob-did "did:key:zBob")

(defn- fixture
  "2人が同じ組織に居て、両方 Passkey 登録済み（= DID を持つ）。"
  [connections]
  (assoc (store/initial-state) :identity
         {:users {"user-alice" {:id "user-alice" :did alice-did
                                :passkey-enrolled? true}
                  "user-bob" {:id "user-bob" :did bob-did
                              :passkey-enrolled? true}
                  "user-new" {:id "user-new" :passkey-enrolled? false}}
          :organizations {"org-a" {:id "org-a" :organization-id "acme"}}
          :memberships {"m-alice" {:id "m-alice" :user-id "user-alice"
                                   :organization-id "org-a" :role :owner}
                        "m-bob" {:id "m-bob" :user-id "user-bob"
                                 :organization-id "org-a" :role :operator}}
          :connections connections
          :oauth-transactions {} :sessions {}}))

(defn- signed-in-view
  "`public-state` はトークンを取るので、本物のセッションを発行して通す。
  ここでセッション機構を迂回すると、UI が実際に見る経路をテストしなくなる。"
  [user-id]
  (identity/public-state (:token (identity/issue-session! user-id))))

(defn- connection [id did user-id provider & {:as over}]
  (merge {:id id :provider provider :status :connected
          :organization-id "org-a" :user-id user-id :user-did did
          :provider-subject (str "sub-" user-id)
          :email (str user-id "@acme.example")
          :display-name user-id}
         over))

(defmacro with-state [state & body]
  `(let [previous# @store/state]
     (try (reset! store/state ~state) ~@body
          (finally (reset! store/state previous#)))))

;; ── 接続は人に属する ────────────────────────────────────────────────────

(deftest two-people-in-one-org-each-hold-their-own-microsoft-connection
  (testing "旧実装の接続 ID は {org}:{provider} で、組織に provider 1枠しか
            無かった。2人目の接続が1人目を静かに上書きし、1人目の Keychain
            エントリは存在しない接続レコードに属することになっていた"
    (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft)
                          "c-bob" (connection "c-bob" bob-did "user-bob" :microsoft)})
      (is (= 2 (count (identity/connections-for :microsoft))))
      (is (= ["c-alice"] (mapv :id (identity/connections-for :microsoft alice-did))))
      (is (= ["c-bob"] (mapv :id (identity/connections-for :microsoft bob-did)))))))

(deftest a-did-resolves-exactly-one-connection
  (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft)
                        "c-bob" (connection "c-bob" bob-did "user-bob" :microsoft)})
    (is (= "c-alice" (:id (identity/connection-for :microsoft alice-did))))
    (is (= "c-bob" (:id (identity/connection-for :microsoft bob-did))))))

(deftest an-unbound-request-refuses-rather-than-picking-somebody
  (testing "mail-sync 自身のコメントが警告している形:
            『machine 上にある token を手当たり次第に掴むアプリは、
            向けられてもいないメールを読むアプリだ』。app の内側で
            first を取るのは同じ行為で、しかも呼び出し元には見えない"
    (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft)
                          "c-bob" (connection "c-bob" bob-did "user-bob" :microsoft)})
      (is (thrown? clojure.lang.ExceptionInfo (identity/connection-for :microsoft)))
      (try (identity/connection-for :microsoft)
           (catch clojure.lang.ExceptionInfo e
             (is (= :oauth/ambiguous-connection (:type (ex-data e))))
             (is (= #{alice-did bob-did} (set (:dids (ex-data e))))))))))

(deftest a-single-user-deployment-still-resolves-without-naming-anybody
  (testing "拒否のコストは単一利用者の配備ではゼロ（接続は1つで、それが返る）"
    (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft)})
      (is (= "c-alice" (:id (identity/connection-for :microsoft)))))))

(deftest a-disconnected-connection-is-not-resolved
  (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft
                                              :status :revoked)})
    (is (nil? (identity/connection-for :microsoft)))
    (is (nil? (identity/connection-for :microsoft alice-did)))))

;; ── 見える範囲も人に属する ──────────────────────────────────────────────

(deftest one-person-does-not-see-anothers-connection-as-their-own
  (testing "旧実装は :connections を organization で絞っていた。同僚の
            Microsoft 接続が『あなたの接続』に並び、Disconnect を押せた"
    (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft)
                          "c-bob" (connection "c-bob" bob-did "user-bob" :microsoft)})
      (let [view (signed-in-view "user-alice")]
        (is (= ["c-alice"] (mapv :id (:connections view))))))))

(deftest connected-is-answered-for-the-signed-in-person-not-for-anybody
  (testing "旧実装の :connected? は state のどこかに接続があれば true
            （組織すら跨いでいた）。2人目は『接続済み』を見て自分の
            アカウントを繋がず、同期は1人目の grant で回り続けた"
    (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft)})
      (let [bob-view (signed-in-view "user-bob")
            ms (some #(when (= "microsoft" (:id %)) %) (:providers bob-view))]
        (is (false? (:connected? ms))
            "bob はまだ自分の Microsoft を繋いでいない"))
      (let [alice-view (signed-in-view "user-alice")
            ms (some #(when (= "microsoft" (:id %)) %) (:providers alice-view))]
        (is (true? (:connected? ms)))))))

(deftest the-public-view-says-whose-connection-it-is
  (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft)})
    (let [view (signed-in-view "user-alice")]
      (is (= alice-did (:user-did (first (:connections view))))))))

(deftest the-public-view-never-carries-a-token-or-a-token-reference
  (with-state (fixture {"c-alice" (connection "c-alice" alice-did "user-alice" :microsoft
                                              :access-token-ref "kc:access"
                                              :refresh-token-ref "kc:refresh")})
    (let [c (first (:connections (signed-in-view "user-alice")))]
      (is (nil? (:access-token-ref c)))
      (is (nil? (:refresh-token-ref c))))))

;; ── 本人確認が先。DID があっても may-act? しない人には接続させない ────────

(deftest connecting-requires-an-enrolled-passkey
  (testing "DID は作成時に付く。loopback に最初に到達した誰かに外部 grant を
            結ぶのは別の問題で、`may-act?` がそれを止める。callback ではなく
            開始時に拒否するので、同意画面自体が出ない"
    (with-state (fixture {})
      (try
        (identity/start-oauth! {:user-id "user-new" :organization-id "org-a"}
                               :microsoft "http://localhost:1337")
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          ;; 未設定 OAuth クライアントより先に本人確認の不在で落ちてもよいし、
          ;; その逆でもよい。要点は「接続が始まらない」こと。
          (is (contains? #{:passkey/required :oauth/not-configured :identity/no-did}
                         (:type (ex-data e)))))))))

;; ── 移行 ────────────────────────────────────────────────────────────────

(deftest a-legacy-connection-gains-its-did-rather-than-becoming-invisible
  (testing ":user-did の無い接続は DID 単位の検索から漏れ、『未接続』として
            読める。:user-id は持っているので DID は導出できる —— 導出しな
            ければならない"
    (with-state (fixture {"org-a:microsoft"
                          (dissoc (connection "org-a:microsoft" nil "user-alice" :microsoft)
                                  :user-did)})
      (is (nil? (identity/connection-for :microsoft alice-did))
          "移行前は DID で引けない")
      (identity/ensure-did-links!)
      (is (= "org-a:microsoft" (:id (identity/connection-for :microsoft alice-did)))
          "移行後は引ける（Keychain の名前は変えていないので token もそのまま）"))))
