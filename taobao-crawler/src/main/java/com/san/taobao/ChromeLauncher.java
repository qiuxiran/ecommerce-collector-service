package com.san.taobao;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Chrome + chromedriver 启动引导。
 * <p>
 * 驱动解析链（本地 path → 缓存 → WebDriverManager/华为镜像 → CfT 对象存储 → npmmirror）
 * 移植自主工程 {@code JobCrawlerService}，去掉 Spring 依赖，配置改由系统属性提供。
 * <p>
 * 与猎聘爬虫的两处关键差异：
 * <ol>
 *   <li><b>默认有界面运行</b>。淘宝的 Baxia 风控会识别 {@code --headless}，无头模式下商品接口不会返回数据。</li>
 *   <li><b>使用固定 user-data-dir 而非临时目录</b>。登录态需要跨次运行复用，否则每次都要重新扫码。</li>
 * </ol>
 */
public final class ChromeLauncher {

    private static final String HUAWEI_CHROMEDRIVER_MIRROR = "https://mirrors.huaweicloud.com/chromedriver/";
    private static final String CFT_PUBLIC_PREFIX = "https://storage.googleapis.com/chrome-for-testing-public/";
    private static final String NPMMIRROR_CHROMEDRIVER = "https://registry.npmmirror.com/-/binary/chromedriver/";



    private final String chromeBinary = prop("taobao.chrome.binary", "");
    private final String chromeProxy = prop("taobao.chrome.proxy", "");
    private final String chromedriverPath = prop("taobao.chromedriver.path", "");
    private final String wdmProxy = prop("taobao.wdm.proxy", "");
    private final boolean wdmUseChinaMirror = Boolean.parseBoolean(prop("taobao.wdm.use-china-mirror", "true"));
    private final boolean wdmFallbackCdn = Boolean.parseBoolean(prop("taobao.wdm.fallback-cdn", "true"));
    private final boolean headless = Boolean.parseBoolean(prop("taobao.chrome.headless", "false"));

    private final Path profileDir = Path.of(prop("taobao.profile.dir",
            Path.of(System.getProperty("user.home"), ".cache", "taobao-crawler", "profile").toString()));

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public Path getProfileDir() {
        return profileDir;
    }

    public ChromeDriver launch() throws Exception {
        Files.createDirectories(profileDir);
        ensureProfileNotInUse();
        ChromeOptions options = buildOptions();

        File driverExe = resolveChromedriverExecutable();
        ChromeDriver driver;
        try {
            if (driverExe != null) {
                ChromeDriverService service = new ChromeDriverService.Builder()
                        .usingDriverExecutable(driverExe)
                        .build();
                driver = new ChromeDriver(service, options);
            } else {
                setupWebDriverManagerChromeDriver();
                driver = new ChromeDriver(options);
            }
        } catch (Exception e) {
            throw wrapLaunchFailure(e);
        }
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(90));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(1));
        return driver;
    }

    private ChromeOptions buildOptions() {
        ChromeOptions options = new ChromeOptions();
        if (headless) {
            System.out.println("[警告] taobao.chrome.headless=true。淘宝风控会识别无头模式，"
                    + "商品接口很可能返回空数据，仅用于排查驱动问题。");
            options.addArguments("--headless=new", "--disable-gpu");
        }
        options.addArguments(
                "--user-data-dir=" + profileDir.toAbsolutePath().normalize(),
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--window-size=1500,1000",
                "--lang=zh-CN",
                "--disable-blink-features=AutomationControlled",
                "--disable-sync",
                "--no-first-run",
                "--no-default-browser-check",
                "--remote-allow-origins=*",
                //新
                "--disable-gpu",//禁用显卡
                "--disable-gpu-rasterization", //抹除显卡指纹
                "--disable-reading-from-canvas",
                "--disk-cache-size=104857600",
                "--ignore-certificate-errors",
                "--disable-web-security"
        );
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        if (!chromeBinary.isBlank()) {
            options.setBinary(chromeBinary);
        }
        if (!chromeProxy.isBlank()) {
            String p = chromeProxy;
            if (!p.startsWith("http://") && !p.startsWith("https://") && !p.startsWith("socks5://")) {
                p = "http://" + p;
            }
            options.addArguments("--proxy-server=" + p);
            System.out.println("[配置] Chrome 使用代理: " + p);
        }
        return options;
    }

    /**
     * 同一 {@code user-data-dir} 只能被一个 Chrome 占用。上次异常退出后进程常还在，
     * 再启动就会报 SessionNotCreated / Chrome instance exited。
     */
    private void ensureProfileNotInUse() {
        Path abs = profileDir.toAbsolutePath().normalize();
        List<Long> pids = chromePidsUsingProfile(abs);
        if (pids.isEmpty()) {
            return;
        }
        throw new IllegalStateException("用户目录已被占用，无法再开一个 Chrome：" + abs
                + "。占用进程 PID: " + pids
                + "。请先关掉这个爬虫弹出的 Chrome，再重新运行。"
                + "任务管理器里也可结束这些 chrome.exe。");
    }

    private static List<Long> chromePidsUsingProfile(Path profileAbs) {
        List<Long> out = new ArrayList<>();
        String needle = profileAbs.toString();
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "process", "where",
                    "name='chrome.exe'", "get", "ProcessId,CommandLine", "/FORMAT:LIST");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String text = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset());
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out;
            }
            Long currentPid = null;
            boolean matches = false;
            for (String raw : text.split("\\r?\\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    if (matches && currentPid != null) {
                        out.add(currentPid);
                    }
                    currentPid = null;
                    matches = false;
                    continue;
                }
                if (line.startsWith("CommandLine=")) {
                    matches = line.contains(needle) && !line.contains("--type=");
                } else if (line.startsWith("ProcessId=")) {
                    try {
                        currentPid = Long.parseLong(line.substring("ProcessId=".length()).trim());
                    } catch (NumberFormatException ignored) {
                        currentPid = null;
                    }
                }
            }
            if (matches && currentPid != null) {
                out.add(currentPid);
            }
        } catch (Exception ignored) {
            Path lock = profileAbs.resolve("lockfile");
            if (Files.exists(lock)) {
                out.add(-1L);
            }
        }
        return out;
    }

    private RuntimeException wrapLaunchFailure(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("SessionNotCreated") || msg.contains("exited") || msg.contains("Chrome instance")) {
            return new IllegalStateException("Chrome 启动后立刻退出。最常见原因是用户目录已被占用："
                    + profileDir.toAbsolutePath().normalize()
                    + "。请关掉占用该目录的 Chrome 窗口后再跑。原始错误: " + msg, e);
        }
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new IllegalStateException("启动 Chrome 失败: " + msg, e);
    }

    // ==================== chromedriver 解析====================

    private File resolveChromedriverExecutable() {
        if (!chromedriverPath.isBlank()) {
            File f = new File(chromedriverPath);
            if (!f.isFile()) {
                throw new IllegalStateException("taobao.chromedriver.path 不是有效文件: " + f.getAbsolutePath());
            }
            System.out.println("[配置] 使用指定的 chromedriver: " + f.getAbsolutePath());
            return f;
        }
        return null;
    }

    private void setupWebDriverManagerChromeDriver() throws Exception {
        String fullChromeVersion = null;
        try {
            fullChromeVersion = readChromeFourPartVersionFromOs();
            Path cached = resolveCachedChromedriverByExactVersion(fullChromeVersion);
            if (cached != null) {
                System.setProperty("webdriver.chrome.driver", cached.toAbsolutePath().toString());
                System.out.println("[驱动] 命中本地缓存，跳过网络探测: " + cached);
                return;
            }
        } catch (Exception e) {
            System.out.println("[驱动] 读取本机 Chrome 版本失败，继续走 WebDriverManager: " + e.getMessage());
        }

        Exception wdmFailure = null;
        try {
            runWebDriverManagerSetupOnce();
            return;
        } catch (Exception e) {
            wdmFailure = e;
            System.out.println("[驱动] WebDriverManager 失败: " + e.getMessage());
        }
        if (!wdmFallbackCdn) {
            throw new IllegalStateException("WebDriverManager 无法准备 chromedriver，"
                    + "可开启 -Dtaobao.wdm.fallback-cdn=true 或指定 -Dtaobao.chromedriver.path", wdmFailure);
        }
        if (fullChromeVersion == null || fullChromeVersion.isBlank()) {
            fullChromeVersion = readChromeFourPartVersionFromOs();
        }
        try {
            Path cftExe = downloadChromedriverFromCftPublic(fullChromeVersion);
            System.setProperty("webdriver.chrome.driver", cftExe.toAbsolutePath().toString());
            System.out.println("[驱动] 已从 Chrome for Testing 对象存储安装: " + cftExe);
            return;
        } catch (Exception e) {
            System.out.println("[驱动] storage.googleapis.com 下载失败: " + e.getMessage());
        }
        try {
            Path npmExe = downloadChromedriverFromNpmmirror();
            System.setProperty("webdriver.chrome.driver", npmExe.toAbsolutePath().toString());
            System.out.println("[驱动] 已从 npmmirror 安装: " + npmExe);
            return;
        } catch (Exception e) {
            System.out.println("[驱动] npmmirror 安装失败: " + e.getMessage());
        }
        throw new IllegalStateException("无法自动准备 chromedriver（WebDriverManager / CfT / npmmirror 均失败）。"
                + "请手动下载与 Chrome 主版本一致的 chromedriver 并用 -Dtaobao.chromedriver.path 指定。"
                + "（检测到的 Chrome 版本: " + fullChromeVersion + "）", wdmFailure);
    }

    private void runWebDriverManagerSetupOnce() throws Exception {
        var wdm = WebDriverManager.chromedriver();
        String proxyForWdm = firstNonBlank(wdmProxy, chromeProxy);
        String hostPort = normalizeProxyHostPort(proxyForWdm);
        if (!hostPort.isBlank()) {
            wdm.proxy(hostPort);
            System.out.println("[驱动] WebDriverManager 使用代理: " + hostPort);
        }
        if (wdmUseChinaMirror) {
            try {
                wdm.driverRepositoryUrl(URI.create(HUAWEI_CHROMEDRIVER_MIRROR).toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException("镜像地址无效", e);
            }
        }
        System.out.println("[驱动] WebDriverManager 正在解析/下载 chromedriver…");
        wdm.setup();
        System.out.println("[驱动] WebDriverManager 已就绪");
    }

    private Path resolveCachedChromedriverByExactVersion(String fullChromeVersion) {
        if (fullChromeVersion == null || fullChromeVersion.isBlank()) {
            return null;
        }
        Path p = cacheRoot().resolve("cft-" + fullChromeVersion).resolve(detectPlatform().driverFileName);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            setExecutableIfNeeded(p);
        } catch (IOException ignored) {
            // Windows 下无需处理执行权限
        }
        return p;
    }

    private static Path cacheRoot() {
        return Path.of(System.getProperty("user.home"), ".cache", "chromedriver");
    }

    private String readChromeFourPartVersionFromOs() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String reg = readChromeVersionFromWindowsRegistry();
            if (reg != null) {
                return reg;
            }
        }
        File chrome = resolveChromeExecutableForVersion();
        String out = runProcessRead(List.of(chrome.getAbsolutePath(), "--version"));
        Matcher m = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(out);
        if (!m.find()) {
            throw new IllegalStateException("chrome --version 输出中无四段版本号: " + out);
        }
        return m.group(1);
    }

    /** Windows 优先读注册表：执行 chrome.exe --version 在个别系统上会弹出浏览器窗口。 */
    private static String readChromeVersionFromWindowsRegistry() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "reg", "query", "HKCU\\Software\\Google\\Chrome\\BLBeacon", "/v", "version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] raw = p.getInputStream().readAllBytes();
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String text = new String(raw, Charset.defaultCharset());
            Matcher m = Pattern.compile("version\\s+REG_\\w+\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
            // 回退到 chrome --version
        }
        return null;
    }

    private File resolveChromeExecutableForVersion() {
        if (!chromeBinary.isBlank()) {
            File f = new File(chromeBinary);
            if (f.isFile()) {
                return f;
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (String p : List.of(
                    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe")) {
                File f = new File(p);
                if (f.isFile()) {
                    return f;
                }
            }
            throw new IllegalStateException("未找到 chrome.exe，请用 -Dtaobao.chrome.binary 指定");
        }
        if (os.contains("mac")) {
            File f = new File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            if (f.isFile()) {
                return f;
            }
            throw new IllegalStateException("未找到 Google Chrome，请用 -Dtaobao.chrome.binary 指定");
        }
        for (String cmd : List.of("/usr/bin/google-chrome-stable", "/usr/bin/google-chrome",
                "/usr/bin/chromium-browser", "/usr/bin/chromium")) {
            File f = new File(cmd);
            if (f.isFile()) {
                return f;
            }
        }
        throw new IllegalStateException("未找到 Chrome/Chromium，请用 -Dtaobao.chrome.binary 指定");
    }

    private static String runProcessRead(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(25, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("命令超时: " + command);
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private Path downloadChromedriverFromCftPublic(String fullVersion) throws Exception {
        Platform plat = detectPlatform();
        URI zipUri = URI.create(CFT_PUBLIC_PREFIX + fullVersion + "/" + plat.folder + "/" + plat.zipFileName);
        Path cacheDir = cacheRoot().resolve("cft-" + fullVersion);
        Path exePath = cacheDir.resolve(plat.driverFileName);
        if (Files.isRegularFile(exePath)) {
            setExecutableIfNeeded(exePath);
            return exePath;
        }
        Files.createDirectories(cacheDir);
        extractChromedriverFromZip(httpGetBytes(zipUri), exePath);
        setExecutableIfNeeded(exePath);
        return exePath;
    }

    private Path downloadChromedriverFromNpmmirror() throws Exception {
        String full = readChromeFourPartVersionFromOs();
        int major = Integer.parseInt(full.substring(0, full.indexOf('.')));
        String driverVer = httpGetTextOrNull(URI.create(NPMMIRROR_CHROMEDRIVER + "LATEST_RELEASE_" + major));
        if (driverVer == null || driverVer.isBlank()) {
            driverVer = full;
        }
        String listJson = httpGetTextOrNull(URI.create(NPMMIRROR_CHROMEDRIVER + driverVer + "/"));
        if (listJson == null || !listJson.trim().startsWith("[")) {
            throw new IOException("npmmirror 无该版本目录（Chrome 主版本可能过新）: " + driverVer);
        }
        Platform plat = detectPlatform();
        String zipUrl = pickNpmmirrorZipUrl(listJson, plat);
        Path cacheDir = cacheRoot().resolve("npm-" + driverVer);
        Path exePath = cacheDir.resolve(plat.driverFileName);
        if (Files.isRegularFile(exePath)) {
            setExecutableIfNeeded(exePath);
            return exePath;
        }
        Files.createDirectories(cacheDir);
        extractChromedriverFromZip(httpGetBytes(URI.create(zipUrl)), exePath);
        setExecutableIfNeeded(exePath);
        return exePath;
    }

    private static String pickNpmmirrorZipUrl(String listJson, Platform plat) {
        com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(listJson).getAsJsonArray();
        String fallbackHit = null;
        for (com.google.gson.JsonElement el : arr) {
            com.google.gson.JsonObject o = el.getAsJsonObject();
            if (!"file".equals(o.get("type").getAsString())) {
                continue;
            }
            String name = o.get("name").getAsString();
            if (plat.npmPreferZip.equals(name)) {
                return o.get("url").getAsString();
            }
            if (plat.npmFallbackZip != null && plat.npmFallbackZip.equals(name)) {
                fallbackHit = o.get("url").getAsString();
            }
        }
        if (fallbackHit != null) {
            return fallbackHit;
        }
        throw new IllegalStateException("npmmirror 列表中无本机所需 zip（" + plat.npmPreferZip + "）");
    }

    private enum Platform {
        WIN64("win64", "chromedriver-win64.zip", "chromedriver.exe", "chromedriver_win64.zip", "chromedriver_win32.zip"),
        LINUX64("linux64", "chromedriver-linux64.zip", "chromedriver", "chromedriver_linux64.zip", null),
        MAC_ARM64("mac-arm64", "chromedriver-mac-arm64.zip", "chromedriver", "chromedriver_mac_arm64.zip", null),
        MAC_X64("mac-x64", "chromedriver-mac-x64.zip", "chromedriver", "chromedriver_mac64.zip", null);

        final String folder;
        final String zipFileName;
        final String driverFileName;
        final String npmPreferZip;
        final String npmFallbackZip;

        Platform(String folder, String zipFileName, String driverFileName, String npmPreferZip, String npmFallbackZip) {
            this.folder = folder;
            this.zipFileName = zipFileName;
            this.driverFileName = driverFileName;
            this.npmPreferZip = npmPreferZip;
            this.npmFallbackZip = npmFallbackZip;
        }
    }

    private static Platform detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Platform.WIN64;
        }
        if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm64") ? Platform.MAC_ARM64 : Platform.MAC_X64;
        }
        return Platform.LINUX64;
    }

    private HttpClient buildDownloadHttpClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(45))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String hp = normalizeProxyHostPort(firstNonBlank(wdmProxy, chromeProxy));
        if (!hp.isBlank()) {
            int colon = hp.lastIndexOf(':');
            if (colon > 0) {
                b.proxy(ProxySelector.of(new InetSocketAddress(
                        hp.substring(0, colon), Integer.parseInt(hp.substring(colon + 1)))));
            }
        }
        return b.build();
    }

    private byte[] httpGetBytes(URI uri) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = buildDownloadHttpClient().send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(4)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + res.statusCode() + " GET " + uri);
        }
        return res.body();
    }

    private String httpGetTextOrNull(URI uri) {
        try {
            HttpResponse<String> res = buildDownloadHttpClient().send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode() / 100 == 2 ? res.body().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void extractChromedriverFromZip(byte[] zipBytes, Path targetExe) throws IOException {
        Files.createDirectories(targetExe.getParent());
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (name.endsWith("/chromedriver.exe") || name.endsWith("/chromedriver")) {
                    Files.copy(zis, targetExe, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException("zip 中未找到 chromedriver 可执行文件");
    }

    private static void setExecutableIfNeeded(Path exePath) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(exePath, perms);
    }

    private static String normalizeProxyHostPort(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim();
        // 误把开关写成代理（-Dtaobao.wdm.proxy=true）时，WDM 会去解析主机名 "true"
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")
                || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("no")) {
            return "";
        }
        return s.replaceFirst("^https?://", "").replaceFirst("^socks5://", "");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }
}
