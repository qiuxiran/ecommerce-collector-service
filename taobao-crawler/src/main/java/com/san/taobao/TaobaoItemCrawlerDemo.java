package com.san.taobao;

import com.google.gson.JsonObject;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 淘宝商品详情结构化抓取 demo：登录后选择/输入一个 .txt 链接列表 → 批量解析。
 * 单条链接仍然可用，方便对照。
 * <p>
 * 设计上刻意不做以下事情：
 * <ul>
 *   <li>不伪造 mtop 签名 / 设备指纹 —— 让真实页面自己发请求，我们只读它拿到的响应；</li>
 *   <li>不自动过滑块 —— 检测到验证就停下来交给人处理，处理完回车继续。</li>
 * </ul>
 * 相应代价是必须有界面运行、需要人工登录一次，抓取速度也上不去。这是这条路的固有成本。
 */
public final class TaobaoItemCrawlerDemo {

    private static final Pattern ITEM_ID_IN_URL = Pattern.compile("[?&]id=(\\d{6,})");
    private static final Pattern BARE_ITEM_ID = Pattern.compile("^\\d{6,}$");

    /** 每条商品抓取之间的间隔，别调小 —— 连续快速请求是触发风控最快的方式 */
    private static final long PAUSE_BETWEEN_ITEMS_MS = 2500;

    /** 登录提示的最大次数。兜底用：任何情况下都不允许无上限地反复加载淘宝页面。 */
    private static final int MAX_LOGIN_PROMPTS = 10;

    /** 是否额外打开图文详情页取 desc_img_arr（多一次页面加载） */
    private static final boolean fetchDesc =
            Boolean.parseBoolean(System.getProperty("taobao.fetch-desc", "true"));

    private static final List<String> BLOCK_MARKERS = List.of(
            "_____tmd_____", "滑动验证", "安全验证", "请输入验证码", "亲，请稍后再试",
            "punish", "captcha", "行为异常");

    public static void main(String[] args) throws Exception {
        printBanner();
        if (!hasInteractiveConsole()) {
            printNonInteractiveHelp();
            return;
        }

        Path outputDir = Path.of(System.getProperty("taobao.output.dir", "output")).toAbsolutePath().normalize();
        OutputWriter writer = new OutputWriter(outputDir);
        System.out.println("[输出] 目录: " + outputDir);

        ChromeLauncher launcher = new ChromeLauncher();
        System.out.println("[配置] Chrome 用户目录: " + launcher.getProfileDir()
                + "（登录态存这里，删掉即需重新登录）");
        System.out.println("[启动] 正在准备 Chrome / chromedriver…");

        ChromeDriver driver = launcher.launch();
        MtopSniffer sniffer = new MtopSniffer(driver);
        sniffer.installOnNewDocument();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        try {
            ensureLoggedIn(driver, in);
            interactiveLoop(driver, sniffer, writer, in, args);
        } catch (NonInteractiveException e) {
            System.out.println();
            System.out.println("[中止] 运行中标准输入变为不可用。");
            printNonInteractiveHelp();
        } finally {
            System.out.println("[退出] 关闭浏览器…");
            try {
                driver.quit();
            } catch (Exception ignored) {
                // 用户可能已手动关掉窗口
            }
        }
    }

    /**
     * 这个程序全程依赖人工输入（登录、输链接、过验证），非交互环境下每个等待输入的点都会立刻 EOF。
     * 所以在碰网络之前就先拦掉，避免退化成对淘宝的无人值守请求循环。
     */
    private static boolean hasInteractiveConsole() {
        if (Boolean.parseBoolean(System.getProperty("taobao.force-interactive", "false"))) {
            return true;
        }
        return System.console() != null;
    }

    private static void printNonInteractiveHelp() {
        System.out.println("[中止] 没有检测到交互式控制台，已在启动浏览器之前停止。");
        System.out.println();
        System.out.println("  请在真正的终端窗口里运行：");
        System.out.println("      mvn -B clean package");
        System.out.println("      java -jar target/taobao-item-crawler-demo.jar");
        System.out.println("      java -jar target/taobao-item-crawler-demo.jar urls.txt");
        System.out.println();
        System.out.println("  只想验证解析逻辑（不开浏览器、不碰淘宝）：");
        System.out.println("      mvn -B -q compile exec:java -Dmain.class=com.san.taobao.ParserSelfCheck");
        System.out.println();
        System.out.println("  确认终端可交互但检测有误时，加 -Dtaobao.force-interactive=true 强制继续。");
    }

    private static void printBanner() {
        System.out.println("""
                ============================================================
                 淘宝商品详情抓取 demo
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

    // ==================== 登录 ====================

    private static void ensureLoggedIn(ChromeDriver driver, BufferedReader in) throws Exception {
        System.out.println("[登录] 打开淘宝首页检查登录态…");
        driver.get("https://www.taobao.com/");
        sleep(2500);

        for (int attempt = 1; !looksLoggedIn(driver); attempt++) {
            if (attempt > MAX_LOGIN_PROMPTS) {
                throw new IllegalStateException("连续 " + MAX_LOGIN_PROMPTS
                        + " 次未检测到登录态，已停止（避免反复请求淘宝）。"
                        + "若确认已登录，用 skip 跳过检查。");
            }
            System.out.println();
            System.out.println("[登录] 未检测到登录态。请在弹出的 Chrome 窗口里完成扫码/账号登录，");
            System.out.println("       登录成功后回到这里按回车重新检查（输入 skip 跳过检查，quit 退出）。");
            System.out.print("> ");

            String line = readLineOrThrow(in);
            if ("skip".equalsIgnoreCase(line)) {
                System.out.println("[登录] 已跳过检查。若后续抓不到数据，多半就是没登录。");
                return;
            }
            if ("quit".equalsIgnoreCase(line)) {
                throw new IllegalStateException("用户主动退出登录检查");
            }
            // 登录是在同一个标签页里完成的，Cookie 直接就能读到，不必重新加载页面。
            // 只有当用户把标签页导航到别处时才需要回到淘宝域，否则读不到淘宝的 Cookie。
            if (!safeLower(driver.getCurrentUrl()).contains("taobao.com")) {
                driver.get("https://www.taobao.com/");
                sleep(2500);
            }
        }
        System.out.println("[登录] 已登录，可以开始抓取。");
    }

    /**
     * 读一行，EOF 抛 {@link NonInteractiveException}。
     * <p>
     * 非交互环境下 {@code readLine()} 会立刻返回 null。把 null 当成"重试"会让等待输入的循环退化成
     * 高频请求循环 —— 之前就是这么把淘宝首页刷了两百多秒，所以这里必须硬性区分 EOF 和空行。
     */
    private static String readLineOrThrow(BufferedReader in) throws java.io.IOException {
        String line = in.readLine();
        if (line == null) {
            throw new NonInteractiveException();
        }
        return line.trim();
    }

    /** 标准输入不可用（EOF）。 */
    private static final class NonInteractiveException extends RuntimeException {
        NonInteractiveException() {
            super("标准输入已到 EOF，需要交互式控制台");
        }
    }

    /** 淘宝登录后会下发 tracknick / lgc 等昵称类 Cookie，用它判断比解析 DOM 稳。 */
    private static boolean looksLoggedIn(ChromeDriver driver) {
        for (String name : List.of("tracknick", "lgc", "_nk_", "dnk")) {
            Cookie c = driver.manage().getCookieNamed(name);
            if (c != null && c.getValue() != null && !c.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 主循环 ====================

    private static void interactiveLoop(ChromeDriver driver, MtopSniffer sniffer,
                                        OutputWriter writer, BufferedReader in,
                                        String[] args) throws Exception {
        String preset = System.getProperty("taobao.urls.file", "");
        if (preset != null && !preset.isBlank()) {
            dispatchInput(driver, sniffer, writer, in, preset.trim());
        } else if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            dispatchInput(driver, sniffer, writer, in, args[0].trim());
        }

        while (true) {
            System.out.println();
            System.out.println("------------------------------------------------------------");
            System.out.println("请输入链接列表 .txt（一行一条），或单条商品链接/ID：");
            System.out.println("  直接回车打开文件选择框；输入 quit 退出。");
            System.out.print("> ");
            String line = in.readLine();
            if (line == null) {
                throw new NonInteractiveException();
            }
            String input = line.trim();
            if (input.isEmpty()) {
                Path picked = pickTxtFile();
                if (picked == null) {
                    System.out.println("[取消] 未选择文件。");
                    continue;
                }
                runBatch(driver, sniffer, writer, in, picked);
                continue;
            }
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                return;
            }
            dispatchInput(driver, sniffer, writer, in, input);
        }
    }

    /** 路径指向已有 .txt/.csv 就批量跑，否则当单条链接处理。 */
    private static void dispatchInput(ChromeDriver driver, MtopSniffer sniffer, OutputWriter writer,
                                      BufferedReader in, String input) throws Exception {
        Path asFile = tryUrlListFile(input);
        if (asFile != null) {
            runBatch(driver, sniffer, writer, in, asFile);
            return;
        }
        if (looksLikeListFilename(input)) {
            System.out.println("[跳过] 文件不存在: " + Path.of(input).toAbsolutePath().normalize());
            return;
        }
        try {
            crawlOne(driver, sniffer, writer, in, input, "");
        } catch (Exception e) {
            System.out.println("[失败] " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        sleep(PAUSE_BETWEEN_ITEMS_MS);
    }

    private static boolean looksLikeListFilename(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".list");
    }

    private static Path tryUrlListFile(String input) {
        try {
            Path p = Path.of(input);
            if (!Files.isRegularFile(p)) {
                return null;
            }
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (looksLikeListFilename(name)) {
                return p.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            // 不是合法路径，按单条链接处理
        }
        return null;
    }

    private static Path pickTxtFile() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 系统皮肤失败就用默认
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择商品链接列表（.txt，一行一条）");
        chooser.setFileFilter(new FileNameExtensionFilter("文本文件 (*.txt)", "txt"));
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setCurrentDirectory(Path.of(".").toAbsolutePath().normalize().toFile());

        JFrame parent = new JFrame();
        parent.setAlwaysOnTop(true);
        parent.setUndecorated(true);
        parent.setLocationRelativeTo(null);
        parent.setVisible(true);
        try {
            int ret = chooser.showOpenDialog(parent);
            if (ret == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            }
            return null;
        } finally {
            parent.dispose();
        }
    }

    private static List<String> loadUrlLines(Path file) throws java.io.IOException {
        List<String> out = new ArrayList<>();
        List<String> raw = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < raw.size(); i++) {
            String line = raw.get(i);
            if (i == 0 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static void runBatch(ChromeDriver driver, MtopSniffer sniffer, OutputWriter writer,
                                 BufferedReader in, Path file) throws Exception {
        List<String> urls = loadUrlLines(file);
        if (urls.isEmpty()) {
            System.out.println("[批次] 文件是空的（空行和 # 注释会忽略）: " + file);
            return;
        }
        System.out.println();
        System.out.println("[批次] 文件: " + file.toAbsolutePath());
        System.out.println("[批次] 共 " + urls.size() + " 条，条间隔 " + PAUSE_BETWEEN_ITEMS_MS + " ms。");
        System.out.println("[批次] 遇到验证会暂停，并记下是第几条触发的。");
        if (urls.size() > 30) {
            System.out.println("[批次] 条数较多，连续抓很容易出滑块，建议先用十几条试阈值。");
        }

        BatchStats stats = new BatchStats(urls.size());
        long started = System.currentTimeMillis();

        for (int i = 0; i < urls.size(); i++) {
            int n = i + 1;
            String tag = "[" + n + "/" + urls.size() + "] ";
            String item = urls.get(i);
            System.out.println();
            System.out.println("==================== " + tag + item + " ====================");
            CrawlOutcome out;
            try {
                out = crawlOne(driver, sniffer, writer, in, item, tag);
            } catch (Exception e) {
                System.out.println("[失败] " + tag + e.getClass().getSimpleName() + ": " + e.getMessage());
                out = new CrawlOutcome(CrawlStatus.FAILED, false);
            }
            if (out.hitRiskControl()) {
                try {
                    writer.appendRiskEvent(n, urls.size(), item);
                } catch (Exception e) {
                    System.out.println("[风控] 写 risk-events.txt 失败: " + e.getMessage());
                }
                if (stats.riskHits == 0) {
                    long elapsedSec = (System.currentTimeMillis() - started) / 1000;
                    System.out.println("[风控] ★ 本次批量首次触发，发生在第 " + n + " / " + urls.size()
                            + " 条（开始后 " + elapsedSec + " 秒）。");
                }
            }
            stats.accept(n, out);
            if (i < urls.size() - 1) {
                sleep(PAUSE_BETWEEN_ITEMS_MS);
            }
        }
        stats.print(started);
    }

    private static CrawlOutcome crawlOne(ChromeDriver driver, MtopSniffer sniffer, OutputWriter writer,
                                         BufferedReader in, String input, String tag) throws Exception {
        String targetUrl = resolveItemUrl(driver, input);
        if (targetUrl == null) {
            System.out.println("[跳过] " + tag + "无法从输入里解析出商品 ID。支持形式：完整商品链接、短链接、纯数字 ID。");
            return new CrawlOutcome(CrawlStatus.BAD_INPUT, false);
        }
        System.out.println("[抓取] " + tag + targetUrl);
        driver.get(targetUrl);

        if (!sniffer.isCdpHookInstalled()) {
            // CDP 不可用时钩子只能后注入，必须刷新一次才能抓到首屏请求
            sniffer.injectNow();
            driver.navigate().refresh();
        }
        sleep(1500);

        GateOutcome gate = passBlockGate(driver, in, tag);
        if (!gate.proceed()) {
            return new CrawlOutcome(CrawlStatus.SKIPPED, gate.hitRiskControl());
        }
        boolean hitRisk = gate.hitRiskControl();

        System.out.println("[等待] 等页面注入商品数据…");
        if (!waitForIceContext(driver, 25)) {
            System.out.println("[提示] 25 秒内没等到 __ICE_APP_CONTEXT__。会退化为 DOM 兜底解析（字段质量差很多），"
                    + "并把页面 dump 下来供排查。");
        }
        scrollPage(driver);
        sleep(1200);

        String pageHtml = driver.getPageSource();
        String finalUrl = driver.getCurrentUrl();

        // 商品数据是 SSR 内联在页面里的，优先直接读全局变量；读不到再从 HTML 文本里提取
        JsonObject ice = IceContextExtractor.fromDriver(driver);
        if (ice == null) {
            ice = IceContextExtractor.fromHtml(pageHtml);
        }
        JsonObject detailRoot = IceContextExtractor.findDetailRoot(ice);
        if (detailRoot == null) {
            System.out.println("[提示] 未能在页面数据里定位到详情节点（skuBase / item+componentsVO）。");
        }

        String descHtml = fetchDescHtml(driver, detailRoot);

        String itemId = firstNonNull(extractItemId(finalUrl), extractItemId(targetUrl));
        String pltId = finalUrl != null && finalUrl.toLowerCase(Locale.ROOT).contains("tmall") ? "tm" : "tb";

        ItemDetail detail = TaobaoItemParser.parse(itemId, pltId, detailRoot, pageHtml, descHtml);

        // 页面没下发逐 SKU 价时，回到详情页点选规格把真实价格补回来
        SkuPriceClicker.fillMissingPrices(driver, sniffer, detail);

        if (looksBlocked(driver)) {
            System.out.println("[风控] " + tag + "补价过程中页面进入验证。");
            passBlockGate(driver, in, tag);
            hitRisk = true;
        }

        // 点选会触发页面异步取价，抓包放在补价之后，价格接口的响应才会落进 raw-mtop
        List<MtopSniffer.Capture> captures = sniffer.drain();

        Path jsonPath = writer.writeItemJson(detail);
        writer.appendJsonLine(detail);
        writer.appendSummaryCsv(detail, finalUrl);
        writer.writePageHtml(detail.itemCode, pageHtml);
        writer.writeRawCaptures(detail.itemCode, captures);
        Path rootPath = detailRoot == null ? null : writer.writeDetailRoot(detail.itemCode, detailRoot);

        printResult(detail, detailRoot != null, jsonPath, rootPath);
        return new CrawlOutcome(CrawlStatus.OK, hitRisk);
    }

    /**
     * 详情图在单独的图文详情页里（{@code item.pcADescUrl}），主页面数据里没有。
     * 开新标签页取完就关掉，避免影响主页面的登录态与滚动位置。
     */
    private static String fetchDescHtml(ChromeDriver driver, JsonObject detailRoot) {
        if (!fetchDesc || detailRoot == null) {
            return null;
        }
        String url = TaobaoItemParser.descPageUrl(detailRoot);
        if (url == null) {
            return null;
        }
        String mainWindow = driver.getWindowHandle();
        try {
            System.out.println("[详情] 打开图文详情页取详情图…");
            driver.switchTo().newWindow(WindowType.TAB);
            driver.get(url);
            sleep(2500);
            scrollPage(driver);
            sleep(1200);
            return driver.getPageSource();
        } catch (Exception e) {
            System.out.println("[详情] 取图文详情失败（不影响主数据）: " + e.getMessage());
            return null;
        } finally {
            try {
                if (!driver.getWindowHandle().equals(mainWindow)) {
                    driver.close();
                }
                driver.switchTo().window(mainWindow);
            } catch (Exception e) {
                System.out.println("[详情] 关闭详情标签页异常: " + e.getMessage());
            }
        }
    }

    private static void printResult(ItemDetail d, boolean structured, Path jsonPath, Path rootPath) {
        System.out.println();
        System.out.println("[结果] 数据来源：" + (structured ? "页面内联结构化数据" : "DOM 兜底（字段质量差）"));
        System.out.println("       item_id   : " + d.itemId);
        System.out.println("       item_name : " + (d.itemName == null ? "(未解析到)" : d.itemName));
        System.out.println("       sku_list  : " + d.skuList.size() + " 条" + priceSourceNote(d));
        System.out.println("       attr_list : " + d.attrList.size() + " 条");
        System.out.println("       main_img  : " + count(d.mainImgArr) + " 张");
        System.out.println("       desc_img  : " + count(d.descImgArr) + " 张");
        System.out.println("       video     : " + count(d.mainVideoArr) + " 个");
        System.out.println("       JSON      : " + jsonPath);
        if (rootPath != null) {
            System.out.println("       详情原始数据: " + rootPath);
        }
        if (d.itemLevelPricedSkuCount > 0) {
            System.out.println("[提示] 有 " + d.itemLevelPricedSkuCount + " 条 SKU 的价格是整品展示价（规格间不区分）——"
                    + "页面没下发逐 SKU 价，点选也没取到，通常是已售罄不可点选的规格。");
        }
        if (!structured || d.skuList.isEmpty() || d.itemName == null) {
            System.out.println("[提示] 关键字段缺失。可以用 ReprocessTool 对保存的 page.html 反复离线调映射，");
            System.out.println("       不用重新抓取：mvn -q compile exec:java -Dmain.class=com.san.taobao.ReprocessTool");
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static int count(List<?> l) {
        return l == null ? 0 : l.size();
    }

    private static String priceSourceNote(ItemDetail d) {
        if (d.skuList.isEmpty()) {
            return "";
        }
        int fallback = d.itemLevelPricedSkuCount;
        int clicked = d.clickPricedSkuCount;
        long noPrice = d.skuList.stream().filter(s -> s.price == null).count();
        long exact = d.skuList.size() - fallback - clicked - noPrice;
        return "（价格：页面 " + exact + " / 点选 " + clicked
                + " / 整品兜底 " + fallback + " / 缺失 " + noPrice + "）";
    }

    // ==================== 风控门禁 ====================

    /**
     * @return proceed=true 表示页面正常可以继续解析；hitRiskControl=true 表示这次出现过验证页
     */
    private static GateOutcome passBlockGate(ChromeDriver driver, BufferedReader in, String tag) throws Exception {
        boolean hit = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (!looksBlocked(driver)) {
                return new GateOutcome(true, hit);
            }
            hit = true;
            System.out.println();
            System.out.println("[风控] " + tag + "当前页面像是验证页或登录页：" + driver.getCurrentUrl());
            System.out.println("       请在浏览器窗口里手动过掉验证（滑块/登录），完成后回车重试；");
            System.out.println("       输入 skip 放弃这一条。");
            System.out.print("> ");
            if ("skip".equalsIgnoreCase(readLineOrThrow(in))) {
                return new GateOutcome(false, true);
            }
            driver.navigate().refresh();
            sleep(2000);
        }
        System.out.println("[风控] " + tag + "连续 3 次仍未通过，放弃这一条。建议歇一会儿再试。");
        return new GateOutcome(false, true);
    }

    private static boolean looksBlocked(ChromeDriver driver) {
        String url = safeLower(driver.getCurrentUrl());
        if (url.contains("login.taobao.com") || url.contains("login.tmall.com") || url.contains("punish")) {
            return true;
        }
        String html;
        try {
            html = driver.getPageSource();
        } catch (Exception e) {
            return false;
        }
        if (html == null) {
            return false;
        }
        // 正常商品页也可能出现 captcha 之类的字样，所以只在页面明显过短时才认定被拦
        boolean tooShort = html.length() < 20000;
        String lower = html.toLowerCase(Locale.ROOT);
        for (String marker : BLOCK_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return tooShort || marker.equals("_____tmd_____");
            }
        }
        return false;
    }

    // ==================== 页面交互 ====================

    /** 等 SSR 数据注入完成。判据是详情节点可定位，而不只是全局变量存在。 */
    private static boolean waitForIceContext(ChromeDriver driver, int timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Object ok = driver.executeScript(
                        "try {"
                                + "  var c = window.__ICE_APP_CONTEXT__;"
                                + "  return !!c && JSON.stringify(c).indexOf('skuBase') >= 0;"
                                + "} catch (e) { return false; }");
                if (Boolean.TRUE.equals(ok)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 页面还在导航中，继续等
            }
            sleep(700);
        }
        return false;
    }

    private static void scrollPage(ChromeDriver driver) {
        try {
            JavascriptExecutor js = driver;
            for (double ratio : new double[]{0.25, 0.5, 0.75, 1.0}) {
                js.executeScript("window.scrollTo(0, document.body.scrollHeight * " + ratio + ");");
                sleep(900);
            }
            js.executeScript("window.scrollTo(0, 0);");
        } catch (Exception e) {
            System.out.println("[滚动] 失败（不影响主数据）: " + e.getMessage());
        }
    }

    // ==================== 链接解析 ====================

    /**
     * 把用户输入统一成商品详情页 URL。
     * 短链接（e.tb.cn 之类）无法本地解析，交给浏览器跳转后再从最终 URL 里取 ID。
     */
    private static String resolveItemUrl(ChromeDriver driver, String input) {
        if (BARE_ITEM_ID.matcher(input).matches()) {
            return "https://item.taobao.com/item.htm?id=" + input;
        }
        String id = extractItemId(input);
        if (id != null) {
            return "https://item.taobao.com/item.htm?id=" + id;
        }
        if (!input.startsWith("http")) {
            return null;
        }
        System.out.println("[解析] 链接里没有 id 参数，先让浏览器跳转一次…");
        try {
            driver.get(input);
            sleep(3000);
            String resolved = extractItemId(driver.getCurrentUrl());
            if (resolved != null) {
                return "https://item.taobao.com/item.htm?id=" + resolved;
            }
        } catch (Exception e) {
            System.out.println("[解析] 跳转失败: " + e.getMessage());
        }
        return null;
    }

    private static String extractItemId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = ITEM_ID_IN_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum CrawlStatus { OK, BAD_INPUT, SKIPPED, FAILED }

    private record CrawlOutcome(CrawlStatus status, boolean hitRiskControl) {}

    private record GateOutcome(boolean proceed, boolean hitRiskControl) {}

    private static final class BatchStats {
        final int total;
        int ok;
        int badInput;
        int skipped;
        int failed;
        int riskHits;
        Integer firstRiskAt;
        final List<Integer> riskAt = new ArrayList<>();

        BatchStats(int total) {
            this.total = total;
        }

        void accept(int n, CrawlOutcome out) {
            switch (out.status()) {
                case OK -> ok++;
                case BAD_INPUT -> badInput++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
            if (out.hitRiskControl()) {
                riskHits++;
                if (firstRiskAt == null) {
                    firstRiskAt = n;
                }
                riskAt.add(n);
            }
        }

        void print(long startedMs) {
            long sec = (System.currentTimeMillis() - startedMs) / 1000;
            System.out.println();
            System.out.println("==================== 批次结束 ====================");
            System.out.println("[批次] 共 " + total + " 条，耗时 " + sec + " 秒（"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "）");
            System.out.println("       成功 " + ok + " / 输入无效 " + badInput
                    + " / 跳过 " + skipped + " / 失败 " + failed);
            if (riskHits == 0) {
                System.out.println("[风控] 全程没有触发验证页。");
            } else {
                System.out.println("[风控] 触发 " + riskHits + " 次，首次在第 " + firstRiskAt + " 条");
                System.out.println("       序号: " + String.join(", ",
                        riskAt.stream().map(String::valueOf).toList()));
                System.out.println("       明细已追加到输出目录的 risk-events.txt");
            }
        }
    }
}
