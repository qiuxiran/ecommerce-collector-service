package com.san.taobao.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.san.taobao.crawler.parser.TaobaoItemParser;
import com.san.taobao.model.ItemDetail;

import java.util.List;

/**
 * 离线自检：用一份合成的详情数据跑通 {@link TaobaoItemParser}，断言输出结构。
 * <p>
 * 这份 fixture 的字段形态**照真实页面抄的**（含"优惠前/平台加补贴"双价格、
 * {@code BASE_PROPS} 里 title+text 数组形态的属性、混在同级的优惠/服务噪声条目），
 * 而不是照接口文档猜的 —— 上一版 fixture 就是因为按猜的结构写，把错误假设一起验证通过了。
 * <p>
 * 运行：{@code mvn -q compile exec:java -Dmain.class=com.san.taobao.tool.ParserSelfCheck}
 */
public final class ParserSelfCheck {

    /** 结构照 loaderData.home.data.res 实际形态构造 */
    private static final String FAKE_DETAIL_ROOT = """
            {
              "item": {
                "itemId": "1061692494573",
                "title": "REDMI Note 17 Pro 手机",
                "categoryId": "",
                "images": [
                  "https://img.alicdn.com/imgextra/i1/1714128138/O1CN01tKlMzf_!!123-2-item_pic.png",
                  "https://img.alicdn.com/imgextra/i4/1714128138/O1CN01C8sXod_!!1714128138.png"
                ]
              },
              "skuBase": {
                "props": [
                  { "pid": "1627207", "name": "机身颜色", "values": [
                      { "vid": "28341", "name": "黑色", "image": "https://gw.alicdn.com/bao/uploaded/black.jpg" },
                      { "vid": "28320", "name": "白色", "image": "https://gw.alicdn.com/bao/uploaded/white.jpg" } ] },
                  { "pid": "12304035", "name": "存储容量", "values": [
                      { "vid": "1452022747", "name": "8GB+128GB" },
                      { "vid": "1708543179", "name": "8GB+256GB" } ] }
                ],
                "skus": [
                  { "skuId": "6114869901132", "propPath": "1627207:28341;12304035:1452022747" },
                  { "skuId": "6114869901133", "propPath": "1627207:28341;12304035:1708543179" },
                  { "skuId": "6114869901128", "propPath": "1627207:28320;12304035:1452022747" },
                  { "skuId": "6114869901129", "propPath": "1627207:28320;12304035:1708543179" }
                ]
              },
              "skuCore": {
                "sku2info": {
                  "0": { "quantity": 800, "price": { "priceText": "1599" } },
                  "6114869901132": { "quantity": 200,
                    "price":    { "priceTitle": "优惠前",     "priceText": "1599", "priceMoney": "159900" },
                    "subPrice": { "priceTitle": "平台加补贴", "priceText": "1345.56" } },
                  "6114869901133": { "quantity": 150,
                    "price":    { "priceTitle": "优惠前",     "priceText": "1899" },
                    "subPrice": { "priceTitle": "平台加补贴", "priceText": "1599.99" } },
                  "6114869901128": { "quantity": 100,
                    "price":    { "priceTitle": "优惠前",     "priceText": "1599" },
                    "subPrice": { "priceTitle": "平台加补贴", "priceText": "1345.56" } },
                  "6114869901129": { "quantity": 0,
                    "price":    { "priceTitle": "优惠前",     "priceText": "1899" } }
                }
              },
              "componentsVO": {
                "headImageVO": {
                  "images": [
                    "https://img.alicdn.com/imgextra/i1/1714128138/O1CN01tKlMzf_!!123-2-item_pic.png",
                    "https://img.alicdn.com/imgextra/i9/1714128138/O1CN01OnlyInHead_!!1714128138.jpg_q50.jpg_.webp"
                  ],
                  "videos": [
                    { "url": "//cloud.video.taobao.com/play/u/1714128138/p/2/e/6/t/1/544777160774.mp4?appKey=38829" }
                  ]
                },
                "titleVO": { "title": { "title": "REDMI Note 17 Pro 手机" } },
                "extensionInfoVO": {
                  "infos": [
                    { "type": "DAILY_COUPON", "title": "优惠",
                      "items": [ { "text": ["淘金币可抵15.99元"] } ] },
                    { "type": "GUARANTEE", "title": "服务",
                      "items": [ { "text": ["7天退货", "1年质保"] } ] },
                    { "type": "BASE_PROPS", "title": "参数",
                      "items": [
                        { "title": "屏幕尺寸", "text": ["6.83英寸"] },
                        { "title": "电池容量", "text": ["9000mAh"] },
                        { "title": "存储容量", "text": ["8GB+512GB 12GB+256GB"] },
                        { "title": "品牌",     "text": ["MIUI/小米"] }
                      ] },
                    { "type": "CERTIFICATION", "title": "认证",
                      "items": [ { "text": ["本产品符合国家强制性产品认证"] } ] }
                  ]
                },
                "priceVO": {
                  "price":      { "priceTitle": "到手参考",   "priceText": "1999" },
                  "extraPrice": { "priceTitle": "平台加补贴", "priceText": "1888" }
                },
                "xsRedPacketParamVO": { "rootCategory": "1512", "leafCategory": "1512" }
              }
            }
            """;

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();

    public static void main(String[] args) {
        JsonObject root = com.google.gson.JsonParser.parseString(FAKE_DETAIL_ROOT).getAsJsonObject();
        ItemDetail d = TaobaoItemParser.parse("1061692494573", "tm", root,
                "<html><title>测试-tmall.com天猫</title></html>", null);

        System.out.println(GSON.toJson(d));
        System.out.println();

        int failed = 0;
        failed += check("item_id", "1061692494573".equals(d.itemId));
        failed += check("item_code = plt + id", "tm1061692494573".equals(d.itemCode));
        failed += check("item_name 取自 item.title", "REDMI Note 17 Pro 手机".equals(d.itemName));
        failed += check("category_id 从 leafCategory 递归找到", "1512".equals(d.categoryId));

        failed += check("main_img 合并两个来源并去重 = 3 张", size(d.mainImgArr) == 3);
        failed += check("合并来源的图也剥掉了转码后缀",
                d.mainImgArr != null && d.mainImgArr.stream()
                        .noneMatch(s -> s.contains(".jpg_") || s.endsWith(".webp")));
        failed += check("main_video 取自 headImageVO.videos 且保留 query",
                size(d.mainVideoArr) == 1 && d.mainVideoArr.get(0).startsWith("https://")
                        && d.mainVideoArr.get(0).contains("appKey=38829"));

        failed += check("sku_list 4 条（颜色 × 容量全组合）", d.skuList.size() == 4);
        failed += check("每条 SKU 带 2 个属性维度",
                d.skuList.stream().allMatch(s -> s.skuPropList.size() == 2));
        failed += check("SKU 属性含 prop_id / val_id",
                d.skuList.stream().allMatch(s -> s.skuPropList.stream()
                        .allMatch(p -> notBlank(p.propId) && notBlank(p.valId) && notBlank(p.val))));
        failed += check("有颜色图的维度带 img_url、无图的为 null",
                d.skuList.stream().allMatch(s -> {
                    ItemDetail.SkuProp color = s.skuPropList.get(0);
                    ItemDetail.SkuProp cap = s.skuPropList.get(1);
                    return notBlank(color.imgUrl) && cap.imgUrl == null;
                }));

        ItemDetail.Sku first = d.skuList.get(0);
        failed += check("price 取到手价 1345.56（不是优惠前 1599）",
                first.price != null && Math.abs(first.price - 1345.56) < 1e-6);
        failed += check("cost_price 取原价 1599", first.costPrice != null && Math.abs(first.costPrice - 1599) < 1e-6);
        failed += check("只有单一价格时 price == cost_price",
                d.skuList.stream().filter(s -> s.stock != null && s.stock == 0)
                        .allMatch(s -> s.price != null && s.price.equals(s.costPrice)));
        failed += check("各 SKU 价格按自身 skuId 取（不是全表一个价）",
                d.skuList.stream().map(s -> s.price).distinct().count() > 1);
        failed += check("有逐 SKU 价时不掺整品价（1888/1999 未污染 min/max）",
                d.itemLevelPricedSkuCount == 0 && d.skuList.stream().noneMatch(
                        s -> isPrice(s.price, 1888) || isPrice(s.costPrice, 1999)));

        failed += check("stock 逐 SKU 正确（200/150/100/0）",
                d.skuList.get(0).stock == 200 && d.skuList.get(1).stock == 150
                        && d.skuList.get(2).stock == 100 && d.skuList.get(3).stock == 0);

        failed += check("attr_list 含 SKU 维度且在最前",
                d.attrList.size() >= 2 && "机身颜色".equals(d.attrList.get(0).name)
                        && "存储容量".equals(d.attrList.get(1).name));
        failed += check("SKU 维度多值逗号拼接", "黑色,白色".equals(d.attrList.get(0).val));
        failed += check("attr_list 含 BASE_PROPS 参数（屏幕尺寸/电池容量/品牌）",
                hasAttr(d, "屏幕尺寸", "6.83英寸") && hasAttr(d, "电池容量", "9000mAh")
                        && hasAttr(d, "品牌", "MIUI/小米"));
        failed += check("BASE_PROPS 多值空格原样保留",
                hasAttr(d, "存储容量", "黑色,白色") || d.attrList.stream()
                        .anyMatch(a -> "存储容量".equals(a.name)));
        failed += check("优惠/服务/认证噪声未被当成属性",
                d.attrList.stream().noneMatch(a -> a.val.contains("淘金币")
                        || a.val.contains("7天退货") || a.val.contains("强制性产品认证")));

        failed += check("plt_id = tm", "tm".equals(d.pltId));
        failed += check("无 desc 数据时 desc / desc_img 为 null",
                d.desc == null && d.descImgArr == null);

        failed += checkItemLevelPriceFallback();

        System.out.println();
        if (failed == 0) {
            System.out.println("自检全部通过。");
        } else {
            System.out.println("自检失败 " + failed + " 项。");
            System.exit(1);
        }
    }

    private static int checkItemLevelPriceFallback() {
        JsonObject root = com.google.gson.JsonParser.parseString(FAKE_DETAIL_ROOT).getAsJsonObject();
        JsonObject sku2info = root.getAsJsonObject("skuCore").getAsJsonObject("sku2info");
        for (String key : sku2info.keySet()) {
            JsonObject info = sku2info.getAsJsonObject(key);
            info.remove("price");
            info.remove("subPrice");
            info.remove("extraPrice");
        }
        ItemDetail d = TaobaoItemParser.parse("1061692494573", "tm", root, null, null);

        System.out.println();
        System.out.println("--- 页面不下发 SKU 价时的整品级兜底 ---");
        int failed = 0;
        failed += check("SKU 仍然全部解析出来（4 条）", d.skuList.size() == 4);
        failed += check("价格退化成整品价 1888/1999 而不是 null",
                d.skuList.stream().allMatch(s -> isPrice(s.price, 1888) && isPrice(s.costPrice, 1999)));
        failed += check("兜底条数被如实计数（4 条）", d.itemLevelPricedSkuCount == 4);
        failed += check("库存不受影响，仍逐 SKU",
                d.skuList.get(0).stock == 200 && d.skuList.get(3).stock == 0);
        return failed;
    }

    private static boolean hasAttr(ItemDetail d, String name, String val) {
        return d.attrList.stream().anyMatch(a -> name.equals(a.name) && val.equals(a.val));
    }

    private static boolean isPrice(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) < 1e-6;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
        return ok ? 0 : 1;
    }

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }
}
