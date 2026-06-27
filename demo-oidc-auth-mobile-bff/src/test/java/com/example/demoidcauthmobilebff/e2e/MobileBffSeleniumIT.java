package com.example.demoidcauthmobilebff.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MobileBffSeleniumIT {

    private WebDriver driver;

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Test
    void loginAndCallResourceServerApiViaTokenExchange() {
        driver = new ChromeDriver(chromeOptions());
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        String baseUrl = systemProperty("bff.base-url", "http://localhost:8081/mobile-bff");
        String username = systemProperty("keycloak.username", "demo");
        String password = systemProperty("keycloak.password", "demo");

        driver.get(baseUrl + "/oauth2/authorization/keycloak");

        if (waitUntilLoginFormOrAuthenticated(wait).equals("login")) {
            WebElement usernameInput = wait.until(ExpectedConditions.elementToBeClickable(By.id("username")));
            usernameInput.clear();
            usernameInput.sendKeys(username);
            driver.findElement(By.id("password")).sendKeys(password);
            driver.findElement(By.id("kc-login")).click();
        }

        waitUntil(wait, webDriver -> bodyTextContainsIgnoringWhitespace("\"authenticated\":true"), "BFF session response");
        assertThat(bodyTextWithoutWhitespace()).contains("\"preferredUsername\":\"demo\"");

        driver.get(baseUrl + "/api/user");

        waitUntil(wait, webDriver -> bodyTextContainsIgnoringWhitespace("\"resourceServerUser\""), "resource server response");
        assertThat(bodyTextWithoutWhitespace())
            .contains("\"flow\":\"authorization_code+token_exchange\"")
            .contains("\"preferred_username\":\"demo\"");
    }

    private static ChromeOptions chromeOptions() {
        ChromeOptions options = new ChromeOptions();
        if (Boolean.parseBoolean(systemProperty("bff.e2e.headless", "true"))) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--no-sandbox");
        options.addArguments("--window-size=1280,900");
        return options;
    }

    private String bodyText() {
        return driver.findElement(By.tagName("body")).getText();
    }

    private void waitUntil(WebDriverWait wait, ExpectedCondition<Boolean> condition, String description) {
        try {
            wait.until(condition);
        } catch (RuntimeException e) {
            throw new AssertionError("Timed out waiting for " + description
                + ". currentUrl=" + safeCurrentUrl()
                + ", body=" + safeBodyText(), e);
        }
    }

    private String waitUntilLoginFormOrAuthenticated(WebDriverWait wait) {
        return wait.until(webDriver -> {
            if (bodyTextContainsIgnoringWhitespace("\"authenticated\":true")) {
                return "authenticated";
            }
            if (isElementDisplayed(By.id("username"))) {
                return "login";
            }
            return null;
        });
    }

    private boolean bodyTextContainsIgnoringWhitespace(String expectedText) {
        try {
            return bodyTextWithoutWhitespace().contains(expectedText);
        } catch (WebDriverException e) {
            return false;
        }
    }

    private String bodyTextWithoutWhitespace() {
        return bodyText().replaceAll("\\s+", "");
    }

    private boolean isElementDisplayed(By locator) {
        try {
            return driver.findElement(locator).isDisplayed();
        } catch (WebDriverException e) {
            return false;
        }
    }

    private String safeCurrentUrl() {
        try {
            return driver.getCurrentUrl();
        } catch (WebDriverException e) {
            return "<unavailable: " + e.getMessage() + ">";
        }
    }

    private String safeBodyText() {
        try {
            return bodyText();
        } catch (WebDriverException e) {
            return "<unavailable: " + e.getMessage() + ">";
        }
    }

    private static String systemProperty(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }
}
