package com.san.taobao.cli;

import com.san.taobao.config.SystemProps;
import com.san.taobao.crawler.browser.BrowserSession;
import com.san.taobao.service.HumanGate;

/**
 * 控制台版的人工介入：打印提示，等用户在浏览器里操作完按回车。
 * <p>
 * 两个循环都有次数上限。这不是为了体验，而是兜底：任何情况下都不允许无上限地
 * 反复加载淘宝页面。
 */
public final class ConsoleHumanGate implements HumanGate {

    private static final int MAX_LOGIN_PROMPTS = 10;
    private static final int MAX_RISK_RETRIES = 3;

    /** 刷新后等页面重新落地，太快复查会读到上一页 HTML。 */
    private static final long AFTER_REFRESH_MS = 2000;

    private final ConsoleIo io;

    public ConsoleHumanGate(ConsoleIo io) {
        this.io = io;
    }

    @Override
    public boolean awaitLogin(BrowserSession session) {
        System.out.println("[登录] 打开淘宝首页检查登录态…");
        session.openHome();

        for (int attempt = 1; !session.looksLoggedIn(); attempt++) {
            if (attempt > MAX_LOGIN_PROMPTS) {
                System.out.println("[登录] 连续 " + MAX_LOGIN_PROMPTS
                        + " 次未检测到登录态，已停止（避免反复请求淘宝）。若确认已登录，可 skip 跳过检查。");
                return false;
            }
            System.out.println();
            System.out.println("[登录] 未检测到登录态。请在弹出的 Chrome 窗口里完成扫码/账号登录。");
            System.out.println("       登录成功后回到这里按回车重新检查（输入 skip 跳过检查，quit 退出）。");

            String line = io.prompt();
            if ("skip".equalsIgnoreCase(line)) {
                System.out.println("[登录] 已跳过检查。若后续抓不到数据，多半就是没登录。");
                return true;
            }
            if ("quit".equalsIgnoreCase(line)) {
                System.out.println("[登录] 已退出。");
                return false;
            }
            // 登录是在同一个标签页里完成的，Cookie 直接就能读到，不必重新加载页面。
            // 只有用户把标签页导航到别处时才需要回到淘宝域，否则读不到淘宝的 Cookie。
            if (!session.onTaobaoDomain()) {
                session.openHome();
            }
        }
        System.out.println("[登录] 已登录，可以开始抓取。");
        return true;
    }

    @Override
    public boolean awaitRiskClear(BrowserSession session, String context) {
        for (int attempt = 1; attempt <= MAX_RISK_RETRIES; attempt++) {
            if (!session.isBlocked()) {
                return true;
            }
            System.out.println();
            System.out.println("[风控] " + context + "当前页面像是验证页或登录页：" + session.currentUrl());
            System.out.println("       请在浏览器窗口里手动过掉验证（滑块/登录），完成后回车重试；");
            System.out.println("       输入 skip 放弃这一条。");

            if ("skip".equalsIgnoreCase(io.prompt())) {
                return false;
            }
            session.refresh();
            SystemProps.sleepMs(AFTER_REFRESH_MS);
        }
        System.out.println("[风控] " + context + "连续 " + MAX_RISK_RETRIES
                + " 次仍未通过，放弃这一条。建议歇一会儿再试。");
        return false;
    }
}
