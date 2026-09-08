package com.san.taobao.crawler.browser;

import com.san.taobao.config.SystemProps;
import com.san.taobao.crawler.parser.ItemLinks;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;

/** 商品详情页的浏览器操作（打开、等待、滚动、取 HTML）。 */
public final class DetailPage {

    private static final int DETAIL_DATA_TIMEOUT_SEC = 25;
    private static final long DETAIL_DATA_POLL_MS = 700;
    private static final long AFTER_OPEN_MS = 1500;
    private static final long AFTER_SCROLL_MS = 1200;
    private static final long SCROLL_STEP_MS = 900;
    private static final long DESC_PAGE_SETTLE_MS = 2500;
    private static final long SHORT_LINK_SETTLE_MS = 3000;

    private static final String READ_ICE_CONTEXT_JS = """
            try { return window.__ICE_APP_CONTEXT__ ? JSON.stringify(window.__ICE_APP_CONTEXT__) : null; }
            catch (e) { return null; }
            """;

    private static final String HAS_DETAIL_DATA_JS = """
            try {
              var c = window.__ICE_APP_CONTEXT__;
              return !!c && JSON.stringify(c).indexOf('skuBase') >= 0;
            } catch (e) { return false; }
            """;

    private final BrowserSession session;
    private final ChromeDriver driver;

    public DetailPage(BrowserSession session) {
        this.session = session;
        this.driver = session.driver();
    }

    public void open(String url) {
        System.out.println("[抓取] " + url);
        driver.get(url);
        if (!session.sniffer().isCdpHookInstalled()) {
            session.sniffer().injectNow();
            session.refresh();
        }
        SystemProps.sleepMs(AFTER_OPEN_MS);
    }

    public boolean awaitDetailData() {
        System.out.println("[等待] 等页面注入商品数据…");
        long deadline = System.currentTimeMillis() + DETAIL_DATA_TIMEOUT_SEC * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(driver.executeScript(HAS_DETAIL_DATA_JS))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
            SystemProps.sleepMs(DETAIL_DATA_POLL_MS);
        }
        System.out.println("[提示] " + DETAIL_DATA_TIMEOUT_SEC + " 秒内没等到 __ICE_APP_CONTEXT__。");
        return false;
    }

    public void scrollThrough() {
        try {
            for (double ratio : new double[]{0.25, 0.5, 0.75, 1.0}) {
                driver.executeScript("window.scrollTo(0, document.body.scrollHeight * " + ratio + ");");
                SystemProps.sleepMs(SCROLL_STEP_MS);
            }
            driver.executeScript("window.scrollTo(0, 0);");
        } catch (RuntimeException e) {
            System.out.println("[滚动] 失败: " + e.getMessage());
        }
        SystemProps.sleepMs(AFTER_SCROLL_MS);
    }

    public String html() {
        return driver.getPageSource();
    }

    public String currentUrl() {
        return session.currentUrl();
    }

    public String readIceContextJson() {
        try {
            Object raw = driver.executeScript(READ_ICE_CONTEXT_JS);
            return raw == null ? null : String.valueOf(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String fetchDescHtml(String descUrl) {
        if (descUrl == null) {
            return null;
        }
        String mainWindow = driver.getWindowHandle();
        try {
            driver.switchTo().newWindow(WindowType.TAB);
            driver.get(descUrl);
            SystemProps.sleepMs(DESC_PAGE_SETTLE_MS);
            scrollThrough();
            return driver.getPageSource();
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                if (!driver.getWindowHandle().equals(mainWindow)) {
                    driver.close();
                }
                driver.switchTo().window(mainWindow);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public String resolveItemIdByNavigation(String shortUrl) {
        try {
            driver.get(shortUrl);
            SystemProps.sleepMs(SHORT_LINK_SETTLE_MS);
            return ItemLinks.extractItemId(session.currentUrl());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
