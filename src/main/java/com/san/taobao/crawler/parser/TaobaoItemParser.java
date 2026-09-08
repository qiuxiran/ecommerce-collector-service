package com.san.taobao.crawler.parser;

import com.san.taobao.model.ItemDetail;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把详情数据节点（{@link IceContextExtractor#findDetailRoot}）映射成 {@link ItemDetail}。
 * <p>
 * 字段路径全部按抓取的真实淘宝/天猫页面核对过，不是按接口文档猜的。几个容易踩的点：
 * <ul>
 *   <li><b>属性不是 name/value 结构。</b>在 {@code componentsVO.extensionInfoVO.infos} 里
 *       {@code type=BASE_PROPS} 那一项下，形如 {@code {"title":"屏幕尺寸","text":["6.83英寸"]}}，
 *       同级还混着优惠、花呗、服务、认证等非属性条目，必须按 type 过滤。</li>
 *   <li><b>价格语义是反的。</b>{@code price.priceTitle} 实测为"优惠前"（即原价），
 *       真正的到手价在 {@code subPrice} / {@code extraPrice}。直接把 {@code price} 当售价会偏高。</li>
 *   <li><b>结构化图片是干净 URL</b>，DOM 里的缩略图带 {@code _q50.jpg_.webp} 之类的转码后缀，
 *       所以优先用结构化字段，DOM 只兜底。</li>
 * </ul>
 */
public final class TaobaoItemParser {

    private static final Pattern ALICDN_IMG = Pattern.compile(
            "(?:https?:)?//[\\w.-]*alicdn\\.com/[^\"'\\\\\\s>)]+?\\.(?:jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TAOBAO_VIDEO = Pattern.compile(
            "(?:https?:)?//cloud\\.video\\.taobao\\.com/[^\"'\\\\\\s>)]+?\\.mp4(?:\\?[^\"'\\\\\\s>)]*)?",
            Pattern.CASE_INSENSITIVE);

    /** 取到第一个图片扩展名为止，剥掉 {@code _q50.jpg_.webp} / {@code _430x430q90.jpg} 等转码后缀 */
    private static final Pattern IMG_BASE = Pattern.compile(
            "^(.*?\\.(?:jpg|jpeg|png|webp))(?:_.*)?$", Pattern.CASE_INSENSITIVE);

    /** extensionInfoVO.infos 里明确不是商品属性的条目类型 */
    private static final Set<String> NON_ATTR_INFO_TYPES = Set.of(
            "DAILY_COUPON", "CAN_BE_ENJOYED_AGAIN", "GUARANTEE", "CERTIFICATION",
            "SERVICE", "SERVICES", "DELIVERY", "PROMOTION", "COUPON", "INSTALLMENT");

    private TaobaoItemParser() {
    }

    /**
     * @param itemId     链接里解析出的商品 ID
     * @param pltId      平台标识：tb / tm
     * @param detailRoot 详情数据节点，可为 null（此时全靠 DOM 兜底）
     * @param pageHtml   渲染后页面 HTML，用于 DOM 兜底
     * @param descHtml   图文详情页 HTML，可为 null
     */
    public static ItemDetail parse(String itemId, String pltId, JsonObject detailRoot,
                                   String pageHtml, String descHtml) {
        ItemDetail out = new ItemDetail();
        out.itemId = itemId;
        out.pltId = pltId;
        out.itemCode = pltId + itemId;

        if (detailRoot != null) {
            mapFromDetailRoot(detailRoot, out);
        }

        // DOM 只在结构化取不到时兜底：DOM 里的价格可能是分期价/券后价，图片带转码后缀，质量明显更差
        if (isBlank(out.itemName)) {
            out.itemName = titleFromDom(pageHtml);
        }
        if (isEmpty(out.mainImgArr)) {
            out.mainImgArr = nullIfEmpty(harvestImagesFromMainPic(pageHtml));
        }
        if (out.skuList.isEmpty()) {
            ItemDetail.Sku single = singleSkuFromDom(pageHtml, out.mainImgArr);
            if (single != null) {
                out.skuList.add(single);
            }
        }

        if (descHtml != null) {
            out.descImgArr = nullIfEmpty(harvestImages(descHtml));
            out.descVideoArr = nullIfEmpty(harvestVideos(descHtml));
            String text = Jsoup.parse(descHtml).text().replaceAll("\\s{2,}", " ").trim();
            out.desc = text.length() > 20 ? text : null;
        }
        return out;
    }

    /**
     * 图文详情页地址（{@code item.pcADescUrl}）。详情图不在主页面数据里，要再开一个页面才能拿到。
     *
     * @return 绝对 URL，取不到返回 null
     */
    public static String descPageUrl(JsonObject detailRoot) {
        JsonObject item = obj(detailRoot, "item");
        String url = firstNonBlank(str(item, "pcADescUrl"), str(item, "descUrl"), str(item, "wapDescUrl"));
        return isBlank(url) ? null : absUrl(url);
    }

    // ==================== 结构化映射 ====================

    private static void mapFromDetailRoot(JsonObject root, ItemDetail out) {
        JsonObject item = obj(root, "item");
        JsonObject componentsVO = obj(root, "componentsVO");
        JsonObject headImage = obj(componentsVO, "headImageVO");

        // ---- 标题 ----
        String title = str(item, "title");
        if (isBlank(title)) {
            title = str(obj(obj(componentsVO, "titleVO"), "title"), "title");
        }
        if (!isBlank(title)) {
            out.itemName = title;
        }

        // ---- 类目 ----
        // item.categoryId 实测为空；leafCategory 埋在埋点参数里，层级不固定，按 key 递归找。
        String cat = firstNonBlank(str(item, "categoryId"), findFirstStringByKey(root, "leafCategory"));
        if (!isBlank(cat)) {
            out.categoryId = cat;
        }

        // ---- 主图 / 主视频 ----
        // item.images 与 headImageVO.images 会互有遗漏（实测有商品前者 1 张、后者 2 张），
        // 所以合并去重而不是二选一。item.images 在前，保持主图顺序。
        List<String> images = new ArrayList<>(strList(item, "images"));
        images.addAll(strList(headImage, "images"));
        out.mainImgArr = nullIfEmpty(cleanImageList(images));

        List<String> videos = new ArrayList<>(videoUrls(arr(headImage, "videos")));
        videos.addAll(videoUrls(arr(item, "videos")));
        out.mainVideoArr = nullIfEmpty(dedupe(videos));

        // ---- SKU ----
        JsonObject skuBase = obj(root, "skuBase");
        JsonObject sku2info = obj(obj(root, "skuCore"), "sku2info");
        JsonObject defaultInfo = obj(sku2info, "0");

        Map<String, PropValue> valueIndex = new LinkedHashMap<>();
        List<ItemDetail.Attr> skuAttrs = indexSkuProps(skuBase, valueIndex);

        // 部分商品（skuItem.hideOtherPrice=true）页面根本不下发单 SKU 价格，sku2info 里只有库存。
        // 这类商品用整品级 priceVO 兜底，比留 null 有用，但精度会降级，所以计数上报。
        JsonObject priceVO = obj(componentsVO, "priceVO");

        JsonArray skus = arr(skuBase, "skus");
        if (skus != null) {
            for (JsonElement se : skus) {
                if (!se.isJsonObject()) {
                    continue;
                }
                JsonObject s = se.getAsJsonObject();
                JsonObject info = obj(sku2info, firstNonBlank(str(s, "skuId"), str(s, "id")));
                out.skuList.add(buildSku(s, info != null ? info : defaultInfo, priceVO, valueIndex, out));
            }
        }
        // 单 SKU 商品没有 skuBase.skus，用默认价/库存或商品级 priceVO 兜一条
        if (out.skuList.isEmpty()) {
            ItemDetail.Sku sku = new ItemDetail.Sku();
            applyPricesWithFallback(defaultInfo, priceVO, sku, out);
            sku.stock = stockOf(defaultInfo);
            sku.imgUrl = isEmpty(out.mainImgArr) ? null : out.mainImgArr.get(0);
            if (sku.price != null || sku.stock != null) {
                out.skuList.add(sku);
            }
        }

        // ---- 属性：SKU 维度在前，商品参数在后 ----
        Set<String> seen = new LinkedHashSet<>();
        for (ItemDetail.Attr a : skuAttrs) {
            if (seen.add(a.name)) {
                out.attrList.add(a);
            }
        }
        for (ItemDetail.Attr a : baseProps(componentsVO)) {
            if (seen.add(a.name)) {
                out.attrList.add(a);
            }
        }
    }

    /** 建立 {@code pid:vid → 属性值} 索引，同时产出 SKU 维度属性（多值逗号拼接）。 */
    private static List<ItemDetail.Attr> indexSkuProps(JsonObject skuBase, Map<String, PropValue> valueIndex) {
        List<ItemDetail.Attr> attrs = new ArrayList<>();
        JsonArray props = arr(skuBase, "props");
        if (props == null) {
            return attrs;
        }
        for (JsonElement pe : props) {
            if (!pe.isJsonObject()) {
                continue;
            }
            JsonObject p = pe.getAsJsonObject();
            String pid = firstNonBlank(str(p, "pid"), str(p, "propId"));
            String pname = firstNonBlank(str(p, "name"), str(p, "propName"));
            List<String> valNames = new ArrayList<>();
            JsonArray values = arr(p, "values");
            if (values == null) {
                continue;
            }
            for (JsonElement ve : values) {
                if (!ve.isJsonObject()) {
                    continue;
                }
                JsonObject v = ve.getAsJsonObject();
                String vid = firstNonBlank(str(v, "vid"), str(v, "valueId"));
                String vname = firstNonBlank(str(v, "name"), str(v, "value"));
                String vimg = cleanImageUrl(firstNonBlank(str(v, "image"), str(v, "img")));
                valueIndex.put(pid + ":" + vid, new PropValue(pid, pname, vid, vname, blankToNull(vimg)));
                if (!isBlank(vname)) {
                    valNames.add(vname);
                }
            }
            if (!isBlank(pname) && !valNames.isEmpty()) {
                attrs.add(new ItemDetail.Attr(pname, String.join(",", valNames)));
            }
        }
        return attrs;
    }

    private static ItemDetail.Sku buildSku(JsonObject skuNode, JsonObject info, JsonObject itemLevelPrice,
                                           Map<String, PropValue> valueIndex, ItemDetail out) {
        ItemDetail.Sku sku = new ItemDetail.Sku();
        applyPricesWithFallback(info, itemLevelPrice, sku, out);
        sku.stock = stockOf(info);

        for (String pair : safe(str(skuNode, "propPath")).split(";")) {
            PropValue pv = valueIndex.get(pair.trim());
            if (pv == null) {
                continue;
            }
            ItemDetail.SkuProp sp = new ItemDetail.SkuProp();
            sp.propId = pv.pid;
            sp.propName = pv.pname;
            sp.valId = pv.vid;
            sp.val = pv.vname;
            sp.imgUrl = pv.img;
            sku.skuPropList.add(sp);
            if (isBlank(sku.imgUrl) && pv.img != null) {
                sku.imgUrl = pv.img;
            }
        }
        if (isBlank(sku.imgUrl) && !isEmpty(out.mainImgArr)) {
            sku.imgUrl = out.mainImgArr.get(0);
        }
        return sku;
    }

    private record PropValue(String pid, String pname, String vid, String vname, String img) {
    }

    /**
     * 价格。
     * <p>
     * 同一个 SKU 会给出多个价格对象（{@code price} 标题为"优惠前"、{@code subPrice} 为"平台加补贴"…），
     * 语义靠标题文案区分并不可靠（文案随活动变）。这里用一个更稳的不变量：
     * <b>售价一定不高于原价</b>，所以取最小值作 {@code price}、最大值作 {@code cost_price}。
     * 只有一个价格时两者相同。
     */
    private static void applyPricesWithFallback(JsonObject info, JsonObject itemLevelPrice,
                                                ItemDetail.Sku sku, ItemDetail out) {
        if (applyPrices(info, sku)) {
            return;
        }
        if (applyPrices(itemLevelPrice, sku)) {
            out.itemLevelPricedSkuCount++;
        }
    }

    /**
     * 从单个价格容器里取价，取到返回 true。
     * <p>
     * 两级价格<b>不能混在一起</b>比较：SKU 级 1769/2299 和整品级 1345/1599 混算会得出 1345/2299，
     * 两个数都不属于任何真实 SKU。所以由调用方按优先级依次尝试。
     */
    private static boolean applyPrices(JsonObject src, ItemDetail.Sku sku) {
        if (src == null) {
            return false;
        }
        List<Double> candidates = new ArrayList<>();
        for (String key : List.of("price", "subPrice", "extraPrice")) {
            Double d = priceTextOf(obj(src, key));
            if (d != null) {
                candidates.add(d);
            }
        }
        for (String key : List.of("oriPrice", "originalPrice", "originPrice", "priceText")) {
            Double d = parsePrice(str(src, key));
            if (d != null) {
                candidates.add(d);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        sku.price = candidates.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        sku.costPrice = candidates.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        return true;
    }

    private static Double priceTextOf(JsonObject priceNode) {
        if (priceNode == null) {
            return null;
        }
        Double d = parsePrice(str(priceNode, "priceText"));
        if (d != null) {
            return d;
        }
        Double cents = parseDouble(str(priceNode, "priceMoney"));
        return cents == null ? null : Math.round(cents) / 100.0;
    }

    private static Integer stockOf(JsonObject info) {
        if (info == null) {
            return null;
        }
        for (String key : List.of("quantity", "stock")) {
            Double d = parseDouble(str(info, key));
            if (d != null) {
                return (int) Math.round(d);
            }
        }
        return null;
    }

    /**
     * 商品参数。
     * 优先取 {@code type=BASE_PROPS}；取不到再放宽为"排除已知非属性类型"，避免把优惠券、服务、认证当成属性。
     */
    private static List<ItemDetail.Attr> baseProps(JsonObject componentsVO) {
        JsonArray infos = arr(obj(componentsVO, "extensionInfoVO"), "infos");
        if (infos == null) {
            return List.of();
        }
        List<ItemDetail.Attr> strict = collectInfoAttrs(infos, true);
        return strict.isEmpty() ? collectInfoAttrs(infos, false) : strict;
    }

    private static List<ItemDetail.Attr> collectInfoAttrs(JsonArray infos, boolean strictBaseProps) {
        List<ItemDetail.Attr> out = new ArrayList<>();
        for (JsonElement ie : infos) {
            if (!ie.isJsonObject()) {
                continue;
            }
            JsonObject info = ie.getAsJsonObject();
            String type = str(info, "type").toUpperCase(Locale.ROOT);
            if (strictBaseProps) {
                if (!"BASE_PROPS".equals(type) && !"参数".equals(str(info, "title"))) {
                    continue;
                }
            } else if (NON_ATTR_INFO_TYPES.contains(type)) {
                continue;
            }
            JsonArray items = arr(info, "items");
            if (items == null) {
                continue;
            }
            for (JsonElement it : items) {
                if (!it.isJsonObject()) {
                    continue;
                }
                JsonObject node = it.getAsJsonObject();
                String name = firstNonBlank(str(node, "title"), str(node, "name"));
                String val = firstNonBlank(String.join(",", strList(node, "text")), str(node, "value"));
                if (!isBlank(name) && !isBlank(val) && val.length() <= 300) {
                    out.add(new ItemDetail.Attr(name, val));
                }
            }
        }
        return out;
    }

    private static List<String> videoUrls(JsonArray videos) {
        List<String> out = new ArrayList<>();
        if (videos == null) {
            return out;
        }
        for (JsonElement el : videos) {
            if (el.isJsonPrimitive()) {
                out.add(absUrl(el.getAsString()));
                continue;
            }
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject v = el.getAsJsonObject();
            String url = firstNonBlank(str(v, "url"), str(v, "videoUrl"), str(v, "videoUrlV2"), str(v, "playUrl"));
            if (!isBlank(url)) {
                out.add(absUrl(url));
            }
        }
        return out;
    }

    /** 按 key 递归找第一个非空字符串值。用于 leafCategory 这类层级不固定的字段。 */
    private static String findFirstStringByKey(JsonElement root, String key) {
        Deque<JsonElement> queue = new ArrayDeque<>();
        queue.add(root);
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
            String hit = str(o, key);
            if (!isBlank(hit)) {
                return hit;
            }
            for (String k : o.keySet()) {
                queue.add(o.get(k));
            }
        }
        return "";
    }

    // ==================== DOM 兜底 ====================

    private static String titleFromDom(String pageHtml) {
        if (isBlank(pageHtml)) {
            return null;
        }
        Document doc = Jsoup.parse(pageHtml);
        for (String sel : List.of("h1", "[class*=mainTitle]", "[class*=ItemTitle]", "[class*=itemTitle]")) {
            Element e = doc.selectFirst(sel);
            if (e != null && !e.text().isBlank()) {
                return e.text().trim();
            }
        }
        String t = doc.title();
        if (isBlank(t)) {
            return null;
        }
        return t.replaceAll("[-—]\\s*(tmall\\.com天猫|淘宝网|天猫).*$", "").trim();
    }

    /** 只取主图区域，避免把"猜你喜欢"等推荐位的图混进 main_img_arr。 */
    private static List<String> harvestImagesFromMainPic(String pageHtml) {
        if (isBlank(pageHtml)) {
            return null;
        }
        Document doc = Jsoup.parse(pageHtml);
        Set<String> out = new LinkedHashSet<>();
        for (String sel : List.of("[class*=thumbnail] img", "[class*=mainPic] img",
                "[class*=PicGallery] img", "[class*=gallery] img")) {
            for (Element img : doc.select(sel)) {
                String src = firstNonBlank(img.attr("src"), img.attr("data-src"));
                if (src.contains("alicdn.com")) {
                    out.add(cleanImageUrl(src));
                }
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    private static ItemDetail.Sku singleSkuFromDom(String pageHtml, List<String> mainImgs) {
        if (isBlank(pageHtml)) {
            return null;
        }
        Document doc = Jsoup.parse(pageHtml);
        Double price = null;
        for (String sel : List.of("[class*=priceText]", "[class*=priceInt]")) {
            Element e = doc.selectFirst(sel);
            if (e == null) {
                continue;
            }
            price = parsePrice(e.text());
            if (price != null) {
                break;
            }
        }
        if (price == null) {
            return null;
        }
        ItemDetail.Sku sku = new ItemDetail.Sku();
        sku.price = price;
        sku.costPrice = price;
        sku.imgUrl = isEmpty(mainImgs) ? null : mainImgs.get(0);
        return sku;
    }

    // ==================== 正则收割 ====================

    static List<String> harvestImages(String raw) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = ALICDN_IMG.matcher(safe(raw));
        while (m.find() && out.size() < 80) {
            String u = cleanImageUrl(m.group());
            if (!isBlank(u)) {
                out.add(u);
            }
        }
        return new ArrayList<>(out);
    }

    static List<String> harvestVideos(String raw) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TAOBAO_VIDEO.matcher(safe(raw));
        while (m.find() && out.size() < 10) {
            out.add(absUrl(m.group().replace("\\/", "/")));
        }
        return new ArrayList<>(out);
    }

    // ==================== 小工具 ====================

    /**
     * 归一化图片 URL：补协议，并截断到第一个图片扩展名。
     * DOM 里的 src 形如 {@code ..._pic.png_q50.jpg_.webp}，截断后与结构化字段给出的干净 URL 一致。
     */
    static String cleanImageUrl(String url) {
        if (isBlank(url)) {
            return "";
        }
        String u = absUrl(url.trim().replace("\\/", "/"));
        Matcher m = IMG_BASE.matcher(u);
        return m.matches() ? m.group(1) : u;
    }

    private static String absUrl(String url) {
        if (isBlank(url)) {
            return "";
        }
        String u = url.trim();
        return u.startsWith("//") ? "https:" + u : u;
    }

    private static List<String> dedupe(List<String> raw) {
        return new ArrayList<>(new LinkedHashSet<>(raw));
    }

    private static List<String> cleanImageList(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            String u = cleanImageUrl(s);
            if (!isBlank(u)) {
                out.add(u);
            }
        }
        return new ArrayList<>(out);
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null || key == null || !o.has(key)) {
            return null;
        }
        JsonElement el = o.get(key);
        return el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String key) {
        if (o == null || !o.has(key)) {
            return null;
        }
        JsonElement el = o.get(key);
        return el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) {
            return "";
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : "";
    }

    private static List<String> strList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonArray a = arr(o, key);
        if (a == null) {
            return out;
        }
        for (JsonElement el : a) {
            if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    private static Double parsePrice(String s) {
        if (isBlank(s)) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        return m.find() ? parseDouble(m.group(1)) : null;
    }

    private static Double parseDouble(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... parts) {
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                return p.trim();
            }
        }
        return "";
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isEmpty(List<?> l) {
        return l == null || l.isEmpty();
    }

    private static <T> List<T> nullIfEmpty(List<T> l) {
        return l == null || l.isEmpty() ? null : l;
    }
}
