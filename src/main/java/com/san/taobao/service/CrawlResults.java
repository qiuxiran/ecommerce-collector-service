package com.san.taobao.service;

import com.google.gson.JsonObject;
import com.san.taobao.model.CrawlResult;
import com.san.taobao.model.CrawlStatus;
import com.san.taobao.model.ItemDetail;
import com.san.taobao.model.MtopCapture;

import java.util.List;

/** {@link CrawlResult} 的工厂与查询，model 层只放纯数据。 */
public final class CrawlResults {

    private CrawlResults() {
    }

    public static CrawlResult ok(String input, String finalUrl, ItemDetail detail, JsonObject detailRoot,
                                 String pageHtml, List<MtopCapture> captures, boolean hitRiskControl) {
        return new CrawlResult(CrawlStatus.OK, input, finalUrl, detail, detailRoot, pageHtml,
                captures, hitRiskControl, null);
    }

    public static CrawlResult badInput(String input) {
        return new CrawlResult(CrawlStatus.BAD_INPUT, input, null, null, null, null, List.of(), false, null);
    }

    public static CrawlResult skipped(String input, boolean hitRiskControl) {
        return new CrawlResult(CrawlStatus.SKIPPED, input, null, null, null, null, List.of(),
                hitRiskControl, null);
    }

    public static CrawlResult failed(String input, Throwable cause) {
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new CrawlResult(CrawlStatus.FAILED, input, null, null, null, null, List.of(), false, error);
    }

    public static boolean structured(CrawlResult result) {
        return result.detailRoot() != null;
    }

    public static boolean incomplete(CrawlResult result) {
        ItemDetail detail = result.detail();
        return detail == null || !structured(result) || detail.skuList.isEmpty() || detail.itemName == null;
    }
}
