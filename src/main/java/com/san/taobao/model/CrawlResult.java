package com.san.taobao.model;

import com.google.gson.JsonObject;

import java.util.List;

/** 单条抓取的产出。工厂方法见 {@link com.san.taobao.service.CrawlResults}。 */
public record CrawlResult(
        CrawlStatus status,
        String input,
        String finalUrl,
        ItemDetail detail,
        JsonObject detailRoot,
        String pageHtml,
        List<MtopCapture> captures,
        boolean hitRiskControl,
        String error
) {
}
