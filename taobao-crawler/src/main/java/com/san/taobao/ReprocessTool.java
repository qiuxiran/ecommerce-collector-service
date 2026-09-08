package com.san.taobao;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 对已保存的 {@code *.page.html} 离线重跑解析，不启动浏览器、不请求淘宝。
 * <p>
 * 抓一次页面很贵（要登录、要控频、可能触发验证），但调映射要反复迭代。把两件事拆开之后，
 * 页面存一次就能改一次解析验证一次，也避免为了调代码去反复请求淘宝。
 * <p>
 * 运行：{@code mvn -q compile exec:java -Dmain.class=com.san.taobao.ReprocessTool}
 * 可选参数：待处理的目录或具体 html 文件，默认 {@code output}。
 */
public final class ReprocessTool {

    /** 文件名形如 tm1061692494573.page.html */
    private static final Pattern FILE_NAME = Pattern.compile("^(tb|tm)(\\d+)\\.page\\.html$");

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : "output").toAbsolutePath().normalize();
        List<Path> files = collectHtmlFiles(target);
        if (files.isEmpty()) {
            System.out.println("没找到 *.page.html：" + target);
            return;
        }

        Path outDir = target.resolve("reprocessed");
        OutputWriter writer = new OutputWriter(outDir);
        System.out.println("重新解析 " + files.size() + " 个页面，结果写入 " + outDir);
        System.out.println();

        for (Path f : files) {
            Matcher m = FILE_NAME.matcher(f.getFileName().toString());
            if (!m.matches()) {
                System.out.println("跳过（文件名不含平台/ID）: " + f.getFileName());
                continue;
            }
            String pltId = m.group(1);
            String itemId = m.group(2);
            String html = Files.readString(f, StandardCharsets.UTF_8);

            JsonObject ice = IceContextExtractor.fromHtml(html);
            JsonObject root = IceContextExtractor.findDetailRoot(ice);
            ItemDetail d = TaobaoItemParser.parse(itemId, pltId, root, html, null);

            writer.writeItemJson(d);
            printSummary(d, root != null);
            System.out.println("  点选补价选择器: " + SkuPriceClicker.validateSelectors(html, d));
            System.out.println();
        }
    }

    private static void printSummary(ItemDetail d, boolean structured) {
        System.out.println("=== " + d.itemCode + (structured ? "  [结构化]" : "  [仅 DOM 兜底]") + " ===");
        System.out.println("  name      : " + truncate(d.itemName, 50));
        System.out.println("  category  : " + (d.categoryId == null || d.categoryId.isBlank() ? "(空)" : d.categoryId));
        System.out.println("  main_img  : " + count(d.mainImgArr) + "  video: " + count(d.mainVideoArr));
        System.out.println("  attr_list : " + d.attrList.size());
        if (!d.attrList.isEmpty()) {
            int show = Math.min(3, d.attrList.size());
            for (int i = 0; i < show; i++) {
                ItemDetail.Attr a = d.attrList.get(i);
                System.out.println("      " + a.name + " = " + truncate(a.val, 40));
            }
        }
        long noPrice = d.skuList.stream().filter(s -> s.price == null).count();
        System.out.println("  sku_list  : " + d.skuList.size()
                + "  （逐 SKU 价 " + (d.skuList.size() - d.itemLevelPricedSkuCount - noPrice)
                + " / 整品兜底 " + d.itemLevelPricedSkuCount + " / 无价 " + noPrice + "）");
        if (!d.skuList.isEmpty()) {
            ItemDetail.Sku s = d.skuList.get(0);
            StringBuilder props = new StringBuilder();
            for (ItemDetail.SkuProp p : s.skuPropList) {
                props.append(p.propName).append('=').append(p.val).append("  ");
            }
            System.out.println("      首条 price=" + s.price + " cost=" + s.costPrice
                    + " stock=" + s.stock + "  " + props.toString().trim());
        }
        System.out.println("  图片后缀干净: " + (d.mainImgArr == null || d.mainImgArr.stream()
                .noneMatch(x -> x.contains(".jpg_") || x.contains(".png_") || x.endsWith(".webp"))));
        System.out.println();
    }

    private static List<Path> collectHtmlFiles(Path target) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(target)) {
            files.add(target);
            return files;
        }
        if (!Files.isDirectory(target)) {
            return files;
        }
        try (Stream<Path> s = Files.list(target)) {
            s.filter(p -> p.getFileName().toString().endsWith(".page.html")).sorted().forEach(files::add);
        }
        return files;
    }

    private static int count(List<?> l) {
        return l == null ? 0 : l.size();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "(null)";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
