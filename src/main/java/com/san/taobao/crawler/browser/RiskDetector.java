package com.san.taobao.crawler.browser;

import com.san.taobao.config.Constants;
import org.openqa.selenium.WebDriver;

import java.util.Locale;

/** 判断当前页面是不是验证页 / 登录页　*/
public final class RiskDetector {

    private RiskDetector() {
    }

    public static boolean isBlocked(WebDriver driver) {
        String url = safeLower(currentUrl(driver));
        for (String marker : Constants.RISK_URL_MARKERS) {
            if (url.contains(marker)) {
                return true;
            }
        }
        String html = pageSource(driver);
        if (html == null) {
            return false;
        }
        boolean tooShort = html.length() < Constants.RISK_NORMAL_PAGE_MIN_CHARS;
        String lower = html.toLowerCase(Locale.ROOT);
        for (String marker : Constants.RISK_PAGE_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return tooShort || marker.equals(Constants.RISK_DEFINITIVE_MARKER);
            }
        }
        return false;
    }

    private static String currentUrl(WebDriver driver) {
        try {
            return driver.getCurrentUrl();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String pageSource(WebDriver driver) {
        try {
            return driver.getPageSource();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
