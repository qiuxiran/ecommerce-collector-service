package com.san.taobao.cli;

import com.san.taobao.model.BatchReport;
import com.san.taobao.model.CrawlResult;
import com.san.taobao.model.CrawlStatus;
import com.san.taobao.model.ItemDetail;
import com.san.taobao.repository.FileResultRepository;
import com.san.taobao.service.CrawlProgressListener;
import com.san.taobao.service.CrawlResults;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 把抓取进度打成给人看的控制台输出。所有面向用户的文案都集中在这里。 */
public final class ConsoleReporter implements CrawlProgressListener {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 超过这个条数就提醒先小批量试风控阈值 */
    private static final int LARGE_BATCH_HINT = 30;

    private static final String REPROCESS_COMMAND =
            "mvn -q compile exec:java -Dmain.class=com.san.taobao.tool.ReprocessTool";

    private static final String SELF_CHECK_COMMAND =
            "mvn -B -q compile exec:java -Dmain.class=com.san.taobao.tool.ParserSelfCheck";

    private final FileResultRepository repository;
    private final long startedAtMs = System.currentTimeMillis();
    private boolean batchMode;
    private boolean riskReported;

    public ConsoleReporter(FileResultRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onBatchStart(int total) {
        batchMode = total > 1;
        if (!batchMode) {
            return;
        }
        System.out.println();
        System.out.println("[批次] 共 " + total + " 条，遇到验证会暂停，并记下是第几条触发的。");
        if (total > LARGE_BATCH_HINT) {
            System.out.println("[批次] 条数较多，连续抓很容易出滑块，建议先用十几条试阈值。");
        }
    }

    @Override
    public void onItemStart(int index, int total, String input) {
        System.out.println();
        String tag = batchMode ? "[" + index + "/" + total + "] " : "";
        System.out.println("==================== " + tag + input + " ====================");
    }

    @Override
    public void onItemDone(int index, int total, CrawlResult result) {
        switch (result.status()) {
            case OK -> printDetail(result);
            case BAD_INPUT -> System.out.println(
                    "[跳过] 无法从输入里解析出商品 ID。支持形式：完整商品链接、短链接、纯数字 ID。");
            case SKIPPED -> System.out.println("[跳过] 这一条没抓成，已放弃。");
            case FAILED -> System.out.println("[失败] " + result.error());
        }
        if (result.hitRiskControl()) {
            reportFirstRisk(index, total);
        }
    }

    @Override
    public void onBatchDone(BatchReport report) {
        if (!batchMode) {
            return;
        }
        System.out.println();
        System.out.println("==================== 批次结束 ====================");
        System.out.println("[批次] 共 " + report.total() + " 条，耗时 " + report.elapsedSeconds()
                + " 秒（" + LocalDateTime.now().format(CLOCK) + "）");
        System.out.println("       成功 " + report.count(CrawlStatus.OK)
                + " / 输入无效 " + report.count(CrawlStatus.BAD_INPUT)
                + " / 跳过 " + report.count(CrawlStatus.SKIPPED)
                + " / 失败 " + report.count(CrawlStatus.FAILED));
        if (!report.hitRiskControl()) {
            System.out.println("[风控] 全程没有触发验证页。");
            return;
        }
        List<Integer> at = report.riskIndexes();
        System.out.println("[风控] 触发 " + at.size() + " 次，首次在第 " + at.get(0) + " 条");
        System.out.println("       序号: " + at.stream().map(String::valueOf).toList());
        System.out.println("       明细已追加到输出目录的 risk-events.txt");
    }

    @Override
    public void onWarning(String message) {
        System.out.println("[警告] " + message);
    }

    private void printDetail(CrawlResult result) {
        ItemDetail d = result.detail();
        Path json = repository.itemJsonPath(d.itemCode);
        System.out.println();
        System.out.println("[结果] 数据来源：" + (CrawlResults.structured(result) ? "页面内联结构化数据" : "DOM 兜底（字段质量差）"));
        System.out.println("       item_id   : " + d.itemId);
        System.out.println("       item_name : " + (d.itemName == null ? "(未解析到)" : d.itemName));
        System.out.println("       sku_list  : " + d.skuList.size() + " 条" + priceNote(d));
        System.out.println("       attr_list : " + d.attrList.size() + " 条");
        System.out.println("       main_img  : " + size(d.mainImgArr) + " 张");
        System.out.println("       desc_img  : " + size(d.descImgArr) + " 张");
        System.out.println("       video     : " + size(d.mainVideoArr) + " 个");
        System.out.println("       JSON      : " + json);
        if (result.detailRoot() != null) {
            System.out.println("       详情原始数据: " + repository.detailRootPath(d.itemCode));
        }
        if (d.itemLevelPricedSkuCount > 0) {
            System.out.println("[提示] 有 " + d.itemLevelPricedSkuCount + " 条 SKU 的价格是整品展示价（规格间不区分）——"
                    + "页面没下发逐 SKU 价，点选也没取到，通常是已售罄不可点选的规格。");
        }
        if (CrawlResults.incomplete(result)) {
            System.out.println("[提示] 关键字段缺失。可以用 ReprocessTool 对保存的 page.html 反复离线调映射，");
            System.out.println("       不用重新抓取：" + REPROCESS_COMMAND);
        }
    }

    /** 「第几条开始出滑块」只在第一次发生时报，后面的进批次汇总。 */
    private void reportFirstRisk(int index, int total) {
        if (riskReported) {
            return;
        }
        riskReported = true;
        System.out.println("[风控] ★ 首次触发，发生在第 " + index + " / " + total
                + " 条（开始后 " + (System.currentTimeMillis() - startedAtMs) / 1000 + " 秒）。");
    }

    private static String priceNote(ItemDetail d) {
        if (d.skuList.isEmpty()) {
            return "";
        }
        long noPrice = d.skuList.stream().filter(s -> s.price == null).count();
        long exact = d.skuList.size() - d.itemLevelPricedSkuCount - d.clickPricedSkuCount - noPrice;
        return "（价格：页面 " + exact + " / 点选 " + d.clickPricedSkuCount
                + " / 整品兜底 " + d.itemLevelPricedSkuCount + " / 缺失 " + noPrice + "）";
    }

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    // ==================== 启动期文案 ====================

    public static void printBanner() {
        System.out.println("""
                ============================================================
                 淘宝商品详情抓取
                ------------------------------------------------------------
                 说明：
                  1. 会打开一个有界面的 Chrome。淘宝风控识别无头模式，必须有界面。
                  2. 首次运行需要你本人在弹出的窗口里扫码登录，登录态会持久化，
                     之后再运行不用重复登录。
                  3. 登录后选择一个 .txt（一行一条链接或商品 ID），选完立刻开始批量解析。
                  4. 遇到滑块/验证时程序会暂停，并记下是第几条触发的。
                  5. 淘宝 robots.txt 与用户协议禁止抓取。请只用自己的账号、
                     保持低频率、数据仅自用，不要外传或商用。
                ============================================================
                """);
    }

    public static void printNonInteractiveHelp() {
        System.out.println("[中止] 没有检测到交互式控制台，已在启动浏览器之前停止。");
        System.out.println();
        System.out.println("  请在真正的终端窗口里运行：");
        System.out.println("      mvn -B clean package");
        System.out.println("      java -jar target/taobao-item-crawler-demo.jar");
        System.out.println("      java -jar target/taobao-item-crawler-demo.jar urls.txt");
        System.out.println();
        System.out.println("  只想验证解析逻辑（不开浏览器、不碰淘宝）：");
        System.out.println("      " + SELF_CHECK_COMMAND);
        System.out.println();
        System.out.println("  确认终端可交互但检测有误时，加 -Dtaobao.force-interactive=true 强制继续。");
    }
}
