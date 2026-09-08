package com.san.taobao.cli;

import com.san.taobao.crawler.browser.BrowserSession;
import com.san.taobao.service.CrawlService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 控制台主循环：把用户输入变成一批待抓链接，交给 {@link CrawlService}。
 * <p>
 * 单条链接和链接列表走的是同一个批量入口（单条 = 长度 1 的批次），不再有两份并行逻辑。
 */
final class InputLoop {

    private final ConsoleIo io;
    private final CrawlService crawlService;
    private final BrowserSession session;

    InputLoop(ConsoleIo io, CrawlService crawlService, BrowserSession session) {
        this.io = io;
        this.crawlService = crawlService;
        this.session = session;
    }

    void run(CliOptions options) {
        if (options.hasPresetInput()) {
            dispatch(options.presetInput());
        }
        while (true) {
            System.out.println();
            System.out.println("------------------------------------------------------------");
            System.out.println("请输入链接列表 .txt（一行一条），或单条商品链接/ID：");
            System.out.println("  直接回车打开文件选择框；输入 quit 退出。");

            String input = io.prompt();
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                return;
            }
            if (input.isEmpty()) {
                Path picked = UrlListSource.pickFile();
                if (picked == null) {
                    System.out.println("[取消] 未选择文件。");
                    continue;
                }
                crawlFile(picked);
                continue;
            }
            dispatch(input);
        }
    }

    /** 路径指向已有列表文件就批量跑，否则当单条链接处理。 */
    private void dispatch(String input) {
        Path file = UrlListSource.asExistingListFile(input);
        if (file != null) {
            crawlFile(file);
            return;
        }
        if (UrlListSource.looksLikeListFilename(input)) {
            System.out.println("[跳过] 文件不存在: " + Path.of(input).toAbsolutePath().normalize());
            return;
        }
        crawlService.crawlBatch(session, List.of(input));
    }

    private void crawlFile(Path file) {
        List<String> urls;
        try {
            urls = UrlListSource.readLines(file);
        } catch (IOException e) {
            System.out.println("[跳过] 读取失败: " + file + " — " + e.getMessage());
            return;
        }
        if (urls.isEmpty()) {
            System.out.println("[批次] 文件是空的（空行和 # 注释会忽略）: " + file);
            return;
        }
        System.out.println();
        System.out.println("[批次] 文件: " + file);
        crawlService.crawlBatch(session, urls);
    }
}
