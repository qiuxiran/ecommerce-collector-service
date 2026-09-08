package com.san.taobao;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在页面里挂钩子，收集淘宝 mtop 接口的响应体。
 * <p>
 * <b>这不是主数据源。</b>实测商品数据是 SSR 内联在页面里的（见 {@link IceContextExtractor}），
 * mtop 接口只有用户信息、广告位这类外围数据，{@code skuBase} 不出现在任何 mtop 响应里。
 * 保留这个类是因为它的 dump 在排查"数据到底从哪来"时很有用，
 * 且商品页未来若改回接口下发，这里能第一时间看到。
 * <p>
 * 覆盖三种传输方式：
 * <ul>
 *   <li>{@code XMLHttpRequest} —— 现在 PC 详情页的主要方式；</li>
 *   <li>{@code fetch}；</li>
 *   <li>JSONP —— mtop SDK 跨域降级时会用 {@code window.mtopjsonpN} 回调，这里预埋 setter 包一层。</li>
 * </ul>
 * 钩子通过 CDP {@code Page.addScriptToEvaluateOnNewDocument} 注入，保证在页面自身 JS 之前执行。
 */
public final class MtopSniffer {

    /** 单条响应体保留上限，避免 desc 之类的大 payload 把 driver 通道打满 */
    private static final int MAX_BODY_CHARS = 1_500_000;

    private static final String HOOK_JS = """
            (function () {
              if (window.__MTOP_HOOKED__) { return; }
              window.__MTOP_HOOKED__ = true;
              window.__MTOP_CAPTURED__ = [];
              window.__MTOP_SEEN__ = 0;

              var MAX_ENTRIES = 120;
              function keep(url, body) {
                try {
                  if (!url || typeof body !== 'string' || body.length < 2) { return; }
                  var u = String(url);
                  if (u.indexOf('mtop.') < 0 && u.indexOf('jsonp:') < 0 && u.indexOf('/h5/') < 0) { return; }
                  window.__MTOP_SEEN__++;
                  if (window.__MTOP_CAPTURED__.length >= MAX_ENTRIES) { return; }
                  window.__MTOP_CAPTURED__.push({ url: u.slice(0, 600), body: body });
                } catch (e) { /* 钩子内异常绝不能影响页面本身 */ }
              }
              window.__MTOP_KEEP__ = keep;

              var XHR = window.XMLHttpRequest;
              if (XHR && XHR.prototype) {
                var rawOpen = XHR.prototype.open;
                XHR.prototype.open = function (method, url) {
                  this.__mtopUrl = url;
                  return rawOpen.apply(this, arguments);
                };
                var rawSend = XHR.prototype.send;
                XHR.prototype.send = function () {
                  var self = this;
                  this.addEventListener('load', function () {
                    try {
                      var rt = self.responseType;
                      if (rt === '' || rt === 'text') { keep(self.__mtopUrl, self.responseText); }
                      else if (rt === 'json') { keep(self.__mtopUrl, JSON.stringify(self.response)); }
                    } catch (e) { }
                  });
                  return rawSend.apply(this, arguments);
                };
              }

              if (window.fetch) {
                var rawFetch = window.fetch;
                window.fetch = function () {
                  var arg = arguments[0];
                  var u = (arg && arg.url) ? arg.url : arg;
                  return rawFetch.apply(this, arguments).then(function (res) {
                    try {
                      res.clone().text().then(function (t) { keep(u, t); }).catch(function () { });
                    } catch (e) { }
                    return res;
                  });
                };
              }

              // mtop SDK 跨域降级为 JSONP 时，回调名形如 mtopjsonp1 / mtopjsonp2…
              // 预先给这些属性装上 setter，页面赋值回调时包一层，读到入参即为响应体。
              for (var i = 1; i <= 80; i++) {
                (function (name) {
                  var wrapped;
                  try {
                    Object.defineProperty(window, name, {
                      configurable: true,
                      set: function (fn) {
                        if (typeof fn === 'function') {
                          wrapped = function (data) {
                            try { keep('jsonp:' + name, JSON.stringify(data)); } catch (e) { }
                            return fn.apply(this, arguments);
                          };
                        } else {
                          wrapped = fn;
                        }
                      },
                      get: function () { return wrapped; }
                    });
                  } catch (e) { }
                })('mtopjsonp' + i);
              }
            })();
            """;

    private final ChromeDriver driver;
    private boolean cdpHookInstalled;

    public MtopSniffer(ChromeDriver driver) {
        this.driver = driver;
    }

    /**
     * 注册钩子，使其在此后每次导航的新文档中优先执行。
     *
     * @return true 表示 CDP 注入成功；false 表示需要在每次导航后手动补注入（会漏掉早期请求）
     */
    public boolean installOnNewDocument() {
        try {
            driver.executeCdpCommand("Page.enable", Map.of());
            driver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", HOOK_JS));
            cdpHookInstalled = true;
            return true;
        } catch (Exception e) {
            System.out.println("[钩子] CDP 注入失败，降级为导航后补注入（可能漏掉首屏请求）: " + e.getMessage());
            cdpHookInstalled = false;
            return false;
        }
    }

    public boolean isCdpHookInstalled() {
        return cdpHookInstalled;
    }

    /** CDP 不可用时的兜底：页面已加载后再注入，配合一次 refresh 才能抓全。 */
    public void injectNow() {
        try {
            ((JavascriptExecutor) driver).executeScript(HOOK_JS);
        } catch (Exception e) {
            System.out.println("[钩子] 手动注入失败: " + e.getMessage());
        }
    }

    /**
     * 累计见过的响应数。与 {@link #drain()} 不同，它不读响应体、不受缓冲区上限影响，
     * 只用来判断「刚才那次操作有没有引发新请求」。读不到时返回 -1，调用方应据此放弃这个信号。
     */
    public int seenCount() {
        try {
            Object v = ((JavascriptExecutor) driver).executeScript(
                    "return window.__MTOP_SEEN__ === undefined ? -1 : window.__MTOP_SEEN__;");
            return v instanceof Number n ? n.intValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 读出当前页面已捕获的响应。页面导航后 window 重建，捕获列表自动清空，无需手动 reset。 */
    @SuppressWarnings("unchecked")
    public List<Capture> drain() {
        List<Capture> out = new ArrayList<>();
        Object raw;
        try {
            raw = ((JavascriptExecutor) driver).executeScript(
                    "var a = window.__MTOP_CAPTURED__ || [];"
                            + "return a.map(function (x) { return { url: x.url, body: String(x.body).slice(0, "
                            + MAX_BODY_CHARS + ") }; });");
        } catch (Exception e) {
            System.out.println("[钩子] 读取捕获数据失败: " + e.getMessage());
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object url = ((Map<String, Object>) m).get("url");
                Object body = ((Map<String, Object>) m).get("body");
                if (url != null && body != null) {
                    out.add(new Capture(String.valueOf(url), String.valueOf(body)));
                }
            }
        }
        return out;
    }

    /** 一条被捕获的 mtop 响应。 */
    public record Capture(String url, String body) {

        /** 接口名，如 {@code mtop.taobao.pcdetail.data.get}；取不到时返回空串。 */
        public String apiName() {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(mtop\\.[a-zA-Z0-9._]+)")
                    .matcher(url);
            return m.find() ? m.group(1) : "";
        }
    }
}
