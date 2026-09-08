package com.san.taobao.service;

import com.san.taobao.model.BatchReport;
import com.san.taobao.model.CrawlResult;

/** 抓取进度通知。CLI 打印；Web 层可换成 SSE / WebSocket。 */
public interface CrawlProgressListener {

    CrawlProgressListener NOOP = new CrawlProgressListener() {
    };

    default void onBatchStart(int total) {
    }

    default void onItemStart(int index, int total, String input) {
    }

    default void onItemDone(int index, int total, CrawlResult result) {
    }

    default void onBatchDone(BatchReport report) {
    }

    default void onWarning(String message) {
    }
}
