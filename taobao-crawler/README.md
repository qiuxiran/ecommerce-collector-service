# 淘宝商品详情抓取 demo

控制台输入淘宝/天猫商品链接，输出结构化 JSON（`item_id` / `sku_list` / `attr_list` / 图片视频数组）。

驱动引导部分参考了工程 `JobCrawlerService`（见下文「与猎聘爬虫的差异」）。

## 运行

```bash
cd docs/taobao-crawler
mvn -B clean package
java -jar target/taobao-item-crawler-demo.jar
```

**必须在真正的终端窗口里跑。** 程序全程依赖人工输入（登录、输链接、过验证），
检测不到交互式控制台时会在启动浏览器之前就退出 —— 否则每个等待输入的点都会立刻 EOF，
退化成对淘宝的无人值守请求循环。

Windows 控制台若中文显示乱码，先执行 `chcp 65001`。输出文件一律 UTF-8，不受控制台编码影响。

首次运行会打开 Chrome 并停在淘宝首页等你扫码登录。登录态持久化在
`~/.cache/taobao-crawler/profile`，之后再跑不用重复登录；删掉该目录即恢复未登录状态。

### 只验证解析逻辑（不开浏览器、不碰淘宝）

```bash
mvn -B -q compile exec:java -Dmain.class=com.san.taobao.ParserSelfCheck
```

用一份合成的页面数据跑通解析并断言输出结构，28 项检查（含「页面不下发 SKU 价时退化到整品价」一组）。
把「字段映射对不对」和「能不能抓到淘宝」两件事解耦，淘宝改版后也可以把真实响应贴进去当回归用例。

> 注意用 `-Dmain.class`，不是 `-Dexec.mainClass` —— pom 里 `<mainClass>` 显式配成了 `${main.class}`，
> 插件的显式配置优先级高于 `exec.mainClass` 这个用户属性，写后者会被忽略、实际启动主程序。

## 输入形式

| 输入 | 说明 |
|------|------|
| `https://item.taobao.com/item.htm?id=1000263488499` | 完整链接 |
| `https://detail.tmall.com/item.htm?id=...` | 天猫链接，`plt_id` 自动置为 `tm` |
| `1000263488499` | 纯数字商品 ID |
| 短链接 | 无法本地解析，先让浏览器跳转再从最终 URL 取 ID |

直接回车退出。

## 输出

默认写到 `./output`（可用 `-Dtaobao.output.dir=` 改）。每条商品产出：

| 文件 | 内容 |
|------|------|
| `tb<itemId>.json` | 目标结构 JSON |
| `tb<itemId>.detail-root.json` | **页面内联的详情数据节点，改映射时看这个** |
| `tb<itemId>.page.html` | 渲染后的页面 HTML，离线复算的输入 |
| `tb<itemId>.raw-mtop.json` | 捕获到的 mtop 响应（仅诊断用，不含商品数据） |
| `items.jsonl` | 单行 JSON 追加，便于批量导入 |
| `items-summary.csv` | 扁平摘要（带 BOM，Excel 直接打开不乱码） |

抓不到的字段保持 `null` 而非空串／空数组，便于区分「商品本身没有」和「解析失败」。

**`page.html` 和 `detail-root.json` 一定要留着。** 淘宝改版时字段会挪位置，有它们在手就能离线对照修映射，
不用重跑抓取。关键字段缺失时程序会提示。

## 配置

全部走 `-D` 系统属性：

| 属性 | 默认 | 说明 |
|------|------|------|
| `taobao.output.dir` | `output` | 输出目录 |
| `taobao.profile.dir` | `~/.cache/taobao-crawler/profile` | Chrome 用户目录（登录态） |
| `taobao.chrome.binary` | 自动探测 | 本机 `chrome.exe` 路径 |
| `taobao.chrome.proxy` | 无 | Chrome 代理，`host:port` |
| `taobao.chrome.headless` | `false` | **不要开**，见下文 |
| `taobao.fetch-desc` | `true` | 是否另开图文详情页取 `desc_img_arr`（多一次页面加载） |
| `taobao.chromedriver.path` | 无 | 指定后完全跳过自动下载 |
| `taobao.wdm.proxy` | 无 | 下载驱动用的代理 |
| `taobao.wdm.use-china-mirror` | `true` | 华为云 chromedriver 镜像 |
| `taobao.wdm.fallback-cdn` | `true` | WDM 失败后回退 CfT / npmmirror |
| `taobao.force-interactive` | `false` | 终端可交互但检测有误时强制继续 |

## 实现思路

**商品数据是服务端渲染时内联在页面里的**，最终挂在 `window.__ICE_APP_CONTEXT__`（阿里 ICE 框架的 SSR 上下文），
路径为 `loaderData.<route>.data.res`。不是通过接口异步下发的。

这一点是实测得出的，和常见说法相反。抓包能看到的 mtop 请求只有 `mtop.user.getusersimple`、
广告位、`maoxland.containerfacade.singleview` 这类外围数据，**`skuBase` 从头到尾不出现在任何 mtop 响应里**。
所以「拦截 mtop 接口拿商品数据」这条路方向是错的 —— 顺带也不必再纠结 `sign` 的 WASM 逆向和
`_m_h5_tk` 轮换，那些是拿不到数据的原因，但不是绕过它就能拿到数据。

取数走两条路：在线直接读全局变量；离线从保存的 HTML 里做带字符串转义感知的花括号配对提取
（商品标题和详情文案里大量出现花括号，纯计数会在中途断掉）。
详情节点按特征广度搜索（含 `skuBase`，或同时含 `item` 与 `componentsVO`），
不硬编码 `loaderData.home.data.res` —— 路由名会变。

映射时几个真实结构与直觉不符的地方：

| | 实际情况 |
|---|---|
| 属性 | 在 `componentsVO.extensionInfoVO.infos` 里 `type=BASE_PROPS` 那一项下，形如 `{"title":"屏幕尺寸","text":["6.83英寸"]}`，**不是** `name`/`value`。同级混着优惠、花呗、服务、认证条目，必须按 `type` 过滤 |
| 价格 | `price.priceTitle` 实测是「优惠前」即**原价**，到手价在 `subPrice` / `extraPrice`。直接把 `price` 当售价会偏高 |
| SKU 价 | **不一定存在**。`skuItem.hideOtherPrice=true` 的商品，`sku2info` 里只有库存没有价格（实测两个商品 0/13、0/36），此时只能退化到整品级 `componentsVO.priceVO` |
| 主图 | `item.images` 与 `componentsVO.headImageVO.images` **互有遗漏**（实测有商品前者 1 张后者 2 张），必须合并去重 |
| 详情图 | 不在主页面数据里，要另开 `item.pcADescUrl` 指向的图文详情页 |
| 类目 | `item.categoryId` 为空；`leafCategory` 埋在埋点参数里，层级不固定，按 key 递归找。部分商品页确实没有 |

价格语义不靠标题文案判断（文案随活动变），而用一个更稳的不变量：**售价不高于原价**，
所以取候选价最小值作 `price`、最大值作 `cost_price`，只有一个价格时两者相同。

取价分两级，**严格按优先级、不混算**：先试 SKU 级 `sku2info[skuId]`，取不到再退整品级 `priceVO`。
两级混在一起比 min/max 会算出既非 SKU 价也非整品价的组合（如 SKU 1769/2299 混整品 1345/1599 得 1345/2299）。
退化的条数会如实统计并在控制台标注，CSV 里也有 `price_source` 列
（`sku` 页面直接有 / `click` 点选补齐 / `item` 全部退化 / `mixed` 两者并存 / `none` 没取到），
便于事后筛出精度降级的商品。整品价只是兜底，正常路径是下节的点选补价。

DOM 解析只在结构化数据完全拿不到时兜底，且会在控制台明确标注 `[DOM 兜底]`。
DOM 里的价格可能是分期价/券后价，图片带 `_q50.jpg_.webp` 转码后缀，质量明显更差。

## 点选补价

卖家开了「选完规格才显示价」（`skuItem.hideOtherPrice=true`）时，页面 SSR 数据里**没有**
SKU 维度的价格，`sku2info` 只给库存，抓包里也没有 —— 不是解析漏了。实测两个商品分别是 0/13、0/36。

这种商品会自动进入点选补价：按每个 SKU 的 `valId` 点齐所有规格维度，等价格刷新，再读价格区。
靠三个 DOM 约定，类名哈希后缀每次发版都变，所以只能前缀匹配：

| 用途 | 选择器 |
|---|---|
| 规格按钮 | `div[class*='valueItem--'][data-vid]`，选中加 `isSelected--`，不可选是 `data-disabled="true"` |
| 价格区 | `div[class*='normalPrice--'] div[class*='priceWrap--']`（避开 `beltPrice--` 那份重复的） |

最大的风险不是读不到，而是**读早了** —— 读到上一个规格的价却当成当前的。只靠「和上次不同」判断不行
（不同规格同价很常见），只靠「文本稳定」也不行（异步取价还在路上时旧价格同样稳定）。所以要同时满足：
过了 600ms 静置、连续两次读到一样、期间没有新的 mtop 响应落地（钩子里加了个不受缓冲区上限影响的计数器），
再确认价格不带「起」字（带「起」说明规格没选全，那是区间价）。

| 开关 | 默认 | 说明 |
|---|---|---|
| `-Dtaobao.sku-click=false` | 开启 | 关掉点选补价，缺价的直接用整品价 |
| `-Dtaobao.sku-click-max=N` | 200 | 超过 N 个规格就不点，避免几百次点击 |

代价是慢：每个 SKU 约 1.2 秒，35 个规格约 40 秒，而且点击密度高会增加风控概率。
补不到的（多为已售罄不可点选）保留整品兜底价，不会因为补价失败丢掉整条数据。
控制台会打印 `（价格：页面 0 / 点选 33 / 整品兜底 2 / 缺失 0）`，CSV 的 `price_source` 相应是
`click`（点选补齐，精确到规格）。

选择器失效是这块最容易静默出问题的地方，所以离线复算会顺带校验一遍，输出 `点选补价选择器: 可用（…）`。

## 离线复算

抓一次页面很贵（要登录、要控频、可能触发验证），但调映射需要反复迭代。两件事是拆开的：

```bash
mvn -B -q compile exec:java -Dmain.class=com.san.taobao.ReprocessTool
```

对 `output/` 下已保存的 `*.page.html` 重跑解析，结果写 `output/reprocessed/`，
不启动浏览器、不请求淘宝。页面存一次就能改一次解析验证一次。

也可以只处理单个文件：`... -Dexec.args="output/tm1061692494573.page.html"`

## 与猎聘爬虫的差异

驱动解析链（本地 path → 缓存 → WebDriverManager/华为镜像 → CfT 对象存储 → npmmirror）
和 Windows 注册表读版本号，从 `JobCrawlerService` 移植，去掉 Spring 依赖。两处必须改的地方：

| | 猎聘 | 淘宝 |
|---|---|---|
| 运行模式 | 无头 | **必须有界面**，风控会识别 `--headless` |
| 用户目录 | 临时目录，退出即删 | **固定目录**，登录态要跨次复用 |
| 取数方式 | 解析渲染后 DOM | 读页面内联的 SSR 数据 |
| 登录 | 粘贴 Cookie | 首次人工扫码，之后复用 profile |

## 已知限制

- **量上不去。** 连续抓几十条必出滑块。程序检测到验证会停下来等你手动过，不会自动破解。
  条间隔固定 2.5 秒，别调小 —— 连续快速请求是触发风控最快的方式。
- **`category_id` 部分商品拿不到。** PC 详情页数据里确实没有可靠的商品类目字段，
  只有部分商品在埋点参数里带 `leafCategory`。宁可留空也不填错值（同级还有「延长保修」等服务的类目 ID，
  很容易误取）。
- **已售罄的规格点不动，价格只能保持整品兜底。** 见下节。
- **`main_video_arr` 大多为 null。** 不是解析失败 —— 实测多数商品 `headImageVO.videos` 就是空数组。
- **`desc` 文本通常为 null。** 图文详情几乎都是纯图片，没有文字内容，所以只有 `desc_img_arr` 有值。
- **映射只在 6 个真实商品上验证过**（1 个淘宝 + 5 个天猫，覆盖 1 / 4 / 12 / 16 / 35 SKU 五种规模，
  以及有无逐 SKU 价两种情况）。
  电商类目千差万别，遇到解析不对的商品，用离线复算调映射，别急着改抓取流程。

## 合规

淘宝 `robots.txt` 与用户协议禁止抓取。请只用自己的账号、保持低频率、数据仅自用，不要外传或商用。
本 demo 刻意不包含验证码绕过与设备指纹伪造。
