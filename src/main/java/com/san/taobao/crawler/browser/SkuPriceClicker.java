package com.san.taobao.crawler.browser;

import com.san.taobao.model.ItemDetail;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebElement;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.chrome.ChromeDriver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逐个点选规格，读取真实的单 SKU 价格。
 * 一部分商品（{@code skuItem.hideOtherPrice=true}）的页面数据里没有SKU维度的价格，
 * {@code sku2info} 只给库存，价格要等用户选定完整规格后由页面异步取回再渲染。
 * 这类商品光解析页面数据只能拿到整品展示价，所有规格一个价。这个类模拟真人点选来补齐。
 * 代价很实在：每个 SKU 都要点击加等待，35 个规格约 1~2 分钟，且点击越密越容易触发风控。
 * 所以只对确实缺价的商品启用，点击之间留固定间隔，并且失败不抛异常——
 * 拿不到就保留整品级兜底价，不能因为补价把整条数据搞没。
 * 靠的两个 DOM 约定（实测 PC 详情页）：规格按钮是 {@code div[class*='valueItem--']}，
 * 带 {@code data-vid} 对应 {@code skuBase} 里的 vid、{@code data-disabled} 表示不可选、
 * 选中时加 {@code isSelected--} 类；价格区是 {@code div[class*='priceWrap--']}。
 * 类名哈希后缀每次发版都会变，所以只能用前缀匹配。
 */
public final class SkuPriceClicker {

    private static final int CLICK_INTERVAL_MS = 150;
    private static final int PER_SKU_PAUSE_MS = 200;
    private static final int PRICE_POLL_MS = 250;
    private static final int PRICE_TIMEOUT_MS = 6000;
    /** 点完到开始认价之间的静置时间。点完立刻读，读到的必然还是上一个规格的价。 */
    private static final int MIN_SETTLE_MS = 600;

    /** 「起」表示价格是「XX 元起」，即规格还没选全，此时读到的不是单 SKU 价。 */
    private static final String FROM_PRICE_MARKER = "起";

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");

    private static final String READ_PRICE_JS = """
            var wrap = document.querySelector("div[class*='normalPrice--'] div[class*='priceWrap--']")
                    || document.querySelector("div[class*='priceWrap--']");
            return wrap ? wrap.innerText : null;
            """;

    private final int maxSkus;

    public SkuPriceClicker(int maxSkus) {
        this.maxSkus = maxSkus;
    }

    /**
     * 给价格不精确的 SKU 逐个点选补价。
     *
     * @return 成功补到真实单 SKU 价的条数
     */
    public int fillMissingPrices(ChromeDriver driver, MtopSniffer sniffer, ItemDetail detail) {
        if (detail == null || detail.skuList.isEmpty()) {
            return 0;
        }
        List<Integer> targets = targetIndexes(detail);
        if (targets.isEmpty()) {
            return 0;
        }
        if (targets.size() > maxSkus) {
            System.out.println("[补价] 需要点选的规格有 " + targets.size()
                    + " 个，超过上限 " + maxSkus + "，跳过。要放开用 -Dtaobao.sku-click-max=N。");
            return 0;
        }
        if (readPriceText(driver) == null) {
            System.out.println("[补价] 页面上没找到价格区（div[class*='priceWrap--']），跳过点选补价。"
                    + "可能是页面改版，需要更新选择器。");
            return 0;
        }

        System.out.println("[补价] 该商品页面未下发逐 SKU 价，开始点选 " + targets.size()
                + " 个规格组合取价（预计 " + estimateSeconds(targets.size()) + " 秒，可用 -Dtaobao.sku-click=false 关闭）…");

        int filled = 0;
        int failed = 0;
        for (int n = 0; n < targets.size(); n++) {
            ItemDetail.Sku sku = detail.skuList.get(targets.get(n));
            List<String> vids = vidsOf(sku);
            if (vids.isEmpty()) {
                failed++;
                continue;
            }
            if (!selectAll(driver, vids)) {
                failed++;
                continue;
            }
            String priceText = awaitPrice(driver, sniffer);
            if (priceText == null) {
                failed++;
                continue;
            }
            if (!applyPrice(priceText, sku)) {
                failed++;
                continue;
            }
            filled++;
            // 补上的这条可能原本是兜底价，也可能原本压根没价，后者没有兜底计数可减
            if (detail.itemLevelPricedSkuCount > 0) {
                detail.itemLevelPricedSkuCount--;
            }
            detail.clickPricedSkuCount++;
            if ((n + 1) % 10 == 0 || n + 1 == targets.size()) {
                System.out.println("[补价] 进度 " + (n + 1) + "/" + targets.size()
                        + "（成功 " + filled + "，失败 " + failed + "）");
            }
            sleep(PER_SKU_PAUSE_MS);
        }
        System.out.println("[补价] 完成：补到真实 SKU 价 " + filled + " 条，失败 " + failed
                + " 条（失败的保留整品兜底价，多为已售罄不可点选的规格）。");
        return filled;
    }

    /**
     * 只处理价格不精确的：整品兜底来的、或者干脆没价的。
     * <p>
     * 单规格商品不点——它那一条的整品价本来就是它自己的价，点了也是同一个数。
     */
    private static List<Integer> targetIndexes(ItemDetail detail) {
        if (detail.skuList.size() <= 1) {
            return List.of();
        }
        boolean anyFallback = detail.itemLevelPricedSkuCount > 0;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < detail.skuList.size(); i++) {
            if (detail.skuList.get(i).price == null || anyFallback) {
                out.add(i);
            }
        }
        return out;
    }

    private static List<String> vidsOf(ItemDetail.Sku sku) {
        Set<String> vids = new LinkedHashSet<>();
        for (ItemDetail.SkuProp p : sku.skuPropList) {
            if (p.valId != null && !p.valId.isBlank()) {
                vids.add(p.valId);
            }
        }
        return new ArrayList<>(vids);
    }

    /**
     * 点齐一个组合的所有维度。
     * <p>
     * 要跑两遍：选中某个值会让另一维度的部分值变成不可选，导致同一遍里后面的点击落空。
     * 第二遍时前面的选择已经生效，通常就能补上。
     */
    private static boolean selectAll(ChromeDriver driver, List<String> vids) {
        for (int pass = 0; pass < 2; pass++) {
            boolean allDone = true;
            for (String vid : vids) {
                WebElement el = findValueItem(driver, vid);
                if (el == null) {
                    return false;
                }
                if (hasClass(el, "isSelected--")) {
                    continue;
                }
                if ("true".equals(el.getAttribute("data-disabled"))) {
                    allDone = false;
                    continue;
                }
                click(driver, el);
                sleep(CLICK_INTERVAL_MS);
            }
            if (allDone && allSelected(driver, vids)) {
                return true;
            }
        }
        return allSelected(driver, vids);
    }

    private static boolean allSelected(ChromeDriver driver, List<String> vids) {
        for (String vid : vids) {
            WebElement el = findValueItem(driver, vid);
            if (el == null || !hasClass(el, "isSelected--")) {
                return false;
            }
        }
        return true;
    }

    private static WebElement findValueItem(ChromeDriver driver, String vid) {
        try {
            return driver.findElement(By.cssSelector(
                    "div[class*='valueItem--'][data-vid='" + vid + "']"));
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hasClass(WebElement el, String fragment) {
        try {
            String cls = el.getAttribute("class");
            return cls != null && cls.contains(fragment);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void click(ChromeDriver driver, WebElement el) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'});", el);
            el.click();
        } catch (RuntimeException e) {
            // 常见于被浮层/吸顶条挡住，原生点击会 ElementClickIntercepted，JS 点击不受遮挡影响
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
            } catch (RuntimeException ignored) {
                // 点不动就让上层按「未选中」处理
            }
        }
    }

    /**
     * 等价格刷新完。
     * <p>
     * 这里最大的风险不是读不到，而是<b>读早了</b>——读到上一个规格的价格却以为是当前这个。
     * 单靠「和上一个不同」判断不行：不同规格同价很常见。单靠「文本稳定」也不行：
     * 异步取价还在路上时，旧价格同样是稳定的。所以三个条件要同时满足：
     * <ul>
     *   <li>过了最小静置时间，把「点完立刻读」这种必然读到旧值的情况排除掉；</li>
     *   <li>连续两次读到一样，说明渲染停了；</li>
     *   <li>期间没有新的 mtop 响应落地，说明取价请求不在飞行中。</li>
     * </ul>
     * 再加一条「不带『起』」，确认规格确实选全了——带「起」的是「XX 元起」的区间价。
     */
    private static String awaitPrice(ChromeDriver driver, MtopSniffer sniffer) {
        long start = System.currentTimeMillis();
        long deadline = start + PRICE_TIMEOUT_MS;
        String stable = null;
        int seenAtStable = -1;
        while (System.currentTimeMillis() < deadline) {
            sleep(PRICE_POLL_MS);
            String now = readPriceText(driver);
            int seen = sniffer == null ? -1 : sniffer.seenCount();
            if (now == null || now.isBlank()) {
                continue;
            }
            boolean settled = System.currentTimeMillis() - start >= MIN_SETTLE_MS;
            boolean quiet = seen < 0 || seen == seenAtStable;
            if (settled && quiet && now.equals(stable) && isCompletePrice(now)) {
                return now;
            }
            stable = now;
            seenAtStable = seen;
        }
        // 超时兜底：只要不是「XX 元起」的区间价就认，宁可精度差也别丢；实在不行返回 null 保持整品兜底
        return stable != null && isCompletePrice(stable) ? stable : null;
    }

    private static boolean isCompletePrice(String text) {
        return !text.contains(FROM_PRICE_MARKER) && hasNumber(text);
    }

    private static String readPriceText(ChromeDriver driver) {
        try {
            Object v = ((JavascriptExecutor) driver).executeScript(READ_PRICE_JS);
            return v == null ? null : v.toString().trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasNumber(String s) {
        return NUMBER.matcher(s).find();
    }

    /**
     * 价格区文案形如「平台加补贴\n¥1619.1\n到手参考\n¥1799」。
     * 语义同样不靠标题判断，沿用解析器那条不变量：售价不高于原价，取最小作 price、最大作 cost_price。
     */
    private static boolean applyPrice(String priceText, ItemDetail.Sku sku) {
        List<Double> nums = new ArrayList<>();
        Matcher m = NUMBER.matcher(priceText);
        while (m.find()) {
            try {
                nums.add(Double.parseDouble(m.group().replace(",", "")));
            } catch (NumberFormatException ignored) {
                // 非价格数字直接丢掉
            }
        }
        if (nums.isEmpty()) {
            return false;
        }
        sku.price = nums.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        sku.costPrice = nums.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        return true;
    }

    private static String estimateSeconds(int skuCount) {
        long ms = (long) skuCount * (PER_SKU_PAUSE_MS + MIN_SETTLE_MS + PRICE_POLL_MS + CLICK_INTERVAL_MS * 2L);
        return String.valueOf(Math.max(1, ms / 1000));
    }

    /**
     * 用保存的页面 HTML 离线校验点选依赖的三个选择器是否还有效。
     * <p>
     * 类名哈希后缀每次发版都变，一旦淘宝改了 DOM 结构，点选补价会静默失效而不报错。
     * 有了这个校验，改动能在离线复算时就被发现，不用等到真去抓才知道。
     */
    public static String validateSelectors(String pageHtml, ItemDetail detail) {
        if (pageHtml == null || pageHtml.isBlank()) {
            return "无页面 HTML，跳过";
        }
        Document doc = Jsoup.parse(pageHtml);
        int priceWraps = doc.select("div[class*=priceWrap--]").size();
        Elements items = doc.select("div[class*=valueItem--][data-vid]");
        Set<String> domVids = new LinkedHashSet<>();
        for (Element e : items) {
            domVids.add(e.attr("data-vid"));
        }
        Set<String> needVids = new LinkedHashSet<>();
        for (ItemDetail.Sku s : detail.skuList) {
            needVids.addAll(vidsOf(s));
        }
        needVids.removeAll(domVids);

        String ok = priceWraps > 0 && !domVids.isEmpty() && needVids.isEmpty() ? "可用" : "不可用";
        return ok + "（价格区 " + priceWraps + " 处，规格按钮 " + domVids.size() + " 个"
                + (needVids.isEmpty() ? "" : "，缺 vid " + needVids) + "）";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
