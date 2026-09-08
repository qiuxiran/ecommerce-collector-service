package com.san.taobao.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 批量抓取的累计统计。风控命中的序号要留下来 —— 「第几条开始出滑块」是这条路上
 * 唯一有意义的容量指标，用来试阈值。
 */
public final class BatchReport {

    private final int total;
    private final long startedAtMs = System.currentTimeMillis();
    private final Map<CrawlStatus, Integer> counts = new EnumMap<>(CrawlStatus.class);
    private final List<Integer> riskIndexes = new ArrayList<>();

    public BatchReport(int total) {
        this.total = total;
    }

    /** @param index 从 1 开始的条目序号 */
    public void accept(int index, CrawlResult result) {
        counts.merge(result.status(), 1, Integer::sum);
        if (result.hitRiskControl()) {
            riskIndexes.add(index);
        }
    }

    public int total() {
        return total;
    }

    public int count(CrawlStatus status) {
        return counts.getOrDefault(status, 0);
    }

    /** 风控命中的条目序号，按发生顺序。 */
    public List<Integer> riskIndexes() {
        return List.copyOf(riskIndexes);
    }

    public boolean hitRiskControl() {
        return !riskIndexes.isEmpty();
    }

    public long elapsedSeconds() {
        return (System.currentTimeMillis() - startedAtMs) / 1000;
    }
}
