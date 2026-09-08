package com.san.taobao.crawler;

import com.google.gson.JsonObject;
import com.san.taobao.config.CrawlConfig;
import com.san.taobao.crawler.browser.BrowserSession;
import com.san.taobao.crawler.browser.DetailPage;
import com.san.taobao.crawler.browser.SkuPriceClicker;
import com.san.taobao.crawler.parser.IceContextExtractor;
import com.san.taobao.crawler.parser.ItemLinks;
import com.san.taobao.crawler.parser.TaobaoItemParser;
import com.san.taobao.model.CrawlResult;
import com.san.taobao.model.ItemDetail;
import com.san.taobao.service.CrawlResults;
import com.san.taobao.service.HumanGate;

/** 单条商品抓取引擎（基础设施层，不含业务编排与持久化）。 */
public final class ItemCrawler {

    private final CrawlConfig config;
    private final HumanGate gate;
    private final SkuPriceClicker priceClicker;

    public ItemCrawler(CrawlConfig config, HumanGate gate) {
        this.config = config;
        this.gate = gate;
        this.priceClicker = config.skuClickEnabled() ? new SkuPriceClicker(config.skuClickMax()) : null;
    }

    public CrawlResult crawl(BrowserSession session, String input, String context) {
        DetailPage page = new DetailPage(session);
        String targetUrl = resolveDetailUrl(page, input);
        if (targetUrl == null) {
            return CrawlResults.badInput(input);
        }

        page.open(targetUrl);
        boolean hitRisk = false;
        if (session.isBlocked()) {
            hitRisk = true;
            if (!gate.awaitRiskClear(session, context)) {
                return CrawlResults.skipped(input, true);
            }
        }

        page.awaitDetailData();
        page.scrollThrough();

        String pageHtml = page.html();
        String finalUrl = page.currentUrl();

        JsonObject iceContext = IceContextExtractor.fromJson(page.readIceContextJson());
        if (iceContext == null) {
            iceContext = IceContextExtractor.fromHtml(pageHtml);
        }
        JsonObject detailRoot = IceContextExtractor.findDetailRoot(iceContext);

        String descHtml = config.fetchDesc() && detailRoot != null
                ? page.fetchDescHtml(TaobaoItemParser.descPageUrl(detailRoot))
                : null;

        String itemId = firstNonNull(ItemLinks.extractItemId(finalUrl), ItemLinks.extractItemId(targetUrl));
        ItemDetail detail = TaobaoItemParser.parse(
                itemId, ItemLinks.platformOfUrl(finalUrl).code(), detailRoot, pageHtml, descHtml);

        if (priceClicker != null) {
            priceClicker.fillMissingPrices(session.driver(), session.sniffer(), detail);
        }
        if (session.isBlocked()) {
            hitRisk = true;
            gate.awaitRiskClear(session, context);
        }

        return CrawlResults.ok(input, finalUrl, detail, detailRoot, pageHtml,
                session.sniffer().drain(), hitRisk);
    }

    private String resolveDetailUrl(DetailPage page, String input) {
        if (ItemLinks.isBareItemId(input)) {
            return ItemLinks.detailUrl(input);
        }
        String id = ItemLinks.extractItemId(input);
        if (id != null) {
            return ItemLinks.detailUrl(id);
        }
        if (!ItemLinks.isHttpUrl(input)) {
            return null;
        }
        String resolved = page.resolveItemIdByNavigation(input);
        return resolved == null ? null : ItemLinks.detailUrl(resolved);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
