package com.san.taobao.repository;

import com.san.taobao.model.CrawlResult;
import com.san.taobao.model.CrawlStatus;
import com.san.taobao.model.ItemDetail;

import java.io.IOException;
import java.nio.file.Path;

/** 本地文件持久化实现。Spring Boot 后可加 {@code @Repository}。 */
public final class FileResultRepository implements ResultRepository {

    private final OutputWriter writer;

    public FileResultRepository(Path outputDir) throws IOException {
        this.writer = new OutputWriter(outputDir);
    }

    @Override
    public void save(CrawlResult result) throws IOException {
        if (result.status() != CrawlStatus.OK || result.detail() == null) {
            return;
        }
        ItemDetail detail = result.detail();
        writer.writeItemJson(detail);
        writer.appendJsonLine(detail);
        writer.appendSummaryCsv(detail, result.finalUrl());
        writer.writePageHtml(detail.itemCode, result.pageHtml());
        writer.writeRawCaptures(detail.itemCode, result.captures());
        if (result.detailRoot() != null) {
            writer.writeDetailRoot(detail.itemCode, result.detailRoot());
        }
    }

    @Override
    public void saveRiskEvent(int index, int total, String input) throws IOException {
        writer.appendRiskEvent(index, total, input);
    }

    public Path itemJsonPath(String itemCode) {
        return writer.itemJsonPath(itemCode);
    }

    public Path detailRootPath(String itemCode) {
        return writer.detailRootPath(itemCode);
    }
}
