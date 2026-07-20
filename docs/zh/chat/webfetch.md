<div v-pre>

# WebFetchTool 开发文档

## 1. 概述

### 1.1 简介
WebFetchTool 是 Agents-Flex 框架中的一个智能网页内容抓取工具，提供具备自适应降级机制的可靠网页内容提取功能。它结合了直接 HTTP 请求和专用阅读器服务（如 Jina Reader），确保在各种网站类型下都能可靠地获取内容。

### 1.2 核心特性
- **自适应内容抓取**：自动在多种读取策略之间切换
- **智能路由**：使用自适应评分机制为每个 URL 选择最佳提供者
- **降级机制**：当简单 HTTP 失败时优雅地降级到高级阅读器
- **内容缓存**：内置支持 TTL 的 LRU 缓存
- **HTML 处理**：自动清洗 HTML 并转换为 Markdown 格式
- **重试逻辑**：指数退避重试以应对临时性故障
- **线程安全指标**：基于历史成功率的自适应学习

### 1.3 架构组件

```
┌─────────────────────────────────────────────┐
│           WebFetchTool                      │
│  (主入口与编排层)                             │
└──────────────┬──────────────────────────────┘
               │
               ├─> 验证与缓存层
               │
               ├─> HTTP 抓取层（带重试）
               │
               └─> AdaptiveWebReaderRouter（自适应路由器）
                       │
                       ├─> AdaptiveScoreEngine（评分引擎）
                       │       └─> ProviderMetrics（指标统计）
                       │
                       └─> WebReaderProvider[]（内容提供者）
                               ├─> HttpReaderProvider（HTTP 读取器）
                               └─> JinaReaderProvider（Jina 阅读器）
```


## 2. 核心类说明

### 2.1 WebFetchTool

**位置**: `com.agentsflex.tool.webfetch.WebFetchTool`

**职责**: 主入口类，负责编排带有缓存、验证和降级逻辑的网页内容抓取流程。

**关键组件**:

| 组件 | 类型 | 说明 |
|------|------|------|
| `agentsFlexHttpClient` | OkHttpClient | 用于发起 HTTP 请求的客户端 |
| `htmlConverter` | FlexmarkHtmlConverter | 将 HTML 转换为 Markdown |
| `maxContentLength` | int | 最大内容长度（默认：100,000 字符） |
| `maxCacheSize` | int | 最大缓存条目数（默认：100） |
| `cache` | LinkedHashMap | 带 TTL 的 LRU 缓存（15 分钟） |
| `webReaderRouter` | AdaptiveWebReaderRouter | 路由到合适的阅读器提供者 |

**主要方法**:
```java
@ToolDef(
    name = "web_fetch",
    description = "Fetch web page content with HTTP + Reader fallback (Jina, etc)."
)
public String webFetch(String url)
```


**工作流程**:
1. 验证 URL（必须使用 http/https 协议）
2. 检查缓存中是否存在内容
3. 尝试使用重试逻辑进行 HTTP 抓取
4. 如果内容不足（< 200 字符），降级到阅读器服务
5. 处理内容（清洗 HTML → 转换为 Markdown → 截断）
6. 缓存并返回结果

### 2.2 AdaptiveWebReaderRouter（自适应 Web 阅读器路由器）

**位置**: `com.agentsflex.tool.webfetch.web.AdaptiveWebReaderRouter`

**职责**: 根据静态评分和历史表现智能选择和排序 WebReaderProvider。

**关键方法**:

- `rank(String url)`: 为给定 URL 排序可用的提供者
- `read(String url, Function<String, String> transformer)`: 执行带自动降级的读取操作

**排序算法**:
```
最终评分 = 基础评分 × (0.5 + 成功率)
```


其中:
- `基础评分`: 提供者对 URL 的静态评分（来自 `provider.score(url)`）
- `成功率`: 历史成功率（0.0 - 1.0，新提供者默认 0.5）

**排序示例**:
```
URL: https://example.com/article

提供者            基础评分     成功率       最终评分
──────────────────────────────────────────────────
jina              90           0.9          126 (90 × 1.4)
browser           80           0.7          96  (80 × 1.2)
http              60           0.5          60  (60 × 1.0)

执行顺序: jina → browser → http
```


### 2.3 AdaptiveScoreEngine（自适应评分引擎）

**位置**: `com.agentsflex.tool.webfetch.web.AdaptiveScoreEngine`

**职责**: 根据运行时性能指标动态调整提供者优先级。

**特性**:
- 使用 `ConcurrentHashMap` 实现线程安全的指标收集
- 累积成功/失败跟踪
- 结合历史数据的动态评分计算

**方法**:
- `score(String providerName, int baseScore)`: 计算最终评分
- `recordSuccess(String provider)`: 记录成功操作
- `recordFail(String provider)`: 记录失败操作

### 2.4 ProviderMetrics（提供者指标统计）

**位置**: `com.agentsflex.tool.webfetch.web.ProviderMetrics`

**职责**: 跟踪单个提供者的性能指标。

**统计内容**:
- 成功次数（AtomicInteger）
- 失败次数（AtomicInteger）
- 成功率（按需计算）

**默认行为**: 新提供者以 0.5 成功率启动（中立状态）

### 2.5 WebReaderProvider 接口

**位置**: `com.agentsflex.tool.webfetch.web.WebReaderProvider`

**职责**: 定义内容读取实现的契约。

**接口方法**:
```java
String name();                    // 提供者标识
boolean supports(String url);     // 检查是否能处理该 URL
int score(String url);            // 静态优先级评分（越高越优先）
String read(String url);          // 执行读取操作
```


### 2.6 HttpReaderProvider（HTTP 阅读器提供者）

**位置**: `com.agentsflex.tool.webfetch.web.HttpReaderProvider`

**职责**: 直接通过 HTTP GET 请求获取网页原始内容。

**特点**:
- 实现简单
- 性能最高
- 无额外依赖

**局限性**:
- 无法执行 JavaScript
- 无法处理 SPA 页面
- 无法获取动态渲染内容

**适用场景**:
- JSON API
- 静态网页
- 开放文档页面

**评分逻辑**:
```java
if (url.endsWith(".json")) return 100;  // JSON 文件最优
if (url.contains("docs")) return 80;    // 文档次之
return 50;  // 默认评分
```


### 2.7 JinaReaderProvider（Jina 阅读器提供者）

**位置**: `com.agentsflex.tool.webfetch.web.JinaReaderProvider`

**职责**: 使用 Jina AI 的阅读器服务（https://r.jina.ai/）进行内容提取。

**特点**:
- 无需浏览器渲染
- 自动去除广告和页面噪音
- 适合博客、文章、技术文档阅读

**默认评分**: 70（可通过 `setDefaultScore()` 配置）

**自定义域名评分**:
```java
JinaReaderProvider provider = new JinaReaderProvider(client);
provider.addHostScore("medium.com", 95);
provider.addHostScore("github.com", 85);
```


### 2.8 OKHttpUtil（HTTP 工具类）

**位置**: `com.agentsflex.tool.webfetch.web.OKHttpUtil`

**职责**: 提供 HTTP 操作工具，具备智能字符集检测功能。

**核心功能**:
- 模拟浏览器 User-Agent
- 多级字符集检测:
    1. HTTP Content-Type 头部
    2. BOM（字节顺序标记）检测
    3. HTML meta charset 标签
    4. 回退字符集（UTF-8、GBK、GB2312、ISO-8859-1）
- 自动修复损坏的编码
- 文本质量评分以选择最优字符集

### 2.9 ProviderCandidate（提供者候选对象）

**位置**: `com.agentsflex.tool.webfetch.web.ProviderCandidate`

**职责**: 在路由过程中封装已排序的提供者候选项。

**字段**:
- `provider`: WebReaderProvider 实例
- `score`: 经过自适应调整后的最终评分

## 3. 详细工作流程

### 3.1 内容抓取流程

```
webFetch(url)
    │
    ├─> validateUrl(url)
    │   ├─ 检查协议（仅允许 http/https）
    │   └─ 检查主机有效性
    │
    ├─> getFromCache(cacheKey)
    │   ├─ 如果缓存有效则返回
    │   └─ 未找到或过期则继续
    │
    ├─> fetchAndProcessContent(url)
    │   │
    │   ├─> fetchWithHttp(url)
    │   │   ├─ executeGet(url) [最多重试 MAX_RETRY=2 次]
    │   │   │   ├─ 2xx: 成功 → 返回响应体
    │   │   │   ├─ 4xx: 不可重试 → 抛出异常
    │   │   │   └─ 5xx/429: 可重试 → 指数退避
    │   │   │
    │   │   └─ processContent(rawHtml)
    │   │       ├─ cleanHtml() [移除 <script>、<style>]
    │   │       ├─ htmlConverter.convert() [HTML → Markdown]
    │   │       └─ truncate() [限制到 maxContentLength]
    │   │
    │   └─ 如果内容 < 200 字符:
    │       │
    │       └─> fetchWithReaders(url, transformer)
    │           │
    │           ├─> rank(url) [按自适应评分排序提供者]
    │           │
    │           └─ 按顺序尝试每个提供者:
    │               ├─ provider.read(url)
    │               ├─ 如果提供了 transformer 则应用
    │               ├─ 成功: recordSuccess() → 返回结果
    │               └─ 失败: recordFail() → 尝试下一个
    │
    └─ putCache(cacheKey, content)
        └─ 返回内容
```


### 3.2 重试策略

**HTTP 重试策略**:
- 最大重试次数: 2（总共 3 次尝试）
- 退避公式: `2^重试次数 × 300ms`
    - 重试 0: 立即执行
    - 重试 1: 等待 300ms
    - 重试 2: 等待 600ms

**可重试的状态码**:
- 5xx（服务器错误）
- 429（请求过多）

**不可重试的状态码**:
- 4xx（客户端错误如 404、403）

### 3.3 缓存策略

**缓存配置**:
- 类型: LRU（最近最少使用）LinkedHashMap
- TTL: 15 分钟
- 最大容量: 100 个条目（可配置）
- 键格式: `"web:" + url`

**缓存行为**:
```java
// 缓存满时自动驱逐最旧的条目
protected boolean removeEldestEntry(Map.Entry eldest) {
    return size() > maxCacheSize;
}

// 检索时检查 TTL
boolean isExpired() {
    return System.currentTimeMillis() - timestamp > CACHE_TTL.toMillis();
}
```


### 3.4 HTML 处理管道

**步骤 1: 清洗 HTML**
```java
private String cleanHtml(String html) {
    return html
        .replaceAll("(?s)<script.*?>.*?</script>", "")
        .replaceAll("(?s)<style.*?>.*?</style>", "");
}
```


**步骤 2: 转换为 Markdown**
```java
String converted = htmlConverter.convert(cleaned);
```


**步骤 3: 截断**
```java
private String truncate(String content) {
    if (content.length() > maxContentLength) {
        return content.substring(0, maxContentLength);
    }
    return content;
}
```


## 4. 使用示例

### 4.1 基本用法

```java
// 使用默认提供者创建
WebFetchTool tool = WebFetchTool.builder()
    .useDefaultProviders()  // 添加 HttpReaderProvider + JinaReaderProvider
    .build();

// 抓取内容
String content = tool.webFetch("https://example.com/article");

// 使用完毕后关闭
tool.close();
```


### 4.2 自定义配置

```java
OkHttpClient customClient = new OkHttpClient.Builder()
    .connectTimeout(Duration.ofSeconds(30))
    .readTimeout(Duration.ofSeconds(30))
    .build();

WebFetchTool tool = WebFetchTool.builder()
    .agentsFlexHttpClient(customClient)
    .maxContentLength(50_000)  // 限制为 50KB
    .maxCacheSize(200)         // 缓存 200 个条目
    .useDefaultProviders()
    .build();
```


### 4.3 添加自定义提供者

```java
// 创建自定义提供者
public class CustomReaderProvider implements WebReaderProvider {
    @Override
    public String name() {
        return "custom";
    }

    @Override
    public boolean supports(String url) {
        return url.contains("special-site.com");
    }

    @Override
    public int score(String url) {
        return url.contains("special-site.com") ? 100 : 30;
    }

    @Override
    public String read(String url) throws Exception {
        // 自定义读取逻辑
        return "...";
    }
}

// 注册自定义提供者
WebFetchTool tool = WebFetchTool.builder()
    .addProvider(new CustomReaderProvider())
    .addProvider(new HttpReaderProvider(client))
    .addProvider(new JinaReaderProvider(client))
    .build();
```


### 4.4 Jina 提供者自定义评分

```java
JinaReaderProvider jinaProvider = new JinaReaderProvider(client);
jinaProvider.setDefaultScore(75);
jinaProvider.addHostScore("blog.example.com", 95);
jinaProvider.addHostScore("docs.example.com", 90);

WebFetchTool tool = WebFetchTool.builder()
    .addProvider(jinaProvider)
    .addProvider(new HttpReaderProvider(client))
    .build();
```


### 4.5 与 Agent 框架集成

```java
// 与 Agents-Flex agent 集成
Agent agent = Agent.builder()
    .name("ResearchAssistant")
    .tools(List.of(new WebFetchTool.Builder()
        .useDefaultProviders()
        .build()))
    .build();

// Agent 现在可以使用 web_fetch 工具
String response = agent.chat("Fetch and summarize https://example.com/news");
```


## 5. 扩展指南

### 5.1 创建新的 WebReaderProvider

要添加新的内容读取策略，实现 `WebReaderProvider` 接口:

```java
public class BrowserlessProvider implements WebReaderProvider {

    private final OkHttpClient client;
    private final String apiKey;

    public BrowserlessProvider(OkHttpClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "browserless";
    }

    @Override
    public boolean supports(String url) {
        // 仅支持需要 JavaScript 渲染的 URL
        return true; // 或添加特定逻辑
    }

    @Override
    public int score(String url) {
        // 大多数情况下优先级低于 Jina
        if (url.contains("spa-app.com")) return 90;
        return 40;
    }

    @Override
    public String read(String url) throws Exception {
        // 调用 Browserless API
        RequestBody body = RequestBody.create(
            MediaType.parse("application/json"),
            "{\"url\":\"" + url + "\"}"
        );

        Request request = new Request.Builder()
            .url("https://chrome.browserless.io/content?token=" + apiKey)
            .post(body)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Browserless failed: " + response.code());
            }
            return response.body().string();
        }
    }
}
```


### 5.2 提供者选择最佳实践

**何时使用 HttpReaderProvider**:
- JSON API
- 静态 HTML 页面
- 文档站点
- 高性能需求场景

**何时使用 JinaReaderProvider**:
- 博客文章
- 新闻文章
- 技术文档
- 含有广告/噪音的页面


**何时创建自定义提供者**:
- 需要 JavaScript 的 SPA 应用
- 具有反机器人保护的站点
-  specialized 内容提取需求
- 专有阅读器服务

### 5.3 评分指南

**评分范围建议**:
- 90-100: 特定域名/类型的最优提供者
- 70-89: 首选通用提供者
- 50-69: 标准回退提供者
- 30-49: 特殊情况提供者
- 0-29: 最后手段提供者

**动态评分示例**:

```java
@Override
public int score(String url) {
    String lowerUrl = url.toLowerCase();

    // 域名特定评分
    if (lowerUrl.contains("github.com")) return 95;
    if (lowerUrl.contains("medium.com")) return 90;
    if (lowerUrl.contains("stackoverflow.com")) return 85;

    // 文件类型评分
    if (lowerUrl.endsWith(".json")) return 100;
    if (lowerUrl.endsWith(".xml")) return 90;
    if (lowerUrl.endsWith(".pdf")) return 10; // 不支持

    // 路径评分
    if (lowerUrl.contains("/api/")) return 95;
    if (lowerUrl.contains("/docs/")) return 80;
    if (lowerUrl.contains("/blog/")) return 75;

    return 50; // 默认
}
```

## 6. 配置参考

### 6.1 Builder 参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `maxContentLength` | int | 100,000 | 最大内容长度（字符） |
| `maxCacheSize` | int | 100 | 最大缓存条目数 |
| `agentsFlexHttpClient` | OkHttpClient | 默认客户端 | 自定义 HTTP 客户端 |
| `providers` | `List<WebReaderProvider>` | 空（必填） | 阅读器提供者列表 |

### 6.2 常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `CACHE_TTL` | 15 分钟 | 缓存生存时间 |
| `MAX_RETRY` | 2 | 最大重试次数 |
| `ALLOWED_SCHEMES` | http, https | 允许的 URL 协议 |

### 6.3 环境变量

虽然代码中未直接使用，但您可能需要配置:

```bash
# Jina Reader（如果遇到速率限制）
JINA_READER_API_KEY=your_api_key

# 代理设置（通过 JVM）
-Dhttp.proxyHost=proxy.example.com
-Dhttp.proxyPort=8080
```



## 7. 错误处理

### 7.1 错误消息

| 错误消息 | 原因 | 解决方案 |
|---------|------|---------|
| "Error: Unsupported URL protocol..." | 非 http/https 协议 | 使用 http:// 或 https:// |
| "Error: Invalid URL format (missing host)" | URL 格式错误 | 检查 URL 语法 |
| "Error: Malformed URL string" | URL 字符串无效 | 验证 URL 格式是否正确 |
| "Error: Failed to extract meaningful content..." | 所有方法都返回空 | 检查站点是否可访问 |
| "Error: Unable to fetch content..." | 网络或服务器错误 | 检查连接，稍后重试 |

### 7.2 异常处理

```java
try {
    String content = tool.webFetch(url);
    if (content.startsWith("Error:")) {
        logger.warn("Fetch failed: {}", content);
        // 处理错误情况
    } else {
        // 处理内容
    }
} catch (Exception e) {
    logger.error("Unexpected error", e);
    // 处理意外异常
}
```


### 7.3 重试行为

工具会在以下情况自动重试:
- HTTP 5xx 错误（服务器错误）
- HTTP 429（速率限制）
- 网络超时
- 连接失败

不会重试的情况:
- HTTP 4xx 错误（客户端错误）
- 无效 URL
- DNS 解析失败

## 8. 性能考虑

### 8.1 缓存优势

- 减少冗余网络调用
- 提高重复 URL 的响应时间
- LRU 驱逐防止内存膨胀
- 15 分钟 TTL 平衡新鲜度和效率

### 8.2 提供者选择影响

**性能排名**（从快到慢）:
1. HttpReaderProvider（~100-500ms）
2. JinaReaderProvider（~500-2000ms）
3. 基于浏览器的自定义提供者（~2000-10000ms）

### 8.3 内存管理

**缓存内存使用**:

```
估算内存 = maxCacheSize × 平均内容大小

示例:
100 个条目 × 50KB 平均 = ~5MB
```


**建议**:
- 监控缓存命中率
- 根据可用内存调整 `maxCacheSize`
- 分布式系统考虑外部缓存（Redis）

### 8.4 连接池

使用共享的 OkHttpClient 以获得更好的性能:

```java
OkHttpClient sharedClient = new OkHttpClient.Builder()
    .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
    .build();

WebFetchTool tool1 = WebFetchTool.builder()
    .agentsFlexHttpClient(sharedClient)
    .useDefaultProviders()
    .build();

WebFetchTool tool2 = WebFetchTool.builder()
    .agentsFlexHttpClient(sharedClient)
    .useDefaultProviders()
    .build();
```


## 9. 测试策略

### 9.1 单元测试

```java
@Test
public void testWebFetch() {
    WebFetchTool tool = WebFetchTool.builder()
        .useDefaultProviders()
        .maxCacheSize(10)
        .build();

    String content = tool.webFetch("https://example.com");

    assertNotNull(content);
    assertFalse(content.isEmpty());
    assertFalse(content.startsWith("Error:"));
}

@Test
public void testInvalidUrl() {
    WebFetchTool tool = WebFetchTool.builder()
        .useDefaultProviders()
        .build();

    String result = tool.webFetch("ftp://invalid.com");
    assertTrue(result.startsWith("Error:"));
}

@Test
public void testCaching() {
    WebFetchTool tool = WebFetchTool.builder()
        .useDefaultProviders()
        .build();

    String first = tool.webFetch("https://example.com");
    String second = tool.webFetch("https://example.com");

    assertEquals(first, second); // 应该来自缓存
}
```


### 9.2 集成测试

```java
@Test
public void testMultipleProviders() {
    // 测试各种 URL 类型
    String[] urls = {
        "https://example.com",
        "https://example.com/api/data.json",
        "https://blog.example.com/post"
    };

    WebFetchTool tool = WebFetchTool.builder()
        .useDefaultProviders()
        .build();

    for (String url : urls) {
        String content = tool.webFetch(url);
        assertNotNull(content);
        System.out.println("Fetched " + url + ": " + content.length() + " chars");
    }
}
```


### 9.3 性能测试

```java
@Test
public void testCachePerformance() {
    WebFetchTool tool = WebFetchTool.builder()
        .useDefaultProviders()
        .build();

    String url = "https://example.com";

    // 第一次调用（缓存未命中）
    long start = System.currentTimeMillis();
    tool.webFetch(url);
    long firstCall = System.currentTimeMillis() - start;

    // 第二次调用（缓存命中）
    start = System.currentTimeMillis();
    tool.webFetch(url);
    long secondCall = System.currentTimeMillis() - start;

    assertTrue(secondCall < firstCall / 10); // 缓存应该快 10 倍
}
```


## 10. 故障排除

### 10.1 常见问题

**问题**: 所有提供者都失败
```
解决方案:
1. 检查网络连接
2. 验证 URL 从您的环境是否可访问
3. 检查防火墙/代理设置
4. 查看日志中特定提供者的错误
```


**问题**: 内容被截断
```
解决方案:
1. 增加 maxContentLength:
   .maxContentLength(200_000)
2. 检查截断是否在预期长度发生
```


**问题**: 缓存不工作
```
解决方案:
1. 验证使用的是相同的 URL（区分大小写）
2. 检查缓存 TTL 是否已过期（15 分钟）
3. 确保缓存未满且在驱逐条目
```


**问题**: 选择了错误的提供者
```
解决方案:
1. 调整提供者评分
2. 检查自适应指标是否正确学习
3. 审查 provider.supports() 逻辑
```


### 10.2 调试技巧

**启用日志**:
```java
// 添加 SLF4J 日志配置
logging.level.com.agentsflex.tool.webfetch.WebFetchTool=DEBUG
```


**监控提供者性能**:
```java
// 访问路由器指标（如果暴露）
// 跟踪哪些提供者成功/失败以进行调试
```


**测试单个提供者**:
```java
WebReaderProvider provider = new JinaReaderProvider(client);
System.out.println("Supports: " + provider.supports(url));
System.out.println("Score: " + provider.score(url));
String content = provider.read(url);
System.out.println("Content length: " + content.length());
```


### 10.3 日志分析

**成功抓取**:
```
INFO  WebFetchTool - HTTP fetch resulted in short/empty content for https://example.com, trying reader fallback...
INFO  AdaptiveWebReaderRouter - Provider jina succeeded for https://example.com
```


**失败抓取**:
```
WARN  AdaptiveWebReaderRouter - Provider http failed: https://example.com -> HTTP 403
WARN  AdaptiveWebReaderRouter - Provider jina failed: https://example.com -> HTTP 503
ERROR WebFetchTool - Web fetch failed for url: https://example.com
```


## 11. 最佳实践

### 11.1 提供者配置

✅ **推荐**: 使用多个提供者以实现冗余
```java
WebFetchTool tool = WebFetchTool.builder()
    .addProvider(new JinaReaderProvider(client))  // 主要
    .addProvider(new HttpReaderProvider(client))  // 回退
    .build();
```


❌ **避免**: 使用单一提供者而无回退
```java
// 有风险 - 如果提供者失败没有回退
WebFetchTool tool = WebFetchTool.builder()
    .addProvider(new SingleProvider())
    .build();
```


### 11.2 资源管理

✅ **推荐**: 使用完毕后关闭工具
```java
try (WebFetchTool tool = WebFetchTool.builder()
    .useDefaultProviders()
    .build()) {
    String content = tool.webFetch(url);
    // 处理内容
}
```


❌ **避免**: 资源泄漏
```java
WebFetchTool tool = WebFetchTool.builder()
    .useDefaultProviders()
    .build();
// 从未关闭 - 缓存和连接泄漏
```

### 11.3 共享资源

✅ **推荐**: 共享 OkHttpClient 实例
```java
OkHttpClient sharedClient = OkHttpClientUtil.buildDefaultClient();

WebFetchTool tool1 = createTool(sharedClient);
WebFetchTool tool2 = createTool(sharedClient);
```


❌ **避免**: 为每个工具创建新客户端
```java
// 每个工具创建自己的连接池
WebFetchTool tool1 = WebFetchTool.builder()
    .agentsFlexHttpClient(new OkHttpClient())
    .build();
```


### 11.4 错误处理

✅ **推荐**: 检查错误响应
```java
String content = tool.webFetch(url);
if (content.startsWith("Error:")) {
    logger.warn("Fetch failed: {}", content);
    handleFailure();
} else {
    processContent(content);
}
```


❌ **避免**: 假设成功
```java
// 如果内容为 null 或错误消息可能会崩溃
int length = tool.webFetch(url).length();
```




</div>
