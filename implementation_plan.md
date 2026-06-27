# スイッチユーザー実装計画

## 概要

`SwitchUserFilter` のOIDC対応版を2段階で実装する。  
クライアントとのI/F（`POST /login/impersonate?username=X` / `POST /logout/impersonate`）は両アプローチで共通。

---

## 2つのアプローチの差分

| 観点 | Approach A（セッションスイッチ） | Approach B（Token Exchange） |
|------|-------------------------------|------------------------------|
| JWT の `sub` / `preferred_username` | **adminのまま**（見た目だけ切り替え） | **demoのJWT**（本物の切り替え） |
| Keycloak設定変更 | なし | `demo-client` に `standard.token.exchange.enabled: true` |
| 外部リソースサーバーへの正確性 | ❌ admin として通信 | ✅ demo として通信 |
| デモの学習価値 | SwitchUserFilter の仕組み理解 | Token Exchange の仕組み理解 |
| URL | `POST /login/impersonate?username=demo` | `POST /login/impersonate?username=demo&method=exchange` |

---

## Open Questions

> [!NOTE]
> Keycloakポートは `application.yml` より `8180` を確認済。

> [!IMPORTANT]
> Approach B の Token Exchange では `demo-client` クライアントに追加設定が必要。  
> また、`admin` ユーザーに Keycloak の `realm-management` クライアントの `impersonation` ロール付与が必要かどうか確認したい。  
> Standard Token Exchange（Keycloak 26+）では `requested_subject`（ユーザー名指定）を使うため、Keycloak Admin REST API の `impersonation` ロールとは別のフローになる。

---

## Phase A：セッションスイッチ

### アーキテクチャ

```
[POST /login/impersonate?username=demo]
         ↓
OidcSwitchUserFilter
  ① ROLE_ADMIN を確認
  ② session["SWITCH_USER_NAME"] = "demo" を保存
  ③ session["SWITCH_USER_ORIGINAL_AUTH"] = admin の OAuth2AuthenticationToken を保存
  ④ ホームへリダイレクト

[以降のすべてのリクエスト]
         ↓
SecurityContext: admin の OAuth2AuthenticationToken（セッションから復元）
         ↓
TokenRefreshFilter（既存・修正）
  ① admin の access_token を取得・リフレッシュ
  ② session["SWITCH_USER_NAME"] を確認
  ③ "demo" が存在 → SwitchedJwtAuthenticationToken を生成
     - 実体: admin の JWT
     - switchedToUsername: "demo"
     - authorities: [ROLE_USER, ROLE_PREVIOUS_ADMINISTRATOR]
  ④ SecurityContext にセット

[GET /user]
         ↓
UserController
  SwitchedJwtAuthenticationToken の場合:
    preferred_username = "demo"（切り替え先）
    sub = "(session switch - no JWT for target user)"
    email = "(not available)"
    is_switched = true
    switched_from = "admin"
    switch_method = "session"
    actual_token_sub = admin の JWT sub（注意書き付き）
    exp / expJst = admin の JWT の有効期限
```

### 変更ファイル一覧

---

#### [MODIFY] [demo-realm.json](file:///c:/workspace/demo-oidc-auth/keycloak/demo-realm.json)

- realm roles `ADMIN`, `USER` を追加
- `demo` ユーザーに `USER` ロール付与
- `admin` ユーザー（パスワード: `admin`）を追加し `ADMIN` ロール付与

---

#### [MODIFY] [SecurityConfig.java](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-server/src/main/java/com/example/demoidcauth/config/SecurityConfig.java)

- `jwtAuthenticationConverter()` Bean 追加  
  → Keycloak の `realm_access.roles` を `ROLE_ADMIN` / `ROLE_USER` にマッピング
- `authorizeHttpRequests` に `/login/impersonate`, `/logout/impersonate` を `hasRole("ADMIN")` or 適切な設定
- `OidcSwitchUserFilter` を Filter Chain に登録（`UsernamePasswordAuthenticationFilter` の後）
- `oauth2ResourceServer` に `jwtAuthenticationConverter` を適用

---

#### [NEW] [SwitchedJwtAuthenticationToken.java](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-server/src/main/java/com/example/demoidcauth/security/SwitchedJwtAuthenticationToken.java)

```java
// JwtAuthenticationToken を継承したカスタム認証トークン
// 切り替え先ユーザー名と元ユーザー名を保持
public class SwitchedJwtAuthenticationToken extends JwtAuthenticationToken {
    private final String switchedToUsername;
    private final String originalUsername;
    private final String switchMethod; // "session" or "exchange"
}
```

---

#### [NEW] [OidcSwitchUserFilter.java](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-server/src/main/java/com/example/demoidcauth/filter/OidcSwitchUserFilter.java)

```
役割: SwitchUserFilter の OIDC対応カスタム実装

処理 (POST /login/impersonate?username=demo):
  - ROLE_ADMIN チェック
  - session に SWITCH_USER_NAME, SWITCH_USER_ORIGINAL_AUTH を保存
  - リダイレクト

処理 (POST /logout/impersonate):
  - session から SWITCH_USER_NAME を削除
  - session から SWITCH_USER_ORIGINAL_AUTH を削除
  - リダイレクト
```

---

#### [MODIFY] [TokenRefreshFilter.java](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-server/src/main/java/com/example/demoidcauth/filter/TokenRefreshFilter.java)

- JWT取得後、`session["SWITCH_USER_NAME"]` を確認
- 存在する場合: `SwitchedJwtAuthenticationToken` を生成して SecurityContext にセット
- 存在しない場合: 既存の `JwtAuthenticationToken` をセット（変更なし）

---

#### [MODIFY] [UserController.java](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-server/src/main/java/com/example/demoidcauth/controller/UserController.java)

- `Authentication` 引数を追加
- `SwitchedJwtAuthenticationToken` の場合: 切り替え先ユーザー情報を返す
- 通常の場合: 既存ロジック（変更なし）
- ロール情報 (`roles`) も返すように拡張

---

#### [MODIFY] [HomeView.vue](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-client/src/views/HomeView.vue)

- `/user` レスポンスの `roles` を確認し、`ROLE_ADMIN` の場合のみスイッチUI表示
- スイッチUI: ユーザー名入力フィールド + 「ユーザー切替（セッション方式）」ボタン
- スイッチ中の場合: バナー表示（「adminとして demoに切替中」）+ 「元に戻す」ボタン
- `is_switched: true` の場合に切替方式の説明テキストを表示

---

## Phase B：Token Exchange（Phase A に追加）

### アーキテクチャ

```
[POST /login/impersonate?username=demo&method=exchange]
         ↓
OidcSwitchUserFilter（修正）
  ① ROLE_ADMIN を確認
  ② admin の access_token を取得
  ③ TokenExchangeService.exchange(adminToken, "demo") を呼び出し
  ④ Keycloak から demo の access_token + refresh_token を受け取る
  ⑤ session に元の OAuth2AuthorizedClient を保存
  ⑥ OAuth2AuthorizedClient を demo のトークンで差し替え
  ⑦ session["SWITCH_USER_METHOD"] = "exchange" を保存
  ⑧ ホームへリダイレクト

[以降のすべてのリクエスト]
         ↓
TokenRefreshFilter（既存・修正）
  ① authorizedClientManager.authorize() → demo の OAuth2AuthorizedClient を取得
  ② demo の JWT を decode → sub=demo_sub_id, preferred_username="demo"
  ③ switch_method=exchange が session に存在 → SwitchedJwtAuthenticationToken 生成
     - 実体: demo の本物の JWT ← Approach A との最大の差分！
     - switchedToUsername: "demo"
     - switchMethod: "exchange"
  ④ SecurityContext にセット

[POST /logout/impersonate]
         ↓
OidcSwitchUserFilter（修正）
  ① 元の OAuth2AuthorizedClient を session から復元
  ② OAuth2AuthorizedClientRepository に書き戻す
  ③ session の SWITCH_USER_NAME / METHOD を削除
```

### 追加変更ファイル

---

#### [MODIFY] [demo-realm.json](file:///c:/workspace/demo-oidc-auth/keycloak/demo-realm.json)

- `demo-client` に `"standard.token.exchange.enabled": "true"` を追加

---

#### [NEW] [TokenExchangeService.java](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-server/src/main/java/com/example/demoidcauth/service/TokenExchangeService.java)

```
Keycloak Token Exchange エンドポイントへの REST 呼び出し:
  POST http://localhost:8180/realms/demo/protocol/openid-connect/token
  grant_type = urn:ietf:params:oauth:grant-type:token-exchange
  client_id = demo-client
  client_secret = demo-secret
  subject_token = <adminの access_token>
  subject_token_type = urn:ietf:params:oauth:token-type:access_token
  requested_token_type = urn:ietf:params:oauth:token-type:access_token
  requested_subject = demo   ← Keycloak固有パラメータ（ユーザー名で指定）
```

---

#### [MODIFY] [OidcSwitchUserFilter.java](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-server/src/main/java/com/example/demoidcauth/filter/OidcSwitchUserFilter.java)

- `method=exchange` パラメータを検出
- `TokenExchangeService` を利用して demo のトークンを取得
- `OAuth2AuthorizedClientRepository` の内容を差し替え
- exit 時に元のトークンを復元

---

#### [MODIFY] [HomeView.vue](file:///c:/workspace/demo-oidc-auth/demo-oidc-auth-client/src/views/HomeView.vue)

- 「ユーザー切替（Token Exchange方式）」ボタンを追加
- 切替方式（A/B）による `/user` レスポンスの違いを UI で強調表示
  - Approach A: 「⚠ セッション方式 - JWTはadminのまま」
  - Approach B: 「✅ Token Exchange方式 - JWTがdemoに切り替わっています」

---

## Session キー一覧

| キー | 内容 | Phase |
|------|------|-------|
| `SWITCH_USER_NAME` | 切り替え先ユーザー名 (`"demo"`) | A, B |
| `SWITCH_USER_METHOD` | `"session"` または `"exchange"` | A, B |
| `SWITCH_USER_ORIGINAL_AUTH` | 元の `OAuth2AuthenticationToken` | A |
| `SWITCH_USER_ORIGINAL_CLIENT` | 元の `OAuth2AuthorizedClient` | B のみ |

---

## Verification Plan

### Phase A
1. `admin` でログイン → ROLE_ADMIN バッジが表示される
2. 「ユーザー切替（セッション）」ボタン → `username=demo` でPOST
3. `/user` レスポンスが `preferred_username=demo`, `switch_method=session`, `is_switched=true` を返す
4. **JWTのsubはadminのまま**であることを確認（Approach Aの限界を実証）
5. 「元に戻す」→ admin の情報に戻ることを確認

### Phase B
1. `standard.token.exchange.enabled` が `demo-client` に設定されていることを確認
2. 「ユーザー切替（Token Exchange）」ボタン → `method=exchange` でPOST
3. `/user` レスポンスが `preferred_username=demo`, `sub=<demo user's UUID>`, `switch_method=exchange` を返す
4. **JWTのsubがdemoのUUID**に変わっていることを確認（Approach Bの本物切り替えを実証）
5. 「元に戻す」→ admin の情報に戻ることを確認
