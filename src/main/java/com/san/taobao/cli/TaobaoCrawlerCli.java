package com.san.taobao.cli;

import com.san.taobao.config.CrawlConfig;
import com.san.taobao.crawler.browser.BrowserSession;
import com.san.taobao.crawler.ItemCrawler;
import com.san.taobao.repository.FileResultRepository;
import com.san.taobao.service.CrawlService;

/**
 * 命令行入口（Presentation 层）。
 * 上 Spring Boot 后，同类职责由 {@code controller} 包承接，业务仍调 {@link CrawlService}。
 */
public final class TaobaoCrawlerCli {

    private TaobaoCrawlerCli() {
    }

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.from(args);
        CrawlConfig config = CrawlConfig.fromSystemProperties();

        ConsoleReporter.printBanner();
        if (!ConsoleIo.isInteractive(options.forceInteractive())) {
            ConsoleReporter.printNonInteractiveHelp();
            return;
        }

        FileResultRepository repository = new FileResultRepository(config.outputDir());
        System.out.println("[输出] 目录: " + config.outputDir());
        System.out.println("[配置] Chrome 用户目录: " + config.chrome().profileDir());
        System.out.println("[启动] 正在准备 Chrome / chromedriver…");

        ConsoleIo io = new ConsoleIo(System.in);
        ConsoleHumanGate gate = new ConsoleHumanGate(io);
        CrawlService crawlService = new CrawlService(
                config, new ItemCrawler(config, gate), repository, gate, new ConsoleReporter(repository));

        try (BrowserSession session = BrowserSession.open(config.chrome())) {
            if (crawlService.prepare(session)) {
                new InputLoop(io, crawlService, session).run(options);
            }
        } catch (NonInteractiveException e) {
            System.out.println();
            System.out.println("[中止] 运行中标准输入变为不可用。");
            ConsoleReporter.printNonInteractiveHelp();
        }
    }
}
