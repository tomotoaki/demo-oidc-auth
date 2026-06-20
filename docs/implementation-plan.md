# demo-oidc-auth サンプルアプリケーション実装計画

## 技術スタック

| コンポーネント | 技術 |
|---|---|
| Client (SPA) | Vue.js 3 + Vite + Axios |
| Server | Spring Boot 4.1 (Spring Security 7) |
| セキュリティ | OAuth2 Client + OAuth2 Resource Server |
| セッション永続化 | Spring Session JDBC |
| DB (ローカル) | H2 (PostgreSQLモード) |
| DB (本番想定) | PostgreSQL |
| IdP | Keycloak 26.6.3 (`C:\keycloak\26.6.3`) |
| Java | Amazon Corretto 25 (`C:\Java\jdk25.0.3_9`) |
| ビルドツール | Maven Wrapper |

## CORS設計方針

- ViteのdevServerプロキシは**使用しない**
- Client (`http://localhost:5173`) からServer (`http://localhost:8080`) へ直接リクエスト
- Server側CORS設定: `allowedOrigins: http://localhost:5173`, `allowCredentials: true`
- Session Cookie送信のためにAxiosで `withCredentials: true` を設定
- CORS設定のサンプルコードとしての役割も兼ねる

## ポート構成

| コンポーネント | ポート |
|---|---|
| Client (Vite dev) | 5173 |
| Server (Spring Boot) | 8080 |
| IdP (Keycloak) | 8180 |

---

## Phase 1: Keycloak 設定

### `keycloak/demo-realm.json`

| 設定 | 値 |
|---|---|
| レルム名 | `demo` |
| クライアントID | `demo-client` |
| クライアントシークレット | `demo-secret` |
| 認可フロー | Authorization Code Flow |
| リダイレクトURI | `http://localhost:8080/login/oauth2/code/keycloak` |
| Web Origins | `http://localhost:5173` |
| ログアウト後リダイレクト | `http://localhost:5173` |
| ユーザ | username: `demo` / password: `demo` |

### Keycloak インポートコマンド

> Keycloak が停止していることを確認してからインポートすること。

**CMD**
```cmd
C:\keycloak\26.6.3\bin\kc.bat import --file keycloak\demo-realm.json
```

**Bash**
```bash
/c/keycloak/26.6.3/bin/kc.sh import --file keycloak/demo-realm.json
```

### Keycloak 起動コマンド

**CMD**
```cmd
C:\keycloak\26.6.3\bin\kc.bat start-dev --http-port 8180
```

**Bash**
```bash
/c/keycloak/26.6.3/bin/kc.sh start-dev --http-port 8180
```

---

## Phase 2: Server (`demo-oidc-auth-server`)

### アーキテクチャ概要

2つの `SecurityFilterChain` を定義する:

1. **API チェーン** (`/api/**`, Order=1):
   - `RequestAttributeSecurityContextRepository` を使用 (コンテキストをセッションに書き戻さない)
   - `TokenAuthenticationFilter` でセッションからOAuth2AuthorizedClientを読み込みJWT認証をセット
   - 401を返す `HttpStatusEntryPoint` を設定

2. **デフォルトチェーン** (その他、Order=2):
   - OAuth2 Login (Keycloak連携)
   - 認証成功後 `http://localhost:5173/` へリダイレクト
   - ログアウト処理

### ディレクトリ構成

```
demo-oidc-auth-server/
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw
├── mvnw.cmd
├── pom.xml
└── src/main/
    ├── java/com/example/demoidcauth/
    │   ├── DemoOidcAuthApplication.java
    │   ├── config/
    │   │   └── SecurityConfig.java
    │   ├── filter/
    │   │   └── TokenAuthenticationFilter.java
    │   └── controller/
    │       └── UserController.java
    └── resources/
        └── application.yml
```

### 主要な依存関係

- `spring-boot-starter-oauth2-client`
- `spring-boot-starter-oauth2-resource-server`
- `spring-boot-starter-web`
- `spring-session-jdbc`
- `spring-boot-starter-jdbc`
- `com.h2database:h2` (runtime)

### `TokenAuthenticationFilter` の処理フロー

1. `request.getSession(false)` でセッション取得 (新規作成しない)
2. セッションから `SPRING_SECURITY_CONTEXT_KEY` で `SecurityContext` を読み込み
3. 認証が `OAuth2AuthenticationToken` であることを確認
4. `OAuth2AuthorizedClientRepository.loadAuthorizedClient()` でAccess Token取得
5. Access Tokenが期限切れ (30秒バッファ) の場合、`OAuth2AuthorizedClientManager` でRefresh
6. `JwtDecoder.decode()` でJWTをデコード
7. `JwtAuthenticationToken` をSecurityContextHolderにセット

---

## Phase 3: Client (`demo-oidc-auth-client`)

### ディレクトリ構成

```
demo-oidc-auth-client/
├── package.json
├── vite.config.js   ← プロキシ設定なし
├── index.html
└── src/
    ├── main.js
    ├── App.vue
    ├── api/
    │   └── axios.js  ← baseURL: http://localhost:8080, withCredentials: true
    ├── router/
    │   └── index.js
    └── views/
        ├── LoginView.vue  ← ログインボタン → window.location で Server へ遷移
        └── HomeView.vue   ← /api/user 呼び出し, ログアウト
```

### ルーティング

| パス | コンポーネント | 備考 |
|---|---|---|
| `/login` | LoginView.vue | 未認証時のリダイレクト先 |
| `/` | HomeView.vue | 認証済みのみ |

---

## 事前準備 (環境変数)

```cmd
SET JAVA_HOME=C:\Java\jdk25.0.3_9
```

---

## 検証手順

1. `kc.bat import` でレルム設定をインポート
2. `kc.bat start-dev --http-port 8180` でKeycloak起動
3. `.\mvnw.cmd spring-boot:run` でServer起動
4. `npm install && npm run dev` でClient起動
5. `http://localhost:5173/` にアクセス → `/login` へリダイレクト確認
6. Keycloakログイン (`demo` / `demo`) → ホーム画面表示確認
7. DevToolsでリクエストヘッダに `Access-Control-Allow-Origin: http://localhost:5173` があることを確認
8. DevToolsのCookiesで `SESSION` Cookieのみ存在し、トークン類がないことを確認
