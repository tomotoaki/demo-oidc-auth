package com.example.demoidcauthmobilebff.e2e;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright を使用した Mobile/App BFF の自動E2Eテスト。
 * 
 * このテストでは以下の認証およびAPI呼び出しのフローを検証します：
 * 1. BFFの認証開始エンドポイントへアクセスする。
 * 2. 未認証の場合、Keycloak (IdP) のログイン画面へリダイレクトされるため、テストユーザーの認証情報を入力してログインする。
 * 3. ログイン成功後、BFFへリダイレクトされてBFFとのセッションが確立されたことを検証する。
 * 4. BFF経由でリソースサーバーのAPIを呼び出すエンドポイントへアクセスする。
 * 5. BFFがセッションのアクセストークンをToken Exchangeし、リソースサーバーのAPIを呼び出して正しいユーザー情報を取得できることを検証する。
 */
@Tag("playwright")
class MobileBffPlaywrightIT {

    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void setUpAll() {
        // Playwright の初期化と Chromium ブラウザの起動
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(Boolean.parseBoolean(systemProperty("bff.e2e.headless", "true")))
                .setArgs(java.util.List.of("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage")));
    }

    @AfterEach
    void tearDown() {
        // テスト毎にブラウザコンテキストとページをクローズしてクリーンアップ
        if (context != null) {
            context.close();
        }
        if (page != null) {
            page.close();
        }
    }

    /**
     * BFFログインからToken Exchangeを経由したリソースサーバーAPIの呼び出しまでの一連のフローをテストします。
     */
    @Test
    void loginAndCallResourceServerApiViaTokenExchange() {
        context = browser.newContext();
        page = context.newPage();
        page.setViewportSize(1280, 900);

        String baseUrl = systemProperty("bff.base-url", "http://localhost:8081/mobile-bff");
        String username = systemProperty("keycloak.username", "demo");
        String password = systemProperty("keycloak.password", "demo");

        // 1. BFFのOAuth2認可開始エンドポイントへナビゲート
        page.navigate(baseUrl + "/oauth2/authorization/keycloak");

        // 2. ログインフォームが表示されるか、もしくはすでにログイン済みで認証結果が表示されるかを待機
        String result = waitUntilLoginFormOrAuthenticated(page);

        if ("login".equals(result)) {
            // ログイン画面が表示された場合、Keycloakの入力フィールドに資格情報を入力して送信
            page.fill("#username", username);
            page.fill("#password", password);
            page.click("#kc-login");
        }

        // 3. BFFのセッションレスポンス (JSON) にて、正しく認証状態になっているかを検証
        // bodyText に "authenticated":true および preferredUsername に "demo" が含まれていることを確認
        page.waitForFunction("() => document.body.innerText.includes('\"authenticated\":true')", null, new Page.WaitForFunctionOptions().setTimeout(20000));
        assertThat(bodyTextWithoutWhitespace())
                .contains("\"preferredUsername\":\"demo\"");

        // 4. BFFが提供するリソースサーバーAPIのプロキシエンドポイント (api/user) へナビゲート
        // ここにアクセスすると、BFFは内部でToken Exchangeを起動してリソースサーバーのAPIを呼び出します
        page.navigate(baseUrl + "/api/user");

        // 5. リソースサーバーから返却されたレスポンスを検証
        // Token Exchangeフロー ("authorization_code+token_exchange") が正しく適用されていることを確認
        page.waitForFunction("() => document.body.innerText.includes('\"resourceServerUser\"')", null, new Page.WaitForFunctionOptions().setTimeout(20000));
        assertThat(bodyTextWithoutWhitespace())
                .contains("\"flow\":\"authorization_code+token_exchange\"")
                .contains("\"preferred_username\":\"demo\"");
    }

    @Test
    void callClientCredentialsApiWithCertHeader() {
        context = browser.newContext(new Browser.NewContextOptions()
            .setExtraHTTPHeaders(java.util.Map.of("X-Amzn-Mtls-Clientcert-Subject", "CN=demo1@example.com")));
        page = context.newPage();
        page.setViewportSize(1280, 900);

        String baseUrl = systemProperty("bff.base-url", "http://127.0.0.1:8081/mobile-bff");

        // 1. APIへアクセス
        page.navigate(baseUrl + "/api/client-credentials");

        // 2. レスポンスの検証
        page.waitForFunction("() => document.body.innerText.includes('\"client_credentials\"')", null, new Page.WaitForFunctionOptions().setTimeout(20000));
        assertThat(bodyTextWithoutWhitespace())
                .contains("\"cn\":\"demo1@example.com\"")
                .contains("\"flow\":\"client_credentials\"")
                .contains("\"tokenValue\":")
                .contains("\"resourceServerResponse\":");
    }

    private String waitUntilLoginFormOrAuthenticated(Page page) {
        long timeout = 20000; // 20 seconds
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                String bodyText = bodyTextWithoutWhitespace();

                if (bodyText.contains("\"authenticated\":true")) {
                    return "authenticated";
                }

                if (isElementDisplayed("#username")) {
                    return "login";
                }
            } catch (PlaywrightException e) {
                // Continue waiting
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for login form or authentication", e);
            }
        }

        throw new AssertionError("Timed out waiting for login form or authentication. currentUrl="
                + page.url() + ", body=" + bodyText());
    }

    private String bodyText() {
        return page.locator("body").textContent();
    }

    private String bodyTextWithoutWhitespace() {
        return bodyText().replaceAll("\\s+", "");
    }

    private boolean isElementDisplayed(String selector) {
        try {
            Locator element = page.locator(selector);
            return element.isVisible();
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private static String systemProperty(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }
}
