package com.san.taobao.repository;

import com.san.taobao.model.CrawlResult;

/**
 * 抓取结果持久化（对应 Spring Boot 的 Mapper / Repository 层）。
 * 现在是写文件；商业化后换 MyBatis Mapper 写库，service 层不用改。
 */
public interface ResultRepository {

    void save(CrawlResult result) throws Exception;

    void saveRiskEvent(int index, int total, String input) throws Exception;
}
