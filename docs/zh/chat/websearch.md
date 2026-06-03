# WebSearch 开发文档

## 1. 概述

### 1.1 简介
`agents-flex-websearch` 是 Agents-Flex 框架的网络搜索模块，为 AI Agent 提供网络内容搜索能力。该模块采用插件化设计，支持多种搜索引擎提供商（如 Bocha、Brave），并提供了完整的领域过滤、结果格式化等功能。

### 1.2 核心特性
- 🔌 **插件化架构**：基于 `SearchProvider` 接口，轻松扩展新的搜索引擎
- 🎯 **域名过滤**：支持白名单（allowedDomains）和黑名单（blockedDomains）过滤
- 📝 **Markdown 输出**：自动将搜索结果格式化为 Markdown 格式
- 🔧 **Tool 集成**：无缝集成到 Agents-Flex 的 Tool 系统，供 LLM 调用
- 🌐 **多提供商支持**：内置 Bocha 和 Brave 搜索引擎支持

### 1.3 技术栈
- **Java 8+**
- **OkHttp3**：HTTP 客户端
- **FastJSON2**：JSON 解析
- **Agents-Flex Core**：核心框架依赖

---

## 2. 模块结构

```
agents-flex-websearch/
├── src/main/java/com/agentsflex/websearch/
│   ├── SearchProvider.java          # 搜索引擎提供商接口
│   ├── SearchRequest.java           # 搜索请求对象
│   ├── SearchResult.java            # 搜索结果对象
│   ├── WebSearchTool.java           # 搜索工具类（LLM Tool）
│   ├── bocha/
│   │   └── BochaSearchProvider.java # Bocha 搜索引擎实现
│   └── brave/
│       └── BraveSearchProvider.java # Brave 搜索引擎实现
├── pom.xml
└── README.md
```


---

## 3. 核心组件

### 3.1 SearchProvider（搜索引擎接口）

**位置**：`com.agentsflex.websearch.SearchProvider`

**职责**：定义搜索引擎提供商的标准接口

```java
public interface SearchProvider {
    /**
     * 执行搜索
     * @param query 搜索请求对象
     * @return 搜索结果列表
     */
    List<SearchResult> search(SearchRequest query);
}
```


**设计说明**：
- 所有搜索引擎提供商必须实现此接口
- 返回空列表表示无结果或搜索失败（不抛出异常）
- 实现类应处理网络异常、API 限流等情况

---

### 3.2 SearchRequest（搜索请求）

**位置**：`com.agentsflex.websearch.SearchRequest`

**字段说明**：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| query | String | - | 搜索关键词（必填） |
| maxResults | Integer | 10 | 最大返回结果数 |
| allowedDomains | List\<String\> | null | 允许的域名白名单 |
| blockedDomains | List\<String\> | null | 禁止的域名黑名单 |

**使用示例**：
```java
SearchRequest request = new SearchRequest();
request.setQuery("Agents-Flex 框架");
request.setMaxResults(5);
request.setAllowedDomains(Arrays.asList("github.com", "gitee.com"));
request.setBlockedDomains(Arrays.asList("spam.com"));
```


---

### 3.3 SearchResult（搜索结果）

**位置**：`com.agentsflex.websearch.SearchResult`

**继承关系**：`SearchResult extends Metadata`

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| title | String | 结果标题 |
| url | String | 结果链接 |
| description | String | 结果描述/摘要 |
| frontMatter | Map\<String, Object\> | 元数据（可选） |

**核心方法**：

#### `toMarkdown()`
将搜索结果转换为 Markdown 格式：

```markdown
---
key: value
---
# 标题

URL: https://example.com

描述内容...
```


#### Builder 模式构建
```java
SearchResult result = SearchResult.builder()
    .title("Agents-Flex 官方文档")
    .url("https://agentsflex.com/docs")
    .description("Agents-Flex 是一个灵活的 AI Agent 框架")
    .frontMatter("source", "web")
    .build();
```


---

### 3.4 WebSearchTool（搜索工具）

**位置**：`com.agentsflex.websearch.WebSearchTool`

**职责**：将搜索功能封装为 LLM 可调用的 Tool

**注解说明**：
```java
@ToolDef(
    name = "web_search",
    description = "Search web content and return relevant results with optional domain filtering"
)
```


**主要方法**：

#### `webSearch(String query, List<String> allowedDomains, List<String> blockedDomains)`

**参数**：
- `query`（必填）：搜索关键词
- `allowedDomains`（可选）：只包含这些域名的结果
- `blockedDomains`（可选）：排除这些域名的结果

**返回值**：Markdown 格式的搜索结果字符串，多个结果用 `\n\n-----\n\n` 分隔

**域名过滤逻辑**：
1. 提取 URL 的域名（支持 http/https 和无协议 URL）
2. 白名单匹配：如果设置了白名单，只保留匹配的域名
3. 黑名单匹配：如果设置了黑名单，排除匹配的域名
4. 子域名匹配：`example.com` 会匹配 `www.example.com`

**Builder 构造**：
```java
WebSearchTool tool = WebSearchTool.builder()
    .provider(new BochaSearchProvider(apiKey))
    .maxResults(10)
    .build();
```


---

### 3.5 BochaSearchProvider（博查搜索引擎）

**配置项**：

| 配置 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| apiKey | String | - | API 密钥（必填） |
| httpClient | OkHttpClient | new OkHttpClient() | HTTP 客户端 |
| summary | Boolean | false | 是否启用摘要生成 |

**请求参数映射**：
```json
{
  "query": "搜索词",
  "count": 10,
  "summary": false,
  "freshness": "noLimit",
  "include": "domain1.com|domain2.com",
  "exclude": "spam.com"
}
```


**响应解析优先级**：
1. 优先使用 `summary` 字段（如果启用）
2. 其次使用 `snippet` 字段

**使用示例**：
```java
BochaSearchProvider provider = new BochaSearchProvider("your-api-key");
provider.setSummary(true); // 启用智能摘要
```



### 3.6 BraveSearchProvider（Brave 搜索引擎）


**配置项**：

| 配置 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| apiKey | String | - | API 密钥（必填） |
| httpClient | OkHttpClient | 默认客户端 | HTTP 客户端 |

**请求参数**：
- `q`：搜索关键词
- `count`：结果数量

**认证方式**：
```
Header: X-Subscription-Token: <apiKey>
```


**结果来源**：
- 网页搜索结果（`web.results`）
- 视频搜索结果（`videos.results`）

**使用示例**：
```java
BraveSearchProvider provider = new BraveSearchProvider("your-api-key");
```



## 4. 快速开始

### 4.1 添加依赖

**Maven**：
```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-websearch</artifactId>
    <version>${revision}</version>
</dependency>
```


**Gradle**：
```groovy
implementation 'com.agentsflex:agents-flex-websearch:${revision}'
```


### 4.2 基础使用示例

#### 示例 1：直接使用 SearchProvider

```java
import com.agentsflex.websearch.*;
import com.agentsflex.websearch.bocha.BochaSearchProvider;

public class SimpleSearchDemo {
    public static void main(String[] args) {
        // 创建搜索引擎提供商
        SearchProvider provider = new BochaSearchProvider("YOUR_BOCHA_API_KEY");

        // 构建搜索请求
        SearchRequest request = new SearchRequest();
        request.setQuery("什么是 Agents-Flex");
        request.setMaxResults(5);

        // 执行搜索
        List<SearchResult> results = provider.search(request);

        // 处理结果
        for (SearchResult result : results) {
            System.out.println("标题: " + result.getTitle());
            System.out.println("URL: " + result.getUrl());
            System.out.println("描述: " + result.getDescription());
            System.out.println("---");
        }
    }
}
```


#### 示例 2：作为 LLM Tool 使用

```java
import com.agentsflex.websearch.WebSearchTool;
import com.agentsflex.core.model.chat.tool.annotation.ToolScanner;
import com.agentsflex.core.prompt.MemoryPrompt;

public class ToolSearchDemo {
    public static void main(String[] args) {
        // 创建 WebSearchTool
        WebSearchTool searchTool = WebSearchTool.builder()
            .provider(new BochaSearchProvider("YOUR_BOCHA_API_KEY"))
            .maxResults(10)
            .build();

        // 注册到 Prompt
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addTools(ToolScanner.scan(searchTool));

        // 添加到 ChatModel 并使用...
        // chatModel.chatStream(prompt, listener);
    }
}
```


#### 示例 3：带域名过滤的搜索

```java
WebSearchTool searchTool = WebSearchTool.builder()
    .provider(new BochaSearchProvider("YOUR_BOCHA_API_KEY"))
    .build();

// LLM 调用时会自动应用域名过滤
String markdownResult = searchTool.webSearch(
    "Java AI 框架",
    Arrays.asList("github.com", "gitee.com"),  // 只允许这两个域名
    Arrays.asList("spam-site.com")              // 排除这个域名
);

System.out.println(markdownResult);
```


### 4.3 完整实战示例

参考 `demos/websearch-demo` 项目：

```java
public class WebSearchDemo {
    public static void main(String[] args) throws InterruptedException {
        // 1. 创建 ChatModel
        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .endpoint("https://ai.gitee.com")
            .apiKey(System.getenv("GITEE_APIKEY"))
            .model("Qwen3.5-35B-A3B")
            .buildModel();

        // 2. 创建 Prompt 并注册工具
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("请注意：在用户给出的问题中，请先使用 TodoWrite 来分解用户问题，然后再按步骤进行执行。");

        // 注册 WebSearchTool
        prompt.addTools(ToolScanner.scan(WebSearchTool.builder()
            .provider(new BochaSearchProvider(System.getenv("BOCHA_APIKEY")))
            .build()));

        // 3. 添加用户消息
        UserMessage userMessage = new UserMessage("帮我搜索一下什么是 Agents-flex，并给出示例代码。");
        prompt.addMessage(userMessage);

        // 4. 执行流式对话
        StreamResponseListener listener = new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                if (response.getMessage().getContent() != null) {
                    System.out.print(response.getMessage().getContent());
                }

                // 处理工具调用
                if (response.getMessage().isFinalDelta() && response.getMessage().getToolCalls() != null) {
                    prompt.addMessage(response.getMessage());
                    List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();
                    prompt.addMessages(toolMessages);

                    // 继续对话
                    chatModel.chatStream(prompt, this);
                }
            }
        };

        chatModel.chatStream(prompt, listener);
        Thread.sleep(200000L);
    }
}
```



## 5. API 详解

### 5.1 环境变量配置

推荐通过环境变量管理 API Key：

```bash
# Bocha API Key
export BOCHA_APIKEY="your-bocha-api-key"

# Brave API Key
export BRAVE_APIKEY="your-brave-api-key"

# Gitee API Key（用于测试）
export GITEE_APIKEY="your-gitee-api-key"
```


Java 代码中获取：
```java
String apiKey = System.getenv("BOCHA_APIKEY");
```


### 5.2 域名过滤机制

#### 域名提取算法

```java
private String extractDomain(String url) {
    if (StringUtil.noText(url)) {
        return "";
    }

    try {
        String u = url.trim();

        // 自动补全协议
        if (!u.startsWith("http")) {
            u = "https://" + u;
        }

        URI uri = URI.create(u);
        String host = uri.getHost();

        return host == null ? u.toLowerCase() : host.toLowerCase();

    } catch (Exception e) {
        return url.toLowerCase();
    }
}
```


#### 域名匹配规则

```java
private boolean match(String domain, Set<String> rules) {
    for (String r : rules) {
        // 精确匹配或子域名匹配
        if (domain.equals(r) || domain.endsWith("." + r)) {
            return true;
        }
    }
    return false;
}
```


**匹配示例**：
- 规则：`github.com`
- 匹配成功：`github.com`、`www.github.com`、`api.github.com`
- 匹配失败：`mygithub.com`、`github.com.cn`

### 5.3 Markdown 格式化

`SearchResult.toMarkdown()` 生成的格式：

```markdown
---
source: web
relevance: 0.95
---
# Agents-Flex 官方文档

URL: https://agentsflex.com/docs/intro

Agents-Flex 是一个基于 Java 的灵活 AI Agent 开发框架...
```


**特点**：
- Front Matter 区域：存储元数据（YAML 格式）
- 标题：使用一级标题
- URL：单独一行显示
- 描述：纯文本内容


## 6. 自定义搜索引擎提供商

### 6.1 实现步骤

#### Step 1：创建 Provider 类

```java
package com.agentsflex.websearch.mycustom;

import com.agentsflex.core.util.StringUtil;
import com.agentsflex.websearch.SearchProvider;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MyCustomSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.mysearch.com/v1/search";

    private final String apiKey;
    private final OkHttpClient httpClient;

    public MyCustomSearchProvider(String apiKey) {
        this(apiKey, new OkHttpClient());
    }

    public MyCustomSearchProvider(String apiKey, OkHttpClient httpClient) {
        if (StringUtil.noText(apiKey)) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        if (request == null || StringUtil.noText(request.getQuery())) {
            return Collections.emptyList();
        }

        try {
            String body = execute(request);
            if (StringUtil.noText(body)) {
                return Collections.emptyList();
            }

            // 解析 JSON 响应
            JSONObject root = JSON.parseObject(body);
            return parse(root);

        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private String execute(SearchRequest request) throws IOException {
        // 构建请求体
        JSONObject bodyJson = new JSONObject();
        bodyJson.put("q", request.getQuery());
        bodyJson.put("limit", request.getMaxResults());

        RequestBody body = RequestBody.create(
            bodyJson.toJSONString(),
            MediaType.parse("application/json")
        );

        Request httpRequest = new Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer " + apiKey)
            .post(body)
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            ResponseBody rb = response.body();
            return rb != null ? rb.string() : null;
        }
    }

    private List<SearchResult> parse(JSONObject root) {
        List<SearchResult> results = new ArrayList<>();

        // 根据实际 API 响应结构解析
        JSONArray items = root.getJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);

                SearchResult result = SearchResult.builder()
                    .title(item.getString("title"))
                    .url(item.getString("link"))
                    .description(item.getString("snippet"))
                    .build();

                results.add(result);
            }
        }

        return results;
    }
}
```


#### Step 2：使用自定义 Provider

```java
SearchProvider provider = new MyCustomSearchProvider("your-api-key");

WebSearchTool tool = WebSearchTool.builder()
    .provider(provider)
    .maxResults(10)
    .build();
```


### 6.2 最佳实践建议

1. **异常处理**：捕获所有异常并返回空列表，避免中断 Agent 执行流程
2. **空值校验**：对输入参数和 API 响应进行严格的空值检查
3. **超时设置**：配置合理的 HTTP 超时时间
4. **重试机制**：可在 Provider 内部实现重试逻辑
5. **日志记录**：关键步骤添加日志便于调试

### 6.3 高级特性：自定义 HTTP 客户端

```java
OkHttpClient customClient = new OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build();

SearchProvider provider = new BochaSearchProvider(apiKey, customClient);
```



## 7. 最佳实践

### 7.1 性能优化

#### 控制结果数量
```java
// 根据实际需求设置合理的结果数
WebSearchTool tool = WebSearchTool.builder()
    .maxResults(5)  // 不要设置过大，避免 Token 浪费
    .build();
```


#### 使用域名过滤减少无关结果
```java
// 限定权威来源
List<String> allowedDomains = Arrays.asList(
    "github.com",
    "stackoverflow.com",
    "official-docs.com"
);
```


### 7.2 错误处理

```java
try {
    List<SearchResult> results = provider.search(request);
    if (results.isEmpty()) {
        System.out.println("未找到相关结果");
    } else {
        // 处理结果
    }
} catch (Exception e) {
    // Provider 内部已处理异常，此处通常不会抛出
    log.error("搜索失败", e);
}
```


### 7.3 与 LLM 配合使用

#### System Prompt 建议
```
你是一个智能助手，可以访问网络搜索获取最新信息。
当用户询问实时信息、新闻、技术文档时，请使用 web_search 工具。
搜索时请：
1. 使用精准的关键词
2. 必要时限制域名范围以提高质量
3. 综合多个搜索结果给出答案
```


#### Tool 组合使用
```java
// 结合 WebFetchTool 获取详细内容
prompt.addTools(ToolScanner.scan(WebSearchTool.builder()
    .provider(new BochaSearchProvider(apiKey))
    .build()));
prompt.addTools(ToolScanner.scan(WebFetchTool.builder()
    .useDefaultProviders()
    .build()));
```


**工作流程**：
1. 使用 `WebSearchTool` 搜索相关网页
2. 从结果中提取重要 URL
3. 使用 `WebFetchTool` 获取网页全文
4. 综合分析后回答用户

### 7.4 安全建议

1. **API Key 管理**：
    - 使用环境变量或密钥管理服务
    - 不要硬编码在代码中
    - 定期轮换密钥

2. **域名白名单**：
    - 对于企业应用，建议设置允许的域名列表
    - 避免访问恶意或不可信网站

3. **结果验证**：
    - 对搜索结果进行必要的内容审核
    - 注意防范注入攻击

### 7.5 监控与日志

```java
// 启用请求日志
ChatMessageLogger.setLogger(new IChatMessageLogger() {
    @Override
    public void logRequest(ChatConfig config, String message) {
        logger.info("搜索请求: {}", message);
    }

    @Override
    public void logResponse(ChatConfig config, String message) {
        logger.info("搜索结果: {}", message);
    }
});
```



## 8. 常见问题

### Q1: 如何获取 Bocha API Key？

**A**: 访问 [Bocha 官网](https://bocha.cn) 注册账号，在控制台获取 API Key。

### Q2: 如何获取 Brave API Key？

**A**: 访问 [Brave Search API](https://brave.com/search/api/) 注册并订阅服务。

### Q3: 搜索结果返回空列表怎么办？

**A**: 可能原因：
1. API Key 无效或未配置
2. 网络连接问题
3. 搜索词过于冷门
4. API 配额已用完

**排查步骤**：
```java
// 1. 检查 API Key
System.out.println("API Key: " + System.getenv("BOCHA_APIKEY"));

// 2. 测试简单搜索
SearchRequest request = new SearchRequest();
request.setQuery("test");
List<SearchResult> results = provider.search(request);
System.out.println("结果数量: " + results.size());
```


### Q4: 如何调试域名过滤功能？

**A**:
```java
WebSearchTool tool = WebSearchTool.builder()
    .provider(new BochaSearchProvider(apiKey))
    .build();

// 手动测试过滤逻辑
String result = tool.webSearch(
    "Java 教程",
    Arrays.asList("example.com"),
    null
);
System.out.println(result);
```


### Q5: 支持哪些搜索引擎？

**A**: 目前内置支持：
- ✅ Bocha Search（博查）
- ✅ Brave Search

可自行实现 `SearchProvider` 接口扩展其他搜索引擎（如 Google、Bing、百度等）。

### Q6: 如何处理大量搜索结果？

**A**:
```java
// 1. 限制返回数量
request.setMaxResults(10);

// 2. 分页处理（如果 Provider 支持）
for (int page = 0; page < totalPages; page++) {
    request.setMaxResults(10);
    // 执行搜索...
}

// 3. 结果去重
Set<String> seenUrls = new HashSet<>();
List<SearchResult> uniqueResults = results.stream()
    .filter(r -> seenUrls.add(r.getUrl()))
    .collect(Collectors.toList());
```


### Q7: 如何在 Spring Boot 中使用？

**A**:
```java
@Configuration
public class WebSearchConfig {

    @Value("${bocha.api.key}")
    private String bochaApiKey;

    @Bean
    public WebSearchTool webSearchTool() {
        return WebSearchTool.builder()
            .provider(new BochaSearchProvider(bochaApiKey))
            .maxResults(10)
            .build();
    }
}
```
```yaml
# application.yml
bocha:
  api:
    key: ${BOCHA_APIKEY}
```


### Q8: 搜索结果中的 Front Matter 有什么用？

**A**: Front Matter 用于存储额外元数据，例如：
```java
SearchResult result = SearchResult.builder()
    .title("示例")
    .url("https://example.com")
    .description("描述")
    .frontMatter("source", "web")
    .frontMatter("relevance_score", 0.95)
    .frontMatter("crawl_date", "2024-01-01")
    .build();
```


这些信息会出现在 Markdown 输出的头部，可用于后续处理。

### Q9: 如何自定义 HTTP 超时时间？

**A**:
```java
OkHttpClient client = new OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build();

SearchProvider provider = new BochaSearchProvider(apiKey, client);
```


### Q10: 是否支持异步搜索？

**A**: 当前版本仅支持同步搜索。如需异步，可结合 CompletableFuture 使用：

```java
CompletableFuture<List<SearchResult>> future = CompletableFuture.supplyAsync(() -> {
    return provider.search(request);
});

future.thenAccept(results -> {
    // 处理结果
});
```

