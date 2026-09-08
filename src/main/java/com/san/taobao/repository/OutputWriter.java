package com.san.taobao.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.san.taobao.model.ItemDetail;
import com.san.taobao.model.MtopCapture;
import com.san.taobao.model.PriceSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 抓取结果的文件写入。 */
public final class OutputWriter {

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

    public Path itemJsonPath(String itemCode) {
        return outputDir.resolve(itemCode + ".json");
    }

    public Path detailRootPath(String itemCode) {
        return outputDir.resolve(itemCode + ".detail-root.json");
    }

    public Path writeItemJson(ItemDetail detail) throws IOException {
        Path p = itemJsonPath(detail.itemCode);
        write(p, GSON.toJson(detail));
        return p;
    }

    public Path appendJsonLine(ItemDetail detail) throws IOException {
        Path p = outputDir.resolve("items.jsonl");
        Files.writeString(p, COMPACT.toJson(detail) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return p;
    }

    public Path writeRawCaptures(String itemCode, List<MtopCapture> captures) throws IOException {
        JsonArray arr = new JsonArray();
        for (MtopCapture c : captures) {
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

    public Path writeDetailRoot(String itemCode, JsonObject detailRoot) throws IOException {
        Path p = detailRootPath(itemCode);
        write(p, GSON.toJson(detailRoot));
        return p;
    }

    public Path writePageHtml(String itemCode, String html) throws IOException {
        Path p = outputDir.resolve(itemCode + ".page.html");
        write(p, html == null ? "" : html);
        return p;
    }

    public Path appendRiskEvent(int index, int total, String url) throws IOException {
        Path p = outputDir.resolve("risk-events.txt");
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "\t" + index + "/" + total + "\t" + (url == null ? "" : url)
                + System.lineSeparator();
        Files.writeString(p, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return p;
    }

    public Path appendSummaryCsv(ItemDetail d, String sourceUrl) throws IOException {
        Path p = outputDir.resolve("items-summary.csv");
        boolean isNew = !Files.exists(p);
        StringBuilder sb = new StringBuilder();
        if (isNew) {
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
                .append(priceSourceOf(d).code()).append(',')
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

    private static PriceSource priceSourceOf(ItemDetail d) {
        if (d.skuList.isEmpty() || d.skuList.stream().allMatch(s -> s.price == null)) {
            return PriceSource.NONE;
        }
        int degraded = d.itemLevelPricedSkuCount;
        if (degraded == d.skuList.size()) {
            return PriceSource.ITEM;
        }
        if (degraded > 0) {
            return PriceSource.MIXED;
        }
        return d.clickPricedSkuCount > 0 ? PriceSource.CLICK : PriceSource.SKU;
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
