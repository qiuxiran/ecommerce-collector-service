package com.san.taobao.crawler.parser;

import com.san.taobao.config.Constants;
import com.san.taobao.model.Platform;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 商品链接与平台的纯文本处理。 */
public final class ItemLinks {

    private static final Pattern ID_IN_URL = Pattern.compile("[?&]id=(\\d{6,})");
    private static final Pattern BARE_ID = Pattern.compile("^\\d{6,}$");

    private ItemLinks() {
    }

    public static boolean isBareItemId(String input) {
        return input != null && BARE_ID.matcher(input).matches();
    }

    public static String extractItemId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = ID_IN_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static String detailUrl(String itemId) {
        return Constants.ITEM_DETAIL_PREFIX + itemId;
    }

    public static boolean isHttpUrl(String input) {
        return input != null && input.toLowerCase(Locale.ROOT).startsWith("http");
    }

    public static Platform platformOfUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains("tmall")
                ? Platform.TMALL : Platform.TAOBAO;
    }

    public static Platform platformOfCode(String code) {
        return Platform.TMALL.code().equalsIgnoreCase(code) ? Platform.TMALL : Platform.TAOBAO;
    }
}
