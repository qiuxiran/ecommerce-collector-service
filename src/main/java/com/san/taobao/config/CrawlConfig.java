package com.san.taobao.config;

import java.nio.file.Path;

/**
 * 一次抓取运行的全部配置。所有 {@code -Dtaobao.*} 系统属性只在
 * {@link #fromSystemProperties()} 这一处读取，其余代码只认这个对象 ——
 * 以后换成 Spring 的 {@code @ConfigurationProperties} 或按租户下发，都只改装配处。
 *
 * @param outputDir           结果输出目录
 * @param chrome              浏览器启动参数
 * @param fetchDesc           是否另开图文详情页取 {@code desc_img_arr}（多一次页面加载）
 * @param pauseBetweenItemsMs 条间隔。<b>别调小</b>，连续快速请求是触发风控最快的方式，
 *                            所以刻意不提供系统属性开关
 * @param skuClickEnabled     页面不下发逐 SKU 价时，是否点选规格补价
 * @param skuClickMax         点选补价的规格数上限，超过就跳过，避免几百次点击
 */
public record CrawlConfig(
        Path outputDir,
        ChromeConfig chrome,
        boolean fetchDesc,
        long pauseBetweenItemsMs,
        boolean skuClickEnabled,
        int skuClickMax
) {

    private static final long DEFAULT_PAUSE_BETWEEN_ITEMS_MS = 2500;

    public static CrawlConfig fromSystemProperties() {
        return new CrawlConfig(
                SystemProps.path("taobao.output.dir", Path.of("output")).toAbsolutePath().normalize(),
                ChromeConfig.fromSystemProperties(),
                SystemProps.flag("taobao.fetch-desc", true),
                DEFAULT_PAUSE_BETWEEN_ITEMS_MS,
                SystemProps.flag("taobao.sku-click", true),
                SystemProps.number("taobao.sku-click-max", 200));
    }
}
