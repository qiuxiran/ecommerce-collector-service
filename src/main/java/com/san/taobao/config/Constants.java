package com.san.taobao.config;

import java.util.List;

public final class Constants {

    public static final String TAOBAO_HOME = "https://www.taobao.com/";
    public static final String ITEM_DETAIL_PREFIX = "https://item.taobao.com/item.htm?id=";

    public static final List<String> LOGIN_NICKNAME_COOKIES =
            List.of("tracknick", "lgc", "_nk_", "dnk");

    /** Baxia 拦截页内部标记，出现即可确认被拦。 */
    public static final String RISK_DEFINITIVE_MARKER = "_____tmd_____";

    public static final List<String> RISK_PAGE_MARKERS = List.of(
            RISK_DEFINITIVE_MARKER, "滑动验证", "安全验证", "请输入验证码", "亲，请稍后再试",
            "punish", "captcha", "行为异常");

    /** 被拦页面通常远小于此；正常商品页 HTML 在几十万字符量级。 */
    public static final int RISK_NORMAL_PAGE_MIN_CHARS = 20000;

    public static final List<String> RISK_URL_MARKERS = List.of(
            "login.taobao.com", "login.tmall.com", "punish");

    private Constants() {
    }
}
