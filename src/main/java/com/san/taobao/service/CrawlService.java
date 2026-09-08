package com.san.taobao.service;

import com.san.taobao.config.CrawlConfig;
import com.san.taobao.config.SystemProps;
import com.san.taobao.crawler.browser.BrowserSession;
import com.san.taobao.crawler.ItemCrawler;
import com.san.taobao.model.BatchReport;
import com.san.taobao.model.CrawlResult;
import com.san.taobao.repository.ResultRepository;

import java.util.List;

/**
 * 抓取业务编排（Service 层）。
 * <p>
 * 上接 {@code cli} / 未来的 {@code controller}，下接 {@code crawler} 引擎与 {@code repository} 持久化。
 * 加 Spring Boot 时在此类上加 {@code @Service} 即可。
 */
public final class CrawlService {

    private final CrawlConfig config;
    private final ItemCrawler itemCrawler;
    private final ResultRepository repository;
    private final HumanGate gate;
    private final CrawlProgressListener listener;

    public CrawlService(CrawlConfig config, ItemCrawler itemCrawler, ResultRepository repository,
                        HumanGate gate, CrawlProgressListener listener) {
        this.config = config;
        this.itemCrawler = itemCrawler;
        this.repository = repository;
        this.gate = gate;
        this.listener = listener;
    }

    public boolean prepare(BrowserSession session) {
        return gate.awaitLogin(session);
    }

    public BatchReport crawlBatch(BrowserSession session, List<String> inputs) {
        int total = inputs.size();
        BatchReport report = new BatchReport(total);
        listener.onBatchStart(total);

        for (int i = 0; i < total; i++) {
            int index = i + 1;
            String input = inputs.get(i);
            listener.onItemStart(index, total, input);

            CrawlResult result = crawlQuietly(session, input, "[" + index + "/" + total + "] ");
            persist(result, index, total);
            report.accept(index, result);
            listener.onItemDone(index, total, result);

            if (index < total) {
                SystemProps.sleepMs(config.pauseBetweenItemsMs());
            }
        }

        listener.onBatchDone(report);
        return report;
    }

    private CrawlResult crawlQuietly(BrowserSession session, String input, String context) {
        try {
            return itemCrawler.crawl(session, input, context);
        } catch (Exception e) {
            return CrawlResults.failed(input, e);
        }
    }

    private void persist(CrawlResult result, int index, int total) {
        try {
            repository.save(result);
        } catch (Exception e) {
            listener.onWarning("保存结果失败（" + index + "/" + total + "）: " + e.getMessage());
        }
        if (!result.hitRiskControl()) {
            return;
        }
        try {
            repository.saveRiskEvent(index, total, result.input());
        } catch (Exception e) {
            listener.onWarning("记录风控事件失败: " + e.getMessage());
        }
    }
}
