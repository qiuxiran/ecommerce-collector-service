package com.san.taobao.crawler.parser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.openqa.selenium.JavascriptExecutor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 取出商品详情数据。
 * <p>
 * 实测结论 抓取的淘宝+天猫真实页面：商品数据不是通过单独的 XHR/JSONP 接口下发的，
 * 而是服务端渲染时内联在页面里，最终赋值给 {@code window.__ICE_APP_CONTEXT__}（阿里 ICE 框架的 SSR 上下文）。
 * 抓包只能看到用户信息、广告位等这类外围无用接口，
 * {@code skuBase} 从头到尾不出现在任何 mtop 响应里 —— 最初 拦截mtop接口的思路不对
 * <p>
 * 两条取数路径：
 * <ol>
 *   <li>在线：直接读 {@code window.__ICE_APP_CONTEXT__}（首选，不依赖 HTML 文本形态）；</li>
 *   <li>离线：从保存的 page.html 里做带字符串转义感知的花括号配对提取（用于复算历史抓取结果）。</li>
 * </ol>
 */
public final class IceContextExtractor {

    /** 内联脚本里 SSR 数据的起始标记，按可靠性排序 */
    private static final List<String> HTML_MARKERS = List.of(
            "var b = {\"appData\"",
            "{\"appData\":",
            "__ICE_APP_CONTEXT__=");

    private IceContextExtractor() {
    }

    /** 从活动页面读取 ICE 上下文。 */
    public static JsonObject fromDriver(JavascriptExecutor js) {
        try {
            Object raw = js.executeScript(
                    "try { return window.__ICE_APP_CONTEXT__ ? JSON.stringify(window.__ICE_APP_CONTEXT__) : null; }"
                            + "catch (e) { return null; }");
            if (raw == null) {
                return null;
            }
            JsonElement el = JsonParser.parseString(String.valueOf(raw));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            System.out.println("[数据] 读取 __ICE_APP_CONTEXT__ 失败: " + e.getMessage());
            return null;
        }
    }

    /** 从 JSON 字符串解析 ICE 上下文（在线读取 window.__ICE_APP_CONTEXT__ 时用）。 */
    public static JsonObject fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从保存的页面 HTML 里提取 ICE 上下文。 */
    public static JsonObject fromHtml(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        for (String marker : HTML_MARKERS) {
            int at = html.indexOf(marker);
            while (at >= 0) {
                int brace = html.indexOf('{', at + marker.length() - 1);
                if (brace < 0) {
                    break;
                }
                String json = extractJsonObjectAt(html, brace);
                if (json != null) {
                    try {
                        JsonElement el = JsonParser.parseString(json);
                        if (el.isJsonObject()) {
                            return el.getAsJsonObject();
                        }
                    } catch (Exception ignored) {
                        // 该标记处不是完整 JSON，继续找下一处
                    }
                }
                at = html.indexOf(marker, at + 1);
            }
        }
        return null;
    }

    /**
     * 从 {@code startBrace} 处的 '{' 开始做花括号配对，返回完整 JSON 文本。
     * 必须感知字符串与转义 —— 商品标题、详情文案里大量出现花括号，纯计数会在中途断掉。
     */
    private static String extractJsonObjectAt(String s, int startBrace) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = startBrace; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return s.substring(startBrace, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 在 ICE 上下文里定位真正的详情数据节点。
     * <p>
     * 已知路径是 {@code loaderData.home.data.res}，但路由名（home）和层级会随页面版本变化，
     * 所以按特征广度搜索：含 {@code skuBase} 或同时含 {@code item} 与 {@code componentsVO} 的对象。
     */
    public static JsonObject findDetailRoot(JsonObject iceContext) {
        if (iceContext == null) {
            return null;
        }
        Deque<JsonElement> queue = new ArrayDeque<>();
        queue.add(iceContext);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 20000) {
            JsonElement el = queue.poll();
            if (el == null) {
                continue;
            }
            if (el.isJsonArray()) {
                el.getAsJsonArray().forEach(queue::add);
                continue;
            }
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (o.has("skuBase") || o.has("skuCore")
                    || (o.has("item") && o.has("componentsVO"))) {
                return o;
            }
            for (String key : o.keySet()) {
                queue.add(o.get(key));
            }
        }
        return null;
    }
}
