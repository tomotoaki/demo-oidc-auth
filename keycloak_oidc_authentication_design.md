# Spring Security: フォーム認証 → Keycloak OIDC/JWT 移行設計

## 1. 要件・前提

- 認証基盤: フォーム認証 → Keycloak(OIDC)
- DBユーザー情報の取得は現行どおり`UserDetails`として行う(変更しない)
- Authoritiesは以下を**マージ**する(方針C)
  - Keycloak: realm roles / client roles / groups
  - DB: アプリ固有のローカルロール
- 認証方式は次の2経路を**共存**させる
  - **経路A(OAuth2 Login)**: ブラウザ、セッションCookie。OPから`OidcUser`を取得
  - **経路B(Resource Server)**: 他サーバから受領したアクセストークン。`Jwt`をデコード
- 設計原則: **`OidcUser`と`Jwt`を1つのクラスで無理に共存させない**。共通化するのは「抽出済みのAuthorities」のみで、claims(生データ)を共通の合流点にはしない

---

## 2. クラス構成

| クラス/インターフェース | 実装するもの | 役割 |
|---|---|---|
| `AppUser` | (なし。JPAエンティティ) | DBのユーザーテーブル。データの入れ物のみ |
| `AppUserDetails` | `UserDetails` | DB由来のユーザー情報。現行実装を踏襲。キーは`sub`(Keycloak不変ID) |
| `AppUserDetailsService` | - | `sub`でDBから`AppUserDetails`を取得(なければJIT作成) |
| `AppAuthoritiesResolver` | - | Keycloak由来Authorities抽出 + DB由来Authoritiesとのマージ。両経路共通 |
| `AppOidcUser` | `OidcUser`, `UserDetails` | 経路A専用のprincipal |
| `AppOidcUserService` | `OidcUserService`を継承 | 経路Aで`AppOidcUser`を生成 |
| `AppJwtPrincipal` | `UserDetails`のみ | 経路B専用のprincipal(`OidcUser`は実装しない) |
| `AppJwtAuthenticationToken` | `AbstractAuthenticationToken`を継承 | 経路B用の`Authentication`。principalに`AppJwtPrincipal`を保持 |
| `AppJwtAuthenticationConverter` | `Converter<Jwt, AbstractAuthenticationToken>` | 経路Bで`AppJwtAuthenticationToken`を生成 |

### 依存関係

```
                AppUserDetailsService ──▶ AppUserDetails(UserDetails, DB由来)
                                                  │
                                                  ▼
                                       AppAuthoritiesResolver
                                  resolve(OidcUser, UserDetails)
                                  resolve(Jwt, UserDetails)
                                                  │
                    ┌─────────────────────────────┴─────────────────────────────┐
                    ▼                                                           ▼
          経路A: AppOidcUser                                     経路B: AppJwtPrincipal
       implements OidcUser, UserDetails                        implements UserDetails
                    │                                                           │
                    ▼                                                           ▼
       OAuth2AuthenticationToken(標準)                        AppJwtAuthenticationToken(自前)
```

両経路の共通部分は**`AppUserDetailsService`と`AppAuthoritiesResolver`の2つのみ**。principal・Authenticationの実装クラスは経路ごとに完全分離する。

---

## 3. 実装

### 3.1 DB由来のUserDetails(現行踏襲)

```java
public class AppUserDetails implements UserDetails {
    private final String subject;      // Keycloak sub
    private final String username;
    private final Collection<? extends GrantedAuthority> localAuthorities;

    @Override public String getUsername() { return username; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return localAuthorities; }
    @Override public String getPassword() { return null; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }

    public String getSubject() { return subject; }
}
```

```java
@Service
@RequiredArgsConstructor
public class AppUserDetailsService {
    private final AppUserRepository appUserRepository;

    public AppUserDetails loadBySubject(String subject, String emailHint) {
        AppUser appUser = appUserRepository.findByKeycloakSub(subject)
                .orElseGet(() -> appUserRepository.save(new AppUser(subject, emailHint)));

        Collection<GrantedAuthority> local = appUser.getLocalAuthorities().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        return new AppUserDetails(subject, appUser.getEmail(), local);
    }
}
```

### 3.2 Authorities抽出・マージ(唯一の共通ポイント)

```java
@Component
@RequiredArgsConstructor
public class AppAuthoritiesResolver {

    private final String clientId;

    public Collection<GrantedAuthority> resolve(OidcUser oidcUser, UserDetails dbUserDetails) {
        return merge(parseKeycloakRoleClaims(oidcUser.getClaims()), dbUserDetails.getAuthorities());
    }

    public Collection<GrantedAuthority> resolve(Jwt jwt, UserDetails dbUserDetails) {
        return merge(parseKeycloakRoleClaims(jwt.getClaims()), dbUserDetails.getAuthorities());
    }

    private Collection<GrantedAuthority> parseKeycloakRoleClaims(Map<String, Object> claims) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        Optional.ofNullable(claims.get("realm_access"))
                .map(o -> (Map<?, ?>) o)
                .map(m -> (Collection<?>) m.get("roles"))
                .ifPresent(roles -> roles.forEach(r ->
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + r.toString().toUpperCase()))));

        Optional.ofNullable(claims.get("resource_access"))
                .map(o -> (Map<?, ?>) o)
                .map(m -> (Map<?, ?>) m.get(clientId))
                .map(m -> (Collection<?>) m.get("roles"))
                .ifPresent(roles -> roles.forEach(r ->
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + r.toString().toUpperCase()))));

        Optional.ofNullable(claims.get("groups"))
                .map(o -> (Collection<?>) o)
                .ifPresent(groups -> groups.forEach(g -> {
                    String name = g.toString().replaceFirst("^/", "").replace('/', '_');
                    authorities.add(new SimpleGrantedAuthority("GROUP_" + name.toUpperCase()));
                }));

        return authorities;
    }

    private Collection<GrantedAuthority> merge(
            Collection<? extends GrantedAuthority> opAuthorities,
            Collection<? extends GrantedAuthority> dbAuthorities) {
        Set<GrantedAuthority> merged = new LinkedHashSet<>();
        merged.addAll(opAuthorities);
        merged.addAll(dbAuthorities);
        return merged;
    }
}
```

> `parseKeycloakRoleClaims`は2つの`resolve`メソッドの内部実装として共有しているだけであり、公開マージAPIではない。どちらか一方だけクレーム解釈を変える必要が生じた場合は、該当`resolve`メソッド内だけを個別に変更する。

### 3.3 経路A: OAuth2 Login

```java
public class AppOidcUser implements OidcUser, UserDetails {

    private final OidcUser delegate;
    private final UserDetails dbUserDetails;
    private final Collection<? extends GrantedAuthority> mergedAuthorities;

    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    @Override public String getName() { return delegate.getName(); }
    @Override public OidcIdToken getIdToken() { return delegate.getIdToken(); }
    @Override public OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }
    @Override public Map<String, Object> getClaims() { return delegate.getClaims(); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return mergedAuthorities; }

    @Override public String getUsername() { return dbUserDetails.getUsername(); }
    @Override public String getPassword() { return null; }
    @Override public boolean isAccountNonExpired() { return dbUserDetails.isAccountNonExpired(); }
    @Override public boolean isAccountNonLocked() { return dbUserDetails.isAccountNonLocked(); }
    @Override public boolean isCredentialsNonExpired() { return dbUserDetails.isCredentialsNonExpired(); }
    @Override public boolean isEnabled() { return dbUserDetails.isEnabled(); }
}
```

```java
@Service
@RequiredArgsConstructor
public class AppOidcUserService extends OidcUserService {

    private final AppUserDetailsService appUserDetailsService;
    private final AppAuthoritiesResolver appAuthoritiesResolver;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = super.loadUser(userRequest);
        UserDetails dbUserDetails = appUserDetailsService.loadBySubject(oidcUser.getSubject(), oidcUser.getEmail());
        Collection<GrantedAuthority> merged = appAuthoritiesResolver.resolve(oidcUser, dbUserDetails);
        return new AppOidcUser(oidcUser, dbUserDetails, merged);
    }
}
```

`Authentication`はSpring標準の`OAuth2AuthenticationToken`をそのまま使う(principalに`AppOidcUser`が入るだけ)。

```java
http.oauth2Login(oauth2 -> oauth2
        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(appOidcUserService))
);
```

### 3.4 経路B: Resource Server

```java
public class AppJwtPrincipal implements UserDetails {

    private final Jwt jwt;
    private final UserDetails dbUserDetails;
    private final Collection<? extends GrantedAuthority> mergedAuthorities;

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return mergedAuthorities; }
    @Override public String getUsername() { return dbUserDetails.getUsername(); }
    @Override public String getPassword() { return null; }
    @Override public boolean isAccountNonExpired() { return dbUserDetails.isAccountNonExpired(); }
    @Override public boolean isAccountNonLocked() { return dbUserDetails.isAccountNonLocked(); }
    @Override public boolean isCredentialsNonExpired() { return dbUserDetails.isCredentialsNonExpired(); }
    @Override public boolean isEnabled() { return dbUserDetails.isEnabled(); }

    public Jwt getJwt() { return jwt; }
}
```

```java
public class AppJwtAuthenticationToken extends AbstractAuthenticationToken {

    private final Jwt jwt;
    private final AppJwtPrincipal principal;

    public AppJwtAuthenticationToken(Jwt jwt, AppJwtPrincipal principal) {
        super(principal.getAuthorities());
        this.jwt = jwt;
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override public Object getPrincipal() { return principal; }
    @Override public Object getCredentials() { return jwt.getTokenValue(); }
    public Jwt getJwt() { return jwt; }
}
```

```java
@Component
@RequiredArgsConstructor
public class AppJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AppUserDetailsService appUserDetailsService;
    private final AppAuthoritiesResolver appAuthoritiesResolver;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UserDetails dbUserDetails = appUserDetailsService.loadBySubject(jwt.getSubject(), jwt.getClaimAsString("email"));
        Collection<GrantedAuthority> merged = appAuthoritiesResolver.resolve(jwt, dbUserDetails);
        AppJwtPrincipal principal = new AppJwtPrincipal(jwt, dbUserDetails, merged);
        return new AppJwtAuthenticationToken(jwt, principal);
    }
}
```

```java
http.oauth2ResourceServer(oauth2 -> oauth2
        .jwt(jwt -> jwt.jwtAuthenticationConverter(appJwtAuthenticationConverter))
);
```

### 3.5 SecurityConfig(両経路の共存)

```java
http
    .oauth2Login(oauth2 -> oauth2
            .userInfoEndpoint(userInfo -> userInfo.oidcUserService(appOidcUserService))
    )
    .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(appJwtAuthenticationConverter))
    );
```

### 3.6 ビジネスロジック側

```java
@GetMapping("/me")
public MyPageResponse me(@AuthenticationPrincipal UserDetails principal) {
    Collection<? extends GrantedAuthority> authorities = principal.getAuthorities();
    // 経路A/Bどちらでも UserDetails として扱える
}
```

`OidcUser`固有の情報(`getIdToken()`等)が必要な場合のみ`instanceof AppOidcUser`で分岐する。

---

## 4. DB設計(方針C)

```sql
CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    keycloak_sub VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(255),
    created_at TIMESTAMP
);

CREATE TABLE app_user_local_authority (
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    authority VARCHAR(100) NOT NULL, -- 例: "ROLE_SCREEN_A_EXPORT"
    PRIMARY KEY (user_id, authority)
);
```

- `app_user_local_authority`はKeycloak側(`ROLE_APP_ADMIN`等)と名前空間が被らない命名規約にする
  - Keycloak由来: `ROLE_APP_ADMIN`, `GROUP_DEPT_A` / DB由来: `ROLE_SCREEN_A_EXPORT`, `ROLE_REPORT_DOWNLOAD`
- CIやアプリ起動時に命名規約違反(prefix衝突)を検知するバリデーションを推奨
- 監査ログ用に`granted_by` / `granted_at`カラムを追加しておくと運用上便利

---

## 5. 運用上の注意点

| 項目 | 内容 |
|---|---|
| DBアクセス頻度 | リクエスト毎に`AppUserDetailsService`経由でDB問い合わせが発生。高頻度アクセス時は`sub`キーの短TTLキャッシュ(Caffeine等)を検討 |
| `sub`の不変性 | Keycloakでユーザー削除・再作成すると`sub`が変わり、JITで新規`AppUser`が作成される |
| CSRF | 経路A(セッションCookie)はCSRF対策(`CookieCsrfTokenRepository`等)が必要。経路B(ステートレス)は対象外として除外設定が必要 |
| 未認証時のレスポンス分岐 | `oauth2Login`と`oauth2ResourceServer`同居時、未認証リクエストへの挙動(302 vs 401)が競合する。`DelegatingAuthenticationEntryPoint`かパス単位の`SecurityFilterChain`分割で対応 |
| ログアウト | セッション破棄に加え、Keycloak側セッション終了(end-sessionエンドポイント)が別途必要 |

---

## 6. 未決定事項

- 未認証時のレスポンス分岐の実装方式(`DelegatingAuthenticationEntryPoint` か `SecurityFilterChain`分割か)
- 経路Bにおけるアクセストークン期限切れ時の責務分担(呼び出し元での再取得が前提)
- ログアウト時のKeycloakセッション終了処理(Front-Channel/Back-Channel Logout)
- ローカルロール付与の管理画面・API設計、および付与操作へのアクセス制御
- キャッシュ導入時のTTLと、ロール変更即時反映とのトレードオフ
