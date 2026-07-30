# Spring Security: フォーム認証 → Keycloak OIDC/JWT 移行設計メモ

## 1. 背景・要件

- 既存システムはフォーム認証(`UserDetails`ベース)
- 認証基盤をKeycloak(OIDC)へ移行する
- 移行後も既存コードとの互換性のため`UserDetails`を残したい
- 権限(Authorities)は以下を合成する
  - Keycloakの `realm roles`
  - Keycloakの `client roles`
  - Keycloakの `groups`
  - アプリ固有にDBで管理する追加ロール(**方針C:マージ方式**)
- 最終的な認証方式は **アクセストークンをサーバ側セッションに保持し、`SecurityContextRepository`でリクエスト毎に復元する** 構成(BFF的なJWT Resource Server構成)

---

## 2. `UserDetails` と `OidcUser` の共存

`UserDetails`と`OidcUser`(`OAuth2User`を継承)はメソッドがほぼ衝突しないため、1クラスで両方実装可能。

| インターフェース | 主なメソッド |
|---|---|
| `UserDetails` | `getAuthorities()`, `getPassword()`, `getUsername()`, `isAccountNonExpired()` 等 |
| `OidcUser` | `getAuthorities()`(共通), `getAttributes()`, `getName()`, `getIdToken()`, `getUserInfo()`, `getClaims()` |

- `getUsername()`(UserDetails)と`getName()`(OidcUser)は別メソッドなので共存可能
- `getAuthorities()`はシグネチャが同一なので1実装で両方を満たせる
- `getPassword()`はOIDCではパスワードを扱わないため`null`を返す実装で問題なし

```java
public class CustomOidcUser implements OidcUser, UserDetails {
    private final OidcUser oidcUser;
    private final Collection<? extends GrantedAuthority> authorities;
    private final AppUser appUser; // ローカルDBのユーザーエンティティ

    // OidcUser系メソッドはoidcUserに委譲
    // UserDetails系メソッドはappUserベースで実装
    // getAuthorities()はマージ済みのauthoritiesフィールドを返す
}
```

**注意点**
- `Authentication#getPrincipal()`の型はフォーム認証時`UserDetails`、移行後`OidcUser`実質`CustomOidcUser`
- セッションを外部ストア(Redis等)に保存する場合、`Serializable`対応を要確認
- `@PreAuthorize`等の既存認可ロジックは、Authoritiesの中身さえ揃えれば認証方式に依存させず流用可能

---

## 3. Keycloakクレームの構造

```json
{
  "sub": "xxxx-xxxx",
  "email": "user@example.com",
  "groups": ["/dept-a", "/dept-a/team1"],
  "realm_access": {
    "roles": ["offline_access", "app-user", "app-admin"]
  },
  "resource_access": {
    "my-client": { "roles": ["client-viewer", "client-editor"] },
    "other-client": { "roles": ["other-role"] }
  }
}
```

- `groups`クレームはKeycloak側でMapper追加が必要(デフォルトでは含まれない)
- `resource_access`はクライアント単位でネストしているため、自クライアントIDでのフィルタが必要

### Authorities変換の実装方針

```java
@Component
public class KeycloakAuthoritiesConverter {
    private final String clientId;

    public Collection<GrantedAuthority> convert(Map<String, Object> claims) {
        // realm_access.roles → "ROLE_" + role.toUpperCase()
        // resource_access.<clientId>.roles → "ROLE_" + role.toUpperCase()
        // groups → "GROUP_" + group名(スラッシュを置換)
    }
}
```

**設計判断ポイント**
- roleとgroupで接頭辞を分けるか(`ROLE_` / `GROUP_`)は既存の`hasRole()`規約に合わせる
- ネストしたgroup(`/dept-a/team1`)を階層のまま使うか末端のみ使うかは要件次第

---

## 4. Keycloakロール と DBローカルロールのマージ(方針C)

### 4.1 権限マージ方針の比較(検討時の整理)

| 方針 | 概要 |
|---|---|
| A. Keycloak優先 | authoritiesはKeycloakのみ。DBはプロフィール情報のみ保持 |
| B. DB優先 | Keycloakは認証のみ。authoritiesはDBの既存ロールテーブルから取得 |
| **C. マージ(採用)** | KeycloakのAuthoritiesにDBの追加ロールを合成(和集合) |
| D. 同期 | ログイン時にKeycloakのroles/groupsをDBへ同期し、DBから読む |

→ 今回は **方針C**:Keycloak側の粗い権限(realm/client roles, groups)に、DBで細かく管理するアプリ固有ロールを追加する構成。

### 4.2 DB設計

```sql
CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    keycloak_sub VARCHAR(64) NOT NULL UNIQUE, -- Keycloakのsub(不変ID)。emailは変わりうるためキーにしない
    email VARCHAR(255),
    created_at TIMESTAMP
);

CREATE TABLE app_user_local_authority (
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    authority VARCHAR(100) NOT NULL, -- 例: "ROLE_SCREEN_A_EXPORT"
    PRIMARY KEY (user_id, authority)
);
```

**重要な設計原則**
- `app_user_local_authority`は**Keycloak側(`ROLE_APP_ADMIN`等)と名前空間が被らない命名規約**にする
  - 例: Keycloak由来 `ROLE_APP_ADMIN`, `GROUP_DEPT_A` / DB由来 `ROLE_SCREEN_A_EXPORT`, `ROLE_REPORT_DOWNLOAD`
  - 混同すると「Keycloak側でロールを消したつもりがDB側の同名ロールで権限が生き残る」事故につながる
- CIやアプリ起動時に命名規約違反(prefix衝突)を検知するバリデーションを入れると安全
- 監査ログ用に`granted_by` / `granted_at`カラムを追加しておくと運用上便利

### 4.3 マージ処理の実装(共通ロジック)

Keycloak由来のAuthoritiesとDBローカルAuthoritiesを取得し、和集合として合成する処理を**共通コンバータ**として1箇所にまとめ、OIDC Login方式・JWT Resource Server方式のどちらからも呼び出せるようにする。

```java
Collection<GrantedAuthority> keycloakAuthorities = authoritiesConverter.convert(claims);

Collection<GrantedAuthority> localAuthorities = appUser.getLocalAuthorities().stream()
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList());

Set<GrantedAuthority> merged = new LinkedHashSet<>();
merged.addAll(keycloakAuthorities);
merged.addAll(localAuthorities);
```

- ユーザー紐付けキーは **`sub`(Keycloakの不変ID)** を使用する(emailは変更されうるため)
- 該当ユーザーがDBに存在しない場合はJIT(Just-In-Time) provisioningで新規作成する

---

## 5. 認証方式ごとの実装(最終形:JWT Resource Server + セッション保持)

当初はOIDC Login(`OidcUserService`)方式で検討したが、最終的な採用方式は **アクセストークンをJWTとして扱うResource Server構成**であり、かつ**アクセストークンをサーバ側セッションに保持し、`SecurityContextRepository`でリクエスト毎に復元する**方式(選択肢B)を採用。

### 5.1 カスタムAuthentication型

`JwtAuthenticationToken`は`principal`が素の`Jwt`になるため、`UserDetails`を持たせるには専用のAuthentication型を用意する。

```java
public class KeycloakAuthenticationToken extends AbstractAuthenticationToken {
    private final Jwt jwt;
    private final CustomUserPrincipal principal; // UserDetails実装

    @Override
    public Object getPrincipal() { return principal; }

    @Override
    public Object getCredentials() { return jwt.getTokenValue(); }

    public Jwt getJwt() { return jwt; }
}
```

```java
public class CustomUserPrincipal implements UserDetails {
    private final AppUser appUser;
    private final Collection<? extends GrantedAuthority> authorities;
    // UserDetailsメソッドはappUserベースで実装(getPassword()はnull)
    public AppUser getAppUser() { return appUser; }
}
```

- Resource Server構成のため`OidcUser`実装は不要(`IdToken`/`UserInfo`は関与しない)

### 5.2 Jwt → Authentication 変換の共通化

`Converter<Jwt, AbstractAuthenticationToken>`として実装し、**初回認証時(Authorizationヘッダ経由)** と **セッション復元時** の両方から共通利用する。

```java
@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    @Transactional
    public AbstractAuthenticationToken convert(Jwt jwt) {
        // 1. sub でAppUserをJIT取得/作成
        // 2. Keycloakクレームからauthorities変換
        // 3. DBローカルauthoritiesを取得
        // 4. 和集合でマージ
        // 5. CustomUserPrincipal + KeycloakAuthenticationTokenを生成して返す
    }
}
```

```java
http.oauth2ResourceServer(oauth2 -> oauth2
        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter))
);
```

### 5.3 セッション保持の設計(選択肢Bの採用理由)

| 選択肢 | 概要 | 評価 |
|---|---|---|
| A. 標準の`HttpSessionSecurityContextRepository` | 復元済み`Authentication`(=Authoritiesも含む)を丸ごとシリアライズしてセッション保存 | 実装は楽だが、JPAエンティティのSerializable対応が煩雑。ロール変更が再ログインまで反映されない |
| **B. 生トークンのみセッション保持(採用)** | セッションにはアクセストークン文字列のみ保持し、リクエスト毎に`JwtDecoder`で再デコード→`KeycloakJwtAuthenticationConverter`で毎回Authoritiesを再構成 | Serializable対応不要。**Keycloak側・DB側どちらのロール変更も次のリクエストから即時反映**。セッションデータも小さい |

**採用理由(選択肢B)**
- セッションには生トークンだけを持たせるという設計意図に合致
- Keycloak管理コンソールでのロール変更、DB側のローカルロール付与、いずれも次リクエストから即座に反映される
- `AppUser`等のJPAエンティティをSerializable対応する必要がない

### 5.4 `SecurityContextRepository`実装

```java
public class TokenSessionSecurityContextRepository implements SecurityContextRepository {

    private static final String TOKEN_SESSION_KEY = "ACCESS_TOKEN";
    private final JwtDecoder jwtDecoder;
    private final Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;

    @Override
    public SecurityContext loadContext(HttpServletRequestResponseHolder holder) {
        // 1. セッションから生トークン取得(なければ空のSecurityContext)
        // 2. jwtDecoder.decode()で署名・exp/nbf検証
        // 3. jwtAuthenticationConverter.convert()でAuthentication再構成
        // 4. JwtException時はセッションからトークンを除去
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        // KeycloakAuthenticationTokenから生トークンを取り出しセッションに保存
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        // セッション内トークンの有無を返す
    }
}
```

```java
http.securityContext(sc -> sc.securityContextRepository(securityContextRepository));
```

> **補足(API注意点)**: Spring Security 6系の`SecurityContextRepository`は`loadContext(HttpServletRequestResponseHolder)`(旧API)と`loadDeferredContext(HttpServletRequest)`(新API)を持つが、新APIには旧`loadContext`を呼ぶデフォルト実装があるため、`loadContext`のみの実装でも動作する。警告を避けたい場合は`loadDeferredContext`を直接実装する形も検討可。

### 5.5 リクエストフロー全体像

1. **初回(Authorizationヘッダあり)**
   `BearerTokenAuthenticationFilter` → `JwtAuthenticationProvider` → `KeycloakJwtAuthenticationConverter`で`KeycloakAuthenticationToken`生成 → `SecurityContextHolderFilter`が`saveContext()`で生トークンをセッション保存
2. **2回目以降(セッションcookieのみ)**
   `SecurityContextHolderFilter`が`loadContext()`呼び出し → セッション内の生トークンを`JwtDecoder`で再検証 → `KeycloakJwtAuthenticationConverter`でAuthorities再構成 → `SecurityContext`にセット

この構成により、Authorizationヘッダの有無に関わらず一貫した`KeycloakAuthenticationToken`(`UserDetails`実装済み)がコンテキストに入る。

---

## 6. 運用・パフォーマンス上の考慮事項

- **DBアクセス頻度**: 選択肢Bはリクエスト毎にDB問い合わせが発生する。アクセス頻度が高い場合は`sub`をキーにした短TTLキャッシュ(Spring Cache + Caffeine等)を`KeycloakJwtAuthenticationConverter`のDB検索部分に導入することを検討
- **トークン有効期限**: `JwtDecoder.decode()`が期限切れで例外を投げ、その時点で未認証扱いになる。リフレッシュトークンによる裏側再取得が必要な場合は、`loadContext`内の例外処理箇所やOAuth2AuthorizedClientManagerとの連携を別途設計する必要あり(未確定・要検討事項)
- **CSRF対策**: セッションcookieベース認証に切り替えるため、フォーム認証時代のCSRF対策(`CookieCsrfTokenRepository`等)の継続適用が必要。ステートレスJWT(ヘッダ方式のみ)からの移行の場合は特に見落としやすい
- **ログアウト**: サーバ側セッション破棄に加え、Keycloak側セッションも終了させる場合は別途end-sessionエンドポイント呼び出し処理が必要(未確定・要検討事項)
- **`sub`の不変性**: Keycloakでユーザーを削除・再作成すると`sub`が変わり、JIT provisioningで新規`AppUser`が作成される点に注意。ユーザー統合が必要な場合はKeycloak側の運用ルールとセットで検討

---

## 7. 未確定・今後の検討事項

- リフレッシュトークンによるアクセストークン再取得フローの詳細設計
- ログアウト時のKeycloakセッション終了処理(Front-Channel/Back-Channel Logout含む)
- ローカルロール付与のための管理画面・API設計、および付与操作に対する`@PreAuthorize`等のアクセス制御
- キャッシュ導入時のロール変更即時反映とのトレードオフ調整(TTL設計)
