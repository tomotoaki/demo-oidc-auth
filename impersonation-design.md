# 管理者なりすまし(SwitchUser相当)設計書
## 内部STS方式によるBFF/RS実装

---

## 1. 背景と方針

Keycloak Token Exchange V2は現時点(2026年7月)でsubject impersonation(トークンのsubを対象ユーザーに書き換える機能)を実装していない。Legacy Token Exchange V1のImpersonation機能はPreview/Deprecatedであり、Token Exchange Delegation機能はExperimentalかつユーザー同意が毎セッション必須のため、管理者による無同意の代理操作(サポート調査など)には適合しない。

そこで、KeycloakのToken Exchange機能には依存せず、**BFF(管理アプリのbackend)を小さな内部STS(Security Token Service)として機能させ、対象ユーザーとして振る舞う短命JWTを自社署名鍵で発行する**方式を採用する。RFC 8693のdelegation semantics(`sub`=対象ユーザー、`act`=実行者)の"形"だけを流用し、発行者はKeycloakではなくBFFとする。

```
[管理者ブラウザ]
     │ 通常ログイン(Keycloak, 管理者トークン取得)
     ▼
[BFF = 内部STS]
     │ ① 管理者トークンの検証・権限チェック
     │ ② 対象ユーザー向け短命JWTを自社鍵で発行
     ▼
[RS(保護対象API)]
     Keycloak発行トークン と 内部STS発行トークン の両方をissuerで判別して検証
```

将来Keycloak V2がsubject impersonationをネイティブサポートした場合は、本設計のクレーム体系(`sub`/`act`)をそのまま踏襲する形でKeycloakのToken Exchangeへ置き換えられるようにしておく。

---

## 2. トークン設計(共通仕様)

内部STSが発行するJWTのクレーム定義。RS側の検証ロジックはこの仕様に依存するため、最初に固定する。

| クレーム | 内容 | 必須 |
|---|---|---|
| `iss` | `https://bff.example.com/impersonation` (内部STS固有のissuer URL) | ✔ |
| `sub` | 対象ユーザーのユーザーID(Keycloakの`sub`と同じ値を使う) | ✔ |
| `act.sub` | 実行した管理者のユーザーID | ✔ |
| `act.iss` | 管理者トークンの本来のissuer(KeycloakのissuerURL) | ✔ |
| `aud` | 呼び出し対象のRS識別子(単一を推奨) | ✔ |
| `scope` | ダウンスコープした権限(対象ユーザーの全権限をそのまま持たせない) | ✔ |
| `imp_reason` | 監査用の理由/チケットID(空文字禁止) | ✔ |
| `imp_session_id` | 一連のなりすまし操作をまとめる相関ID(監査の紐付け用) | ✔ |
| `iat` / `exp` | 発行時刻/有効期限。`exp - iat` は60〜120秒程度 | ✔ |
| `jti` | トークンID(重複利用検知・監査用) | ✔ |

```json
{
  "iss": "https://bff.example.com/impersonation",
  "sub": "target-user-id",
  "act": {
    "sub": "admin-user-id",
    "iss": "https://keycloak.example.com/realms/myrealm"
  },
  "aud": "target-rs",
  "scope": "read:orders",
  "imp_reason": "SUPPORT-1234",
  "imp_session_id": "8f14e45f-ceea-4a17-8f1c-9c8b1f2b4b3f",
  "iat": 1752300000,
  "exp": 1752300090,
  "jti": "b3f1..."
}
```

署名は内部STS専有の鍵ペア(Keycloakの鍵とは完全に分離)。RSには`https://bff.example.com/impersonation/.well-known/jwks.json`としてJWKSを公開する。

---

## 3. BFF側(内部STS)の設計

### 3.1 コンポーネント構成

```
POST /internal/impersonation/tokens
  - 入力: Keycloak発行の管理者アクセストークン(Authorizationヘッダ) + { targetUserId, reason, audience, scope }
  - 出力: { access_token, token_type: "Bearer", expires_in }

GET /internal/impersonation/.well-known/jwks.json
  - 内部STSの公開鍵セット(RS側検証用)
```

### 3.2 発行フロー(4段階チェック)

1. **管理者トークンの検証**
   Keycloak発行のBearerトークンをBFF自身のResource Server機能(通常のOAuth2/JWT検証)で検証する。署名・`exp`・`aud`(BFF自身宛であること)を確認。

2. **権限チェック**
   Keycloak側で管理者に付与された`impersonate-users`ロール(realm role or client role)の有無を検証する。Keycloakの新しい交換機能は使わず、単純なロールクレームの確認のみ。

3. **業務ルール検証**
   - 対象ユーザーが存在するか(Keycloak Admin REST APIまたは自社ユーザーディレクトリに照会)
   - 対象ユーザーが管理者自身でないか
   - 同一テナント/組織に属しているか(マルチテナントの場合)
   - `reason`が空でないか(自由記述 or チケットIDフォーマットの検証)

4. **短命JWT発行**
   上記が全て通れば、内部STS鍵でJWTに署名し返却する。`scope`は要求された値と対象ユーザーの実権限の積(ダウンスコープ)に限定する。

### 3.3 実装例(Spring Boot / Nimbus JOSE JWT)

```java
@RestController
@RequestMapping("/internal/impersonation")
public class ImpersonationController {

    private final ImpersonationTokenService tokenService;
    private final ImpersonationAuditLogger auditLogger;

    @PostMapping("/tokens")
    @PreAuthorize("hasAuthority('SCOPE_impersonate-users')")
    public ResponseEntity<TokenResponse> issue(
            @AuthenticationPrincipal Jwt adminToken,
            @RequestBody @Valid ImpersonationRequest request) {

        // 業務ルール検証(対象ユーザー存在確認・テナント一致・自分自身でないか等)
        targetUserValidator.validate(adminToken, request.targetUserId());

        if (!StringUtils.hasText(request.reason())) {
            throw new BadRequestException("reason is required");
        }

        String sessionId = UUID.randomUUID().toString();

        SignedJWT jwt = tokenService.issue(
                adminToken.getSubject(),      // act.sub
                adminToken.getIssuer(),        // act.iss
                request.targetUserId(),        // sub
                request.audience(),
                downscope(request.scope(), adminToken, request.targetUserId()),
                request.reason(),
                sessionId,
                Duration.ofSeconds(90));

        auditLogger.logIssued(adminToken.getSubject(), request.targetUserId(),
                request.reason(), sessionId);

        return ResponseEntity.ok(new TokenResponse(jwt.serialize(), "Bearer", 90));
    }
}
```

```java
@Service
public class ImpersonationTokenService {

    private final RSAKey signingKey; // 起動時にKMS/Vaultからロード、定期ローテーション

    public SignedJWT issue(String actorSub, String actorIss, String targetSub,
                            String audience, String scope, String reason,
                            String sessionId, Duration ttl) {

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://bff.example.com/impersonation")
                .subject(targetSub)
                .audience(audience)
                .claim("act", Map.of("sub", actorSub, "iss", actorIss))
                .claim("scope", scope)
                .claim("imp_reason", reason)
                .claim("imp_session_id", sessionId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt;
    }
}
```

JWKSエンドポイントはNimbusの`JWKSet`をそのまま公開鍵のみでシリアライズして返す標準的な実装でよい。

### 3.4 ガードレール(BFF側)

- **鍵ローテーション**: 数日〜数週間単位で`kid`をローテーション。旧鍵は検証用に一定期間JWKSへ残す。
- **レート制限**: 同一管理者が単位時間内に発行できるトークン数を制限(例: Bucket4Jで1分間5回まで)。
- **異常検知**: 短時間に多数の異なるユーザーへのなりすましトークンが発行された場合にアラート。
- **監査ログの二重化**: 発行時にBFF側でも構造化ログ/専用監査テーブルに記録し、後述のRS側ログと突合できるよう`imp_session_id`で紐付ける。
- **即時無効化**: 緊急時は内部STSの署名鍵を失効・ローテーションすればよい。トークンは短命なので実質即座に無害化される。Keycloak側のRevocationチェーンには依存しない。

---

## 4. RS側(Spring Security Resource Server)の設計

### 4.1 マルチIssuer対応

RSはKeycloak発行トークンと内部STS発行トークンの両方を受け付ける必要がある。Spring Securityの`JwtIssuerAuthenticationManagerResolver`を使い、`iss`クレームでAuthenticationManagerを振り分ける。

```java
@Bean
public AuthenticationManagerResolver<HttpServletRequest> issuerAuthenticationManagerResolver(
        KeycloakJwtAuthenticationManagerProvider keycloakProvider,
        ImpersonationJwtAuthenticationManagerProvider impersonationProvider) {

    Map<String, AuthenticationManager> managers = Map.of(
            "https://keycloak.example.com/realms/myrealm", keycloakProvider.get(),
            "https://bff.example.com/impersonation", impersonationProvider.get()
    );

    return new JwtIssuerAuthenticationManagerResolver(managers::get);
}

@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http,
        AuthenticationManagerResolver<HttpServletRequest> resolver) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.authenticationManagerResolver(resolver));
    return http.build();
}
```

### 4.2 内部STSトークン用の検証・変換

内部STS発行トークンには通常のJWT検証に加えて、`act`クレームの必須化や`exp`の妥当性など追加バリデーションを行う。

```java
@Component
public class ImpersonationJwtAuthenticationManagerProvider {

    public AuthenticationManager get() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri("https://bff.example.com/impersonation/.well-known/jwks.json")
                .build();

        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator("https://bff.example.com/impersonation"),
                actClaimRequiredValidator(),
                shortLifetimeValidator(Duration.ofMinutes(3))
        );
        decoder.setJwtValidator(validators);

        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(impersonationAuthenticationConverter());
        return provider::authenticate;
    }

    private OAuth2TokenValidator<Jwt> actClaimRequiredValidator() {
        return jwt -> jwt.getClaimAsMap("act") != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                      new OAuth2Error("invalid_token", "act claim is required", null));
    }

    private OAuth2TokenValidator<Jwt> shortLifetimeValidator(Duration maxTtl) {
        return jwt -> {
            Duration ttl = Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt());
            return ttl.compareTo(maxTtl) <= 0
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                          new OAuth2Error("invalid_token", "token lifetime too long", null));
        };
    }
}
```

### 4.3 SecurityContext構築(RunAsUserToken方式)

Spring SecurityのSwitchUserFilterが内部的に使う`RunAsUserToken`(元の認証情報を保持しつつ対象ユーザーとして振る舞い、`ROLE_PREVIOUS_ADMINISTRATOR`のような特別権限を追加する仕組み)と同じ考え方をRS側にも適用する。`act`クレームがあれば、Principalは対象ユーザーだが、追加の権限として`IMPERSONATED_BY_ADMIN`を持たせ、監査・業務ロジック側で判別できるようにする。

```java
public class ImpersonationAwareAuthenticationToken extends AbstractAuthenticationToken {

    private final Jwt jwt;
    private final String targetUserId;   // sub
    private final String actorUserId;    // act.sub (nullable: 通常トークンならnull)

    public ImpersonationAwareAuthenticationToken(Jwt jwt, String targetUserId,
                                                  String actorUserId,
                                                  Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.jwt = jwt;
        this.targetUserId = targetUserId;
        this.actorUserId = actorUserId;
        setAuthenticated(true);
    }

    @Override public Object getCredentials() { return jwt; }
    @Override public Object getPrincipal() { return targetUserId; }
    public boolean isImpersonated() { return actorUserId != null; }
    public String getActorUserId() { return actorUserId; }
}
```

```java
private Converter<Jwt, AbstractAuthenticationToken> impersonationAuthenticationConverter() {
    return jwt -> {
        Map<String, Object> act = jwt.getClaimAsMap("act");
        String actorSub = act != null ? (String) act.get("sub") : null;

        Collection<GrantedAuthority> authorities = new ArrayList<>(scopesToAuthorities(jwt));
        if (actorSub != null) {
            authorities.add(new SimpleGrantedAuthority("IMPERSONATED_BY_ADMIN"));
        }

        return new ImpersonationAwareAuthenticationToken(
                jwt, jwt.getSubject(), actorSub, authorities);
    };
}
```

Keycloak発行トークン側の`AuthenticationManager`は通常の`JwtAuthenticationConverter`でよいが、`actorUserId=null`の同種`ImpersonationAwareAuthenticationToken`(またはただの`JwtAuthenticationToken`)を返すようにしておくと、業務コード側は常に同じ型で「なりすましか否か」を判定できる。

### 4.4 監査ロギング(AOP)

```java
@Aspect
@Component
public class ImpersonationAuditAspect {

    private final ImpersonationAuditLogger auditLogger;

    @Around("@annotation(org.springframework.web.bind.annotation.RequestMapping) "
          + "|| execution(* com.example.api..*Controller.*(..))")
    public Object logIfImpersonated(ProceedingJoinPoint pjp) throws Throwable {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ImpersonationAwareAuthenticationToken token && token.isImpersonated()) {
            auditLogger.logAccess(
                    token.getActorUserId(),   // 実行した管理者
                    token.getPrincipal().toString(), // 対象ユーザー
                    pjp.getSignature().toShortString(),
                    Instant.now());
        }
        return pjp.proceed();
    }
}
```

RS側でも`imp_session_id`をMDC(ログコンテキスト)に積んでおくと、BFF側の発行ログとRS側のアクセスログを相関IDで突合できる。

### 4.5 認可判断への反映

業務ロジック側(`@PreAuthorize`など)では、通常のスコープ/ロール判定に加えて、必要であれば`IMPERSONATED_BY_ADMIN`権限を見て「なりすまし中は特定の破壊的操作を禁止する」といった追加制約をかけることもできる。

```java
@PreAuthorize("hasAuthority('SCOPE_write:orders') and !hasAuthority('IMPERSONATED_BY_ADMIN') or hasRole('SUPPORT_WRITE_ALLOWED')")
```

---

## 5. 運用ガードレール(全体)

| 項目 | 内容 |
|---|---|
| トークン寿命 | 内部STSトークンは60〜120秒。加えてBFF側の「なりすましセッション」自体も15分程度でタイムボックスし、期限切れ後は権限チェックからやり直す |
| 監査 | BFF発行時・RS受信時の二重記録。`imp_session_id`で相関。誰が・いつ・誰として・なぜ(`imp_reason`)を必須項目化 |
| 通知 | 対象ユーザーへの事後通知(任意)。透明性確保のため推奨 |
| レート制限/異常検知 | 同一管理者による短時間・多数ユーザーへのなりすましを検知しアラート |
| 緊急停止 | 内部STS署名鍵のローテーション/失効で即座に新規トークン発行を止められる。既存トークンも短命のため実害は限定的 |
| 対象RSの限定 | `aud`は単一のRSに限定し、複数RSへの使い回しを避ける(RSごとに個別トークンを発行させる) |

---

## 6. 将来のKeycloak移行への含み

Keycloak側でV2にsubject impersonationがネイティブ実装された場合(upstream issue: keycloak/keycloak#38336 で追跡中)、以下の対応で移行できるよう設計しておく。

- 本設計の`sub`/`act`クレーム体系は、KeycloakのRFC 8693準拠実装がそのまま採用する形と一致させてある
- RS側の`JwtIssuerAuthenticationManagerResolver`の設定で、内部STSのissuerをKeycloakのissuerに差し替えるだけで移行可能な構造にしておく
- `ImpersonationAwareAuthenticationToken`や監査AOPなど、RS側のロジックは発行者がKeycloakに変わっても変更不要
- 移行タイミングでBFFの内部STS機能(鍵管理・発行エンドポイント)は撤去し、BFFはKeycloakへのToken Exchangeリクエストの仲介役に縮小できる
