package com.san.taobao.crawler.browser;

import com.san.taobao.config.ChromeConfig;
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
 * Chrome + chromedriver å¯å¨å¼å¯¼ã
 * <p>
 * é©±å¨è§£æé¾ï¼æ¬å° path â ç¼å­ â WebDriverManager/åä¸ºéå â CfT å¯¹è±¡å­å¨ â npmmirrorï¼
 * ç§»æ¤èªä¸»å·¥ç¨ {@code JobCrawlerService}ï¼å»æ Spring ä¾èµï¼éç½®ç± {@link ChromeConfig} æ³¨å¥ã
 * <p>
 * ä¸çèç¬è«çä¸¤å¤å³é®å·®å¼ï¼
 * <ol>
 *   <li><b>é»è®¤æçé¢è¿è¡</b>ãæ·å®ç Baxia é£æ§ä¼è¯å« {@code --headless}ï¼æ å¤´æ¨¡å¼ä¸ååæ¥å£ä¸ä¼è¿åæ°æ®ã</li>
 *   <li><b>ä½¿ç¨åºå® user-data-dir èéä¸´æ¶ç®å½</b>ãç»å½æéè¦è·¨æ¬¡è¿è¡å¤ç¨ï¼å¦åæ¯æ¬¡é½è¦éæ°æ«ç ã</li>
 * </ol>
 */
public final class ChromeLauncher {

    private static final String HUAWEI_CHROMEDRIVER_MIRROR = "https://mirrors.huaweicloud.com/chromedriver/";
    private static final String CFT_PUBLIC_PREFIX = "https://storage.googleapis.com/chrome-for-testing-public/";
    private static final String NPMMIRROR_CHROMEDRIVER = "https://registry.npmmirror.com/-/binary/chromedriver/";


    private final ChromeConfig config;
    private final Path profileDir;

    public ChromeLauncher(ChromeConfig config) {
        this.config = config;
        this.profileDir = config.profileDir().toAbsolutePath().normalize();
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
        if (config.headless()) {
            System.out.println("[è­¦å] taobao.chrome.headless=trueãæ·å®é£æ§ä¼è¯å«æ å¤´æ¨¡å¼ï¼"
                    + "ååæ¥å£å¾å¯è½è¿åç©ºæ°æ®ï¼ä»ç¨äºææ¥é©±å¨é®é¢ã");
            options.addArguments("--headless=new", "--disable-gpu");
        }
        options.addArguments(
                "--user-data-dir=" + profileDir,
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--window-size=1500,1000",
                "--lang=zh-CN",
                "--disable-blink-features=AutomationControlled",
                "--disable-sync",
                "--no-first-run",
                "--no-default-browser-check",
                "--remote-allow-origins=*",
                //æ°
                "--disable-gpu",//ç¦ç¨æ¾å¡
                "--disable-gpu-rasterization", //æ¹é¤æ¾å¡æçº¹
                "--disable-reading-from-canvas",
                "--disk-cache-size=104857600",
                "--ignore-certificate-errors",
                "--disable-web-security"
        );
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        if (!config.binary().isBlank()) {
            options.setBinary(config.binary());
        }
        if (!config.proxy().isBlank()) {
            String p = config.proxy();
            if (!p.startsWith("http://") && !p.startsWith("https://") && !p.startsWith("socks5://")) {
                p = "http://" + p;
            }
            options.addArguments("--proxy-server=" + p);
            System.out.println("[éç½®] Chrome ä½¿ç¨ä»£ç: " + p);
        }
        return options;
    }

    /**
     * åä¸ {@code user-data-dir} åªè½è¢«ä¸ä¸ª Chrome å ç¨ãä¸æ¬¡å¼å¸¸éåºåè¿ç¨å¸¸è¿å¨ï¼
     * åå¯å¨å°±ä¼æ¥ SessionNotCreated / Chrome instance exitedã
     */
    private void ensureProfileNotInUse() {
        Path abs = profileDir;
        List<Long> pids = chromePidsUsingProfile(abs);
        if (pids.isEmpty()) {
            return;
        }
        throw new IllegalStateException("ç¨æ·ç®å½å·²è¢«å ç¨ï¼æ æ³åå¼ä¸ä¸ª Chromeï¼" + abs
                + "ãå ç¨è¿ç¨ PID: " + pids
                + "ãè¯·åå³æè¿ä¸ªç¬è«å¼¹åºç Chromeï¼åéæ°è¿è¡ã"
                + "ä»»å¡ç®¡çå¨éä¹å¯ç»æè¿äº chrome.exeã");
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
            return new IllegalStateException("Chrome å¯å¨åç«å»éåºãæå¸¸è§åå æ¯ç¨æ·ç®å½å·²è¢«å ç¨ï¼"
                    + profileDir
                    + "ãè¯·å³æå ç¨è¯¥ç®å½ç Chrome çªå£ååè·ãåå§éè¯¯: " + msg, e);
        }
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new IllegalStateException("å¯å¨ Chrome å¤±è´¥: " + msg, e);
    }

    // ==================== chromedriver è§£æ====================

    private File resolveChromedriverExecutable() {
        if (!config.driverPath().isBlank()) {
            File f = new File(config.driverPath());
            if (!f.isFile()) {
                throw new IllegalStateException("taobao.chromedriver.path ä¸æ¯æææä»¶: " + f.getAbsolutePath());
            }
            System.out.println("[éç½®] ä½¿ç¨æå®ç chromedriver: " + f.getAbsolutePath());
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
                System.out.println("[é©±å¨] å½ä¸­æ¬å°ç¼å­ï¼è·³è¿ç½ç»æ¢æµ: " + cached);
                return;
            }
        } catch (Exception e) {
            System.out.println("[é©±å¨] è¯»åæ¬æº Chrome çæ¬å¤±è´¥ï¼ç»§ç»­èµ° WebDriverManager: " + e.getMessage());
        }

        Exception wdmFailure = null;
        try {
            runWebDriverManagerSetupOnce();
            return;
        } catch (Exception e) {
            wdmFailure = e;
            System.out.println("[é©±å¨] WebDriverManager å¤±è´¥: " + e.getMessage());
        }
        if (!config.fallbackCdn()) {
            throw new IllegalStateException("WebDriverManager æ æ³åå¤ chromedriverï¼"
                    + "å¯å¼å¯ -Dtaobao.wdm.fallback-cdn=true ææå® -Dtaobao.chromedriver.path", wdmFailure);
        }
        if (fullChromeVersion == null || fullChromeVersion.isBlank()) {
            fullChromeVersion = readChromeFourPartVersionFromOs();
        }
        try {
            Path cftExe = downloadChromedriverFromCftPublic(fullChromeVersion);
            System.setProperty("webdriver.chrome.driver", cftExe.toAbsolutePath().toString());
            System.out.println("[é©±å¨] å·²ä» Chrome for Testing å¯¹è±¡å­å¨å®è£: " + cftExe);
            return;
        } catch (Exception e) {
            System.out.println("[é©±å¨] storage.googleapis.com ä¸è½½å¤±è´¥: " + e.getMessage());
        }
        try {
            Path npmExe = downloadChromedriverFromNpmmirror();
            System.setProperty("webdriver.chrome.driver", npmExe.toAbsolutePath().toString());
            System.out.println("[é©±å¨] å·²ä» npmmirror å®è£: " + npmExe);
            return;
        } catch (Exception e) {
            System.out.println("[é©±å¨] npmmirror å®è£å¤±è´¥: " + e.getMessage());
        }
        throw new IllegalStateException("æ æ³èªå¨åå¤ chromedriverï¼WebDriverManager / CfT / npmmirror åå¤±è´¥ï¼ã"
                + "è¯·æå¨ä¸è½½ä¸ Chrome ä¸»çæ¬ä¸è´ç chromedriver å¹¶ç¨ -Dtaobao.chromedriver.path æå®ã"
                + "ï¼æ£æµå°ç Chrome çæ¬: " + fullChromeVersion + "ï¼", wdmFailure);
    }

    private void runWebDriverManagerSetupOnce() throws Exception {
        var wdm = WebDriverManager.chromedriver();
        String proxyForWdm = normalizeProxyHostPort(config.effectiveDriverProxy());
        String hostPort = normalizeProxyHostPort(proxyForWdm);
        if (!hostPort.isBlank()) {
            wdm.proxy(hostPort);
            System.out.println("[é©±å¨] WebDriverManager ä½¿ç¨ä»£ç: " + hostPort);
        }
        if (config.useChinaMirror()) {
            try {
                wdm.driverRepositoryUrl(URI.create(HUAWEI_CHROMEDRIVER_MIRROR).toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException("éåå°åæ æ", e);
            }
        }
        System.out.println("[é©±å¨] WebDriverManager æ­£å¨è§£æ/ä¸è½½ chromedriverâ¦");
        wdm.setup();
        System.out.println("[é©±å¨] WebDriverManager å·²å°±ç»ª");
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
            // Windows ä¸æ éå¤çæ§è¡æé
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
            throw new IllegalStateException("chrome --version è¾åºä¸­æ åæ®µçæ¬å·: " + out);
        }
        return m.group(1);
    }

    /** Windows ä¼åè¯»æ³¨åè¡¨ï¼æ§è¡ chrome.exe --version å¨ä¸ªå«ç³»ç»ä¸ä¼å¼¹åºæµè§å¨çªå£ã */
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
            // åéå° chrome --version
        }
        return null;
    }

    private File resolveChromeExecutableForVersion() {
        if (!config.binary().isBlank()) {
            File f = new File(config.binary());
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
            throw new IllegalStateException("æªæ¾å° chrome.exeï¼è¯·ç¨ -Dtaobao.chrome.binary æå®");
        }
        if (os.contains("mac")) {
            File f = new File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            if (f.isFile()) {
                return f;
            }
            throw new IllegalStateException("æªæ¾å° Google Chromeï¼è¯·ç¨ -Dtaobao.chrome.binary æå®");
        }
        for (String cmd : List.of("/usr/bin/google-chrome-stable", "/usr/bin/google-chrome",
                "/usr/bin/chromium-browser", "/usr/bin/chromium")) {
            File f = new File(cmd);
            if (f.isFile()) {
                return f;
            }
        }
        throw new IllegalStateException("æªæ¾å° Chrome/Chromiumï¼è¯·ç¨ -Dtaobao.chrome.binary æå®");
    }

    private static String runProcessRead(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(25, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("å½ä»¤è¶æ¶: " + command);
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
            throw new IOException("npmmirror æ è¯¥çæ¬ç®å½ï¼Chrome ä¸»çæ¬å¯è½è¿æ°ï¼: " + driverVer);
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
        throw new IllegalStateException("npmmirror åè¡¨ä¸­æ æ¬æºæé zipï¼" + plat.npmPreferZip + "ï¼");
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
        String hp = normalizeProxyHostPort(config.effectiveDriverProxy());
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
        throw new IOException("zip ä¸­æªæ¾å° chromedriver å¯æ§è¡æä»¶");
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
        // è¯¯æå¼å³åæä»£çï¼-Dtaobao.wdm.proxy=trueï¼æ¶ï¼WDM ä¼å»è§£æä¸»æºå "true"
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
