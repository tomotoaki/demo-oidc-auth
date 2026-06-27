package com.example.demoidcauth.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

/**
 * スイッチユーザー中の認証トークン。
 * JwtAuthenticationToken を継承し、切り替え先ユーザー名と
 * 元ユーザー名・切替方式を保持する。
 *
 * <ul>
 *   <li>switch_method = "session"  : Approach A（セッションスイッチ）- JWT は元ユーザー (admin) のまま</li>
 *   <li>switch_method = "exchange" : Approach B（Token Exchange）  - JWT は切替先ユーザーの本物</li>
 * </ul>
 */
public class SwitchedJwtAuthenticationToken extends JwtAuthenticationToken {

    private static final long serialVersionUID = 1L;

    /** 切り替え先ユーザー名 (例: "demo") */
    private final String switchedToUsername;

    /** 切り替え元ユーザー名 (例: "admin") */
    private final String originalUsername;

    /** 切替方式: "session" or "exchange" */
    private final String switchMethod;

    public SwitchedJwtAuthenticationToken(
            Jwt jwt,
            Collection<? extends GrantedAuthority> authorities,
            String switchedToUsername,
            String originalUsername,
            String switchMethod) {
        super(jwt, authorities);
        this.switchedToUsername = switchedToUsername;
        this.originalUsername = originalUsername;
        this.switchMethod = switchMethod;
        // 認証済みとしてマーク (super でも setAuthenticated(true) されているが念のため)
        setAuthenticated(true);
    }

    /** このトークンの「名前」を切り替え先ユーザー名にオーバーライド */
    @Override
    public String getName() {
        return switchedToUsername;
    }

    public String getSwitchedToUsername() {
        return switchedToUsername;
    }

    public String getOriginalUsername() {
        return originalUsername;
    }

    public String getSwitchMethod() {
        return switchMethod;
    }
}
