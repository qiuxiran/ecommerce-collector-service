package com.san.taobao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 结果落盘。全部显式 UTF-8 —— 本机 platform encoding 是 GBK，依赖默认编码会写出乱码文件。
 * 除了目标结构的 JSON，还会把原始 mtop 响应和页面 HTML 一起 dump 下来。
 * 淘宝改版时字段位置会挪，有原始件在手就能直接对照修映射，不用重跑一遍抓取。
 */
public final class OutputWriter {

    /** disableHtmlEscaping 必须开：否则视频 URL 里的 ?appKey=38829 会被转义成 \u003d */
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private final Path outputDir;

    public OutputWriter(Path outputDir) throws IOException {
        this.outputDir = outputDir;
        Files.createDirectories(outputDir);
    }

    public Path writeItemJson(ItemDetail detail) throws IOException {
        Path p = outputDir.resolve(detail.itemCode + ".json");
        write(p, GSON.toJson(detail));
        return p;
    }

    /** 单行 JSON 追加写，便于后续按行批量导入。 */
    public Path appendJsonLine(ItemDetail detail) throws IOException {
        Path p = outputDir.resolve("items.jsonl");
        Files.writeString(p, COMPACT.toJson(detail) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return p;
    }

    public Path writeRawCaptures(String itemCode, List<MtopSniffer.Capture> captures) throws IOException {
        JsonArray arr = new JsonArray();
        for (MtopSniffer.Capture c : captures) {
            JsonObject o = new JsonObject();
            o.addProperty("url", c.url());
            o.addProperty("api", c.apiName());
            o.addProperty("bodyLength", c.body().length());
            o.addProperty("body", c.body());
            arr.add(o);
        }
        Path p = outputDir.resolve(itemCode + ".raw-mtop.json");
        write(p, GSON.toJson(arr));
        return p;
    }

    /**
     * 页面内联的详情数据节点。这是现在的主数据源，比整页 HTML 小得多，
     * 改映射时直接看这个文件就够。
     */
    public Path writeDetailRoot(String itemCode, JsonObject detailRoot) throws IOException {
        Path p = outputDir.resolve(itemCode + ".detail-root.json");
        write(p, GSON.toJson(detailRoot));
        return p;
    }

    public Path writePageHtml(String itemCode, String html) throws IOException {
        Path p = outputDir.resolve(itemCode + ".page.html");
        write(p, html == null ? "" : html);
        return p;
    }

    /** 批量实验：哪一条、什么时候撞上验证页。追加写入，方便对照阈值。 */
    public Path appendRiskEvent(int index, int total, String url) throws IOException {
        Path p = outputDir.resolve("risk-events.txt");
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "\t" + index + "/" + total + "\t" + (url == null ? "" : url)
                + System.lineSeparator();
        Files.writeString(p, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return p;
    }

    /** 扁平摘要，方便在 Excel 里快速核对抓取质量。 */
    public Path appendSummaryCsv(ItemDetail d, String sourceUrl) throws IOException {
        Path p = outputDir.resolve("items-summary.csv");
        boolean isNew = !Files.exists(p);
        StringBuilder sb = new StringBuilder();
        if (isNew) {
            // BOM：Excel 打开 UTF-8 CSV 不加 BOM 会把中文显示成乱码
            sb.append('\uFEFF');
            sb.append("item_code,item_id,plt_id,item_name,sku_count,min_price,max_price,price_source,")
                    .append("total_stock,attr_count,main_img_count,desc_img_count,video_count,source_url\n");
        }
        double min = Double.MAX_VALUE;
        double max = 0;
        int totalStock = 0;
        for (ItemDetail.Sku s : d.skuList) {
            if (s.price != null) {
                min = Math.min(min, s.price);
                max = Math.max(max, s.price);
            }
            if (s.stock != null) {
                totalStock += s.stock;
            }
        }
        sb.append(csv(d.itemCode)).append(',')
                .append(csv(d.itemId)).append(',')
                .append(csv(d.pltId)).append(',')
                .append(csv(d.itemName)).append(',')
                .append(d.skuList.size()).append(',')
                .append(min == Double.MAX_VALUE ? "" : String.valueOf(min)).append(',')
                .append(max == 0 ? "" : String.valueOf(max)).append(',')
                .append(priceSource(d)).append(',')
                .append(totalStock).append(',')
                .append(d.attrList.size()).append(',')
                .append(size(d.mainImgArr)).append(',')
                .append(size(d.descImgArr)).append(',')
                .append(size(d.mainVideoArr)).append(',')
                .append(csv(sourceUrl)).append('\n');

        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return p;
    }

    /**
     * 价格精度。{@code sku} = 页面数据里直接有逐 SKU 价；{@code click} = 靠点选规格补回来的，
     * 同样精确到规格；{@code item} = 全部退化成整品展示价，规格间不区分；
     * {@code mixed} = 精确与退化并存；{@code none} = 没取到价。
     */
    private static String priceSource(ItemDetail d) {
        if (d.skuList.isEmpty() || d.skuList.stream().allMatch(s -> s.price == null)) {
            return "none";
        }
        int degraded = d.itemLevelPricedSkuCount;
        if (degraded == d.skuList.size()) {
            return "item";
        }
        if (degraded > 0) {
            return "mixed";
        }
        return d.clickPricedSkuCount > 0 ? "click" : "sku";
    }

    private void write(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"").replaceAll("[\\r\\n]+", " ");
        return "\"" + v + "\"";
    }
}
