package com.san.taobao.service;

import com.san.taobao.crawler.browser.BrowserSession;

/**
 * 需人工介入的节点（登录、过滑块）。
 * CLI 用控制台实现；Spring Boot 后换成异步任务 + WebSocket 通知。
 */
public interface HumanGate {

    boolean awaitLogin(BrowserSession session);

    boolean awaitRiskClear(BrowserSession session, String context);
}
