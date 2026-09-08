package com.san.taobao.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 一条被捕获的 mtop 响应。仅用于诊断「数据到底从哪来」，不是商品数据的来源。 */
public record MtopCapture(String url, String body) {

    private static final Pattern API_NAME = Pattern.compile("(mtop\\.[a-zA-Z0-9._]+)");

    /** 接口名，如 {@code mtop.taobao.pcdetail.data.get}；取不到时返回空串。 */
    public String apiName() {
        Matcher m = API_NAME.matcher(url);
        return m.find() ? m.group(1) : "";
    }
}
