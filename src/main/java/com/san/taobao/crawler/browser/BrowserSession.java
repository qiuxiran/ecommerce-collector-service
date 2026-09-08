package com.san.taobao.crawler.browser;

import com.san.taobao.config.ChromeConfig;
import com.san.taobao.config.Constants;
import com.san.taobao.config.SystemProps;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.chrome.ChromeDriver;

import java.nio.file.Path;
import java.util.Locale;

/** ???????? session ???? Chrome profile????????? */
public final class BrowserSession implements AutoCloseable {

    private static final long DOMAIN_SETTLE_MS = 2500;

    private final ChromeDriver driver;
    private final MtopSniffer sniffer;
    private final Path profileDir;

    public static BrowserSession open(ChromeConfig config) throws Exception {
        ChromeDriver driver = new ChromeLauncher(config).launch();
        MtopSniffer sniffer = new MtopSniffer(driver);
        sniffer.installOnNewDocument();
        return new BrowserSession(driver, sniffer, config.profileDir());
    }

    private BrowserSession(ChromeDriver driver, MtopSniffer sniffer, Path profileDir) {
        this.driver = driver;
        this.sniffer = sniffer;
        this.profileDir = profileDir;
    }

    public ChromeDriver driver() {
        return driver;
    }

    public MtopSniffer sniffer() {
        return sniffer;
    }

    public Path profileDir() {
        return profileDir;
    }

    public String currentUrl() {
        try {
            return driver.getCurrentUrl();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public void openHome() {
        driver.get(Constants.TAOBAO_HOME);
        SystemProps.sleepMs(DOMAIN_SETTLE_MS);
    }

    public boolean onTaobaoDomain() {
        return currentUrl().toLowerCase(Locale.ROOT).contains("taobao.com");
    }

    public boolean looksLoggedIn() {
        for (String name : Constants.LOGIN_NICKNAME_COOKIES) {
            Cookie c = driver.manage().getCookieNamed(name);
            if (c != null && c.getValue() != null && !c.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    public boolean isBlocked() {
        return RiskDetector.isBlocked(driver);
    }

    public void refresh() {
        driver.navigate().refresh();
    }

    @Override
    public void close() {
        System.out.println("[??] ??????");
        try {
            driver.quit();
        } catch (Exception ignored) {
        }
    }
}
