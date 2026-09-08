package com.san.taobao.config;

import java.nio.file.Path;

/**
 * Chrome 与 chromedriver 的启动参数。
 * <p>
 * {@code profileDir} 是多账号隔离的关键：一个淘宝账号一个目录，登录态互不污染。
 * 单机 CLI 用默认目录即可，服务化后按账号分配。
 *
 * @param profileDir     Chrome user-data-dir，登录态持久化在这里
 * @param binary         chrome 可执行文件路径，空串表示自动探测
 * @param proxy          Chrome 代理，形如 {@code host:port}，空串表示不用
 * @param headless       无头模式。淘宝风控会识别，仅用于排查驱动问题
 * @param driverPath     指定 chromedriver 路径，非空则完全跳过自动下载
 * @param driverProxy    下载驱动用的代理，空串时回落到 {@code proxy}
 * @param useChinaMirror WebDriverManager 是否走华为云镜像
 * @param fallbackCdn    WebDriverManager 失败后是否回退 CfT / npmmirror
 */
public record ChromeConfig(
        Path profileDir,
        String binary,
        String proxy,
        boolean headless,
        String driverPath,
        String driverProxy,
        boolean useChinaMirror,
        boolean fallbackCdn
) {

    public static ChromeConfig fromSystemProperties() {
        return new ChromeConfig(
                SystemProps.path("taobao.profile.dir", defaultProfileDir()),
                SystemProps.text("taobao.chrome.binary", ""),
                SystemProps.text("taobao.chrome.proxy", ""),
                SystemProps.flag("taobao.chrome.headless", false),
                SystemProps.text("taobao.chromedriver.path", ""),
                SystemProps.text("taobao.wdm.proxy", ""),
                SystemProps.flag("taobao.wdm.use-china-mirror", true),
                SystemProps.flag("taobao.wdm.fallback-cdn", true));
    }

    /** 换一个 profile 目录，其余参数不变。服务化后按账号切换用这个。 */
    public ChromeConfig withProfileDir(Path dir) {
        return new ChromeConfig(dir, binary, proxy, headless, driverPath, driverProxy,
                useChinaMirror, fallbackCdn);
    }

    /** 驱动下载优先用专用代理，没配就跟 Chrome 共用一个。 */
    public String effectiveDriverProxy() {
        return driverProxy.isBlank() ? proxy : driverProxy;
    }

    private static Path defaultProfileDir() {
        return Path.of(System.getProperty("user.home"), ".cache", "taobao-crawler", "profile");
    }
}
