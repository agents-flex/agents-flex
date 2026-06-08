<div v-pre>

# Agents-Flex LLM Wiki 开发文档

## 目录

1. [概述](#概述)
2. [模块架构](#模块架构)
3. [核心组件](#核心组件)
4. [设计理念](#设计理念)
5. [快速开始](#快速开始)
6. [API 参考](#api-参考)
7. [最佳实践](#最佳实践)
8. [扩展开发](#扩展开发)
9. [常见问题](#常见问题)


## 概述

### 什么是 Wiki 模块？

Wiki 模块是 Agents-Flex 框架中的一个知识管理组件，专为 LLM（大语言模型）应用设计。它提供了一种 **渐进式披露（Progressive Disclosure）** 的知识访问机制，允许 AI Agent 以树状结构导航和检索层次化的知识库。

### 核心价值

- **原生树状结构支持**：Wiki 节点可以直接包含子节点列表，形成完整的知识树
- **自动子节点展示**：获取 Wiki 内容时，子节点信息会自动附加在 Markdown 输出中
- **按需加载**：子节点内容不会直接嵌入，必须通过工具调用显式获取，避免上下文过载
- **渐进式探索**：AI Agent 可以根据需要递归地深入探索知识树的各个分支
- **灵活的数据源**：支持自定义 WikiProvider，可从文件系统、数据库、API 等多种来源加载内容

### 应用场景

- 技术文档查询系统
- 企业知识库助手
- API 文档智能导航
- 产品手册交互式查询
- 教育培训材料浏览

---

## 模块架构

### 项目结构

```
agents-flex-wiki/
├── src/main/java/com/agentsflex/wiki/
│   ├── Wiki.java              # Wiki 数据模型（支持子节点）
│   ├── WikiProvider.java      # Wiki 提供者接口（SPI）
│   └── WikiTool.java          # Wiki 工具构建器
├── pom.xml
```


### 依赖关系

```xml
<dependencies>
    <dependency>
        <groupId>com.agentsflex</groupId>
        <artifactId>agents-flex-core</artifactId>
    </dependency>
</dependencies>
```


Wiki 模块仅依赖于 `agents-flex-core`，保持轻量级设计。


## 核心组件

### 1. Wiki - 知识节点模型

`Wiki` 类代表知识树中的一个节点，包含以下属性：

#### 属性说明

| 属性 | 类型 | 说明 |
|------|------|------|
| `path` | String | Wiki 路径标识符，用于唯一标识一个节点 |
| `title` | String | Wiki 标题 |
| `summary` | String | Wiki 摘要/描述，用于预览 |
| `content` | String | Wiki 完整内容（Markdown 格式） |
| `children` | List\<Wiki\> | **子节点列表**（新增） |
| `frontMatter` | Map<String, Object> | 前置元数据，存储额外信息 |

#### 构造方法

```java
// 空构造
public Wiki()

// 基础构造
public Wiki(String path, String title)

// 带摘要
public Wiki(String path, String title, String summary)

// 完整构造
public Wiki(String path, String title, String summary, Map<String, Object> frontMatter)
```


#### 关键方法

##### toXml() - 将 Wiki 转换为 XML 格式（用于工具描述）

```java
public String toXml() {
    // 输出示例：
    // <wiki>
    //      <path>active-record.md</path>
    //      <title>Active Record</title>
    //      <summary>模式中，对象中既有持久存储的数据...</summary>
    // </wiki>
}
```


**注意**：`toXml()` 方法不包含 `children` 信息，仅用于在工具描述中展示可用的 Wiki 列表。

##### toMarkdown() - 将 Wiki 转换为 Markdown 格式（用于内容返回）

```java
public String toMarkdown() {
    // 输出示例：
    // ---
    // path: active-record.md
    // title: Active Record
    // summary: 模式中，对象中既有持久存储的数据...
    // ---
    //
    // [完整内容...]
    //
    // ## Children Wikis:
    // <available_wikis>
    //  <wiki>
    //      <path>child1.md</path>
    //      <title>子节点 1</title>
    //      <summary>子节点描述</summary>
    //  </wiki>
    //  <wiki>
    //      <path>child2.md</path>
    //      <title>子节点 2</title>
    //      <summary>子节点描述</summary>
    //  </wiki>
    // </available_wikis>
}
```


**重要特性**：当 Wiki 包含 `children` 时，`toMarkdown()` 会自动在内容末尾追加 "Children Wikis" 部分，以 XML 格式列出所有子节点，方便 LLM 了解可继续探索的子主题。

##### 子节点操作方法

**addChild()** - 添加单个子节点

```java
public void addChild(Wiki child) {
    if (this.children == null) {
        this.children = new java.util.ArrayList<>();
    }
    this.children.add(child);
}
```


**addChildren()** - 批量添加子节点

```java
public void addChildren(List<Wiki> children) {
    if (this.children == null) {
        this.children = new java.util.ArrayList<>();
    }
    this.children.addAll(children);
}
```


**getChildren() / setChildren()** - 获取/设置子节点列表

```java
public List<Wiki> getChildren()
public void setChildren(List<Wiki> children)
```


##### 元数据操作

**addFrontMatter()** - 添加元数据

```java
public void addFrontMatter(String key, Object value)
```



### 2. WikiProvider - 知识提供者接口

`WikiProvider` 是一个 SPI（Service Provider Interface），定义了如何获取 Wiki 内容的契约。

```java
public interface WikiProvider {
    /**
     * 根据路径获取 Wiki 内容
     * @param path Wiki 路径标识
     * @return Wiki 对象，如果不存在则返回 null
     */
    Wiki getWiki(String path);
}
```


#### 实现要求

开发者需要实现此接口来提供自定义的数据源接入：

- 从文件系统加载 Markdown 文件
- 从数据库查询知识条目
- 从远程 API 获取内容
- 从 CMS 系统读取文档
- **构建子节点关系**（新增）


### 3. WikiTool - Wiki 工具构建器

`WikiTool` 是核心工具类，负责构建一个可供 LLM 调用的 Tool 对象。

#### 构建器模式

```java
Tool wikiTool = WikiTool.builder()
    .addWikis(wikisList)           // 添加可用的 Wiki 列表
    .wikiProvider(provider)        // 设置 Wiki 提供者
    .build();                      // 构建 Tool
```


#### Builder 方法

| 方法 | 说明 |
|------|------|
| `addWiki(Wiki wiki)` | 添加单个 Wiki |
| `addWikis(List<Wiki> wikis)` | 批量添加 Wiki |
| `wikiProvider(WikiProvider provider)` | 设置 Wiki 提供者（必需） |
| `toolDescriptionTemplate(String template)` | 自定义工具描述模板 |
| `build()` | 构建并返回 Tool 对象 |

#### 默认工具描述模板

```java
private static final String TOOL_DESCRIPTION_TEMPLATE =
    "Access a hierarchical wiki knowledge system with progressive and recursive disclosure\n" +
    "\n" +
    "<wiki_instructions>\n" +
    "This tool provides access to a hierarchical Wiki knowledge system.\n" +
    "\n" +
    "Core concepts:\n" +
    "- Each Wiki is a node in a knowledge tree identified by a path\n" +
    "- A Wiki may have child Wikis (sub-nodes)\n" +
    "- Child Wikis are NOT embedded directly; they must be retrieved via new tool calls\n" +
    "\n" +
    "Navigation model:\n" +
    "- <available_wikis> represents the current node's accessible sub-wikis\n" +
    "- Each entry in <available_wikis> is a navigable child node\n" +
    "- To access a child wiki, call this tool again with its path\n" +
    "\n" +
    "Progressive + recursive disclosure strategy:\n" +
    "1. Start from current Wiki (path result)\n" +
    "2. Inspect title, description, frontMatter\n" +
    "3. Use available_wikis to detect possible subtopics\n" +
    "4. Decide whether to:\n" +
    "   - Answer directly from current wiki\n" +
    "   - OR navigate into one or more child wikis (recursive expansion)\n" +
    "\n" +
    "Important rules:\n" +
    "- Treat Wiki as a navigable knowledge tree, not a single document\n" +
    "- Always consider whether a child wiki may contain more precise information\n" +
    "- Only call child wiki when needed (avoid over-navigation)\n" +
    "- Do NOT assume missing content exists in current node\n" +
    "</wiki_instructions>\n" +
    "\n" +
    "<available_wikis>\n" +
    "%s\n"  // 此处插入可用 Wiki 列表的 XML
    "</available_wikis>";
```


#### 工具参数

构建的 Tool 接受以下参数：

```json
{
  "name": "get_wiki_content",
  "parameters": {
    "path": {
      "type": "string",
      "required": true,
      "description": "The wiki path."
    }
  }
}
```


#### 执行逻辑

```java
.function(stringStringMap -> {
    String path = (String) stringStringMap.get("path");
    Wiki wiki = wikiProvider.getWiki(path);
    if (wiki != null) {
        return wiki.toMarkdown();  // 返回 Markdown 格式内容（包含子节点）
    }
    return "Wiki not found: " + path;
})
```



## 设计理念

### 渐进式披露（Progressive Disclosure）

这是 Wiki 模块的核心设计原则：

1. **初始状态**：LLM 只能看到顶层 Wiki 的列表（path、title、summary）
2. **按需加载**：当 LLM 需要深入了解某个主题时，调用工具获取具体内容
3. **自动子节点提示**：获取的内容自动包含 "Children Wikis" 部分，告知 LLM 有哪些子主题可以探索
4. **递归探索**：LLM 可以根据子节点列表继续深入获取详细内容

#### 优势

- ✅ **避免上下文溢出**：不会一次性加载所有知识
- ✅ **提高响应速度**：只加载必要的内容
- ✅ **自然的知识导航**：子节点信息随内容一起返回，无需额外查询
- ✅ **模拟人类阅读**：像翻阅书籍目录一样逐步深入
- ✅ **降低 Token 成本**：减少不必要的信息传输

### 树状知识结构

#### 更新后的工作流程

```
根节点（可用 Wiki 列表）
├── introduction.md
├── quick-start.md
└── advanced.md
    ├── configuration.md（子节点，在 advanced.md 的 Markdown 中列出）
    ├── plugins.md（子节点，在 advanced.md 的 Markdown 中列出）
    └── deployment.md（子节点，在 advanced.md 的 Markdown 中列出）
```


**工作流程**：

1. LLM 看到根节点的 Wiki 列表
2. LLM 调用 `get_wiki_content(path="advanced.md")`
3. 返回的内容包含：
    - `advanced.md` 的完整内容
    - **"Children Wikis"** 部分，列出 `configuration.md`、`plugins.md`、`deployment.md`
4. LLM 根据需要继续调用 `get_wiki_content(path="configuration.md")`


## 快速开始

### 1. 添加依赖

在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-wiki</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```


### 2. 创建带有子节点的 Wiki 数据

```java
import com.agentsflex.wiki.Wiki;
import java.util.*;

public class MyWikis {

    public static List<Wiki> getWikis() {
        List<Wiki> rootWikis = new ArrayList<>();

        // 创建顶层 Wiki
        Wiki introduction = new Wiki(
            "introduction.md",
            "项目介绍",
            "Agents-Flex 是一个优雅的 Java LLM 应用开发框架"
        );

        Wiki quickStart = new Wiki(
            "quick-start.md",
            "快速开始",
            "5 分钟上手 Agents-Flex"
        );

        // 创建带有子节点的 Wiki
        Wiki advanced = new Wiki(
            "advanced.md",
            "高级功能",
            "Agents-Flex 的高级特性和配置"
        );

        // 添加子节点
        Wiki config = new Wiki(
            "advanced/configuration.md",
            "高级配置",
            "详细的配置选项和最佳实践"
        );

        Wiki plugins = new Wiki(
            "advanced/plugins.md",
            "插件系统",
            "如何开发和集成插件"
        );

        Wiki deployment = new Wiki(
            "advanced/deployment.md",
            "部署指南",
            "生产环境部署策略"
        );

        // 方式 1：使用 addChild
        advanced.addChild(config);
        advanced.addChild(plugins);
        advanced.addChild(deployment);

        // 方式 2：使用 addChildren
        // advanced.addChildren(Arrays.asList(config, plugins, deployment));

        rootWikis.add(introduction);
        rootWikis.add(quickStart);
        rootWikis.add(advanced);

        return rootWikis;
    }
}
```


### 3. 实现 WikiProvider（支持子节点）

```java
import com.agentsflex.wiki.Wiki;
import com.agentsflex.wiki.WikiProvider;
import com.agentsflex.core.util.IOUtil;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class MyWikiProvider implements WikiProvider {

    private static final String WIKI_ROOT = "/path/to/wiki/files";

    // 定义层级关系
    private static final Map<String, List<String>> HIERARCHY = new HashMap<>();

    static {
        // 定义哪些 Wiki 有子节点
        HIERARCHY.put("advanced.md", Arrays.asList(
            "advanced/configuration.md",
            "advanced/plugins.md",
            "advanced/deployment.md"
        ));
    }

    @Override
    public Wiki getWiki(String path) {
        File wikiFile = new File(WIKI_ROOT, path);

        if (!wikiFile.exists()) {
            return null;
        }

        try {
            // 读取文件内容
            String content = IOUtil.readUtf8(Files.newInputStream(wikiFile.toPath()));

            // 创建 Wiki 对象
            Wiki wiki = new Wiki(path, wikiFile.getName(), "");
            wiki.setContent(content);

            // 如果有子节点，创建并添加子节点
            List<String> childPaths = HIERARCHY.get(path);
            if (childPaths != null) {
                for (String childPath : childPaths) {
                    Wiki child = createChildWiki(childPath);
                    if (child != null) {
                        wiki.addChild(child);
                    }
                }
            }

            return wiki;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load wiki: " + path, e);
        }
    }

    private Wiki createChildWiki(String childPath) {
        File childFile = new File(WIKI_ROOT, childPath);
        if (!childFile.exists()) {
            return null;
        }

        try {
            String content = IOUtil.readUtf8(Files.newInputStream(childFile.toPath()));
            Wiki child = new Wiki(childPath, childFile.getName(), "");
            child.setContent(content);
            return child;
        } catch (Exception e) {
            return null;
        }
    }
}
```


### 4. 集成到 LLM 对话

```java
import com.agentsflex.wiki.WikiTool;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.message.UserMessage;

// 1. 创建聊天模型
OpenAIChatModel chatModel = OpenAIChatConfig.builder()
    .provider("GiteeAI")
    .endpoint("https://ai.gitee.com")
    .requestPath("/v1/chat/completions")
    .apiKey(System.getenv("GITEE_APIKEY"))
    .model("Qwen3-32B")
    .buildModel();

// 2. 构建 Wiki 工具
Tool wikiTool = WikiTool.builder()
    .addWikis(MyWikis.getWikis())
    .wikiProvider(new MyWikiProvider())
    .build();

// 3. 创建提示词并添加工具
MemoryPrompt prompt = new MemoryPrompt();
prompt.addTool(wikiTool);

// 4. 添加用户消息
UserMessage userMessage = new UserMessage("Agents-Flex 的高级配置有哪些？");
prompt.addMessage(userMessage);

// 5. 执行对话
chatModel.chatStream(prompt, new StreamResponseListener() {
    @Override
    public void onMessage(StreamContext context, AiMessageResponse response) {
        String content = response.getMessage().getContent();
        if (content != null) {
            System.out.print(content);
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
});
```



## API 参考

### Wiki 类完整 API

```java
public class Wiki {
    // 构造方法
    public Wiki()
    public Wiki(String path, String title)
    public Wiki(String path, String title, String summary)
    public Wiki(String path, String title, String summary, Map<String, Object> frontMatter)

    // Getter/Setter
    public String getPath()
    public void setPath(String path)
    public String getTitle()
    public void setTitle(String title)
    public String getSummary()
    public void setSummary(String summary)
    public String getContent()
    public void setContent(String content)
    public List<Wiki> getChildren()
    public void setChildren(List<Wiki> children)
    public Map<String, Object> getFrontMatter()
    public void setFrontMatter(Map<String, Object> frontMatter)

    // 子节点操作（新增）
    public void addChild(Wiki child)
    public void addChildren(List<Wiki> children)

    // 元数据操作
    public void addFrontMatter(String key, Object value)

    // 序列化
    public String toXml()         // 用于工具描述（不含子节点）
    public String toMarkdown()    // 用于内容返回（含子节点列表）
}
```


### WikiProvider 接口

```java
public interface WikiProvider {
    /**
     * 根据路径获取 Wiki
     * @param path Wiki 路径标识
     * @return Wiki 对象或 null
     */
    Wiki getWiki(String path);
}
```


### WikiTool.Builder API

```java
public class WikiTool.Builder {
    // 添加 Wiki
    public Builder addWiki(Wiki wiki)
    public Builder addWikis(List<Wiki> wikis)

    // 设置提供者（必需）
    public Builder wikiProvider(WikiProvider wikiProvider)

    // 自定义描述模板（可选）
    public Builder toolDescriptionTemplate(String template)

    // 构建工具
    public Tool build()
}
```


### 工具方法

```java
// 静态方法：将 Wiki 列表转换为 XML 格式
public static String buildWikisXml(List<Wiki> wikis)
```



## 最佳实践

### 1. Wiki 组织策略

#### 扁平化 vs 层级化

**推荐做法**：

```java
// ✅ 好的设计：清晰的层级结构
Wiki chat = new Wiki("chat.md", "聊天功能", "聊天模型配置和使用");
chat.addChild(new Wiki("chat/openai.md", "OpenAI 配置", "OpenAI 模型接入指南"));
chat.addChild(new Wiki("chat/qwen.md", "通义千问配置", "阿里云 Qwen 模型接入"));

// ❌ 避免：过深的嵌套（超过 3-4 层）
Wiki deep = new Wiki("a/b/c/d/e.md", "...", "...");
```


#### 摘要编写规范

```java
// ✅ 好的摘要：简洁明确
new Wiki("query.md", "查询功能", "MyBatis-Flex 提供灵活的 QueryWrapper 进行条件查询和分页")

// ❌ 差的摘要：过于模糊
new Wiki("query.md", "查询", "-")
```


### 2. 子节点管理

#### 方式 1：在 Wiki 创建时添加

```java
Wiki parent = new Wiki("parent.md", "父节点", "父节点描述");
parent.addChild(new Wiki("child1.md", "子节点 1", "子节点 1 描述"));
parent.addChild(new Wiki("child2.md", "子节点 2", "子节点 2 描述"));
```


#### 方式 2：在 WikiProvider 中动态构建

```java
@Override
public Wiki getWiki(String path) {
    Wiki wiki = loadWikiFromSource(path);

    // 根据业务逻辑动态添加子节点
    if (shouldHaveChildren(path)) {
        List<Wiki> children = findChildren(path);
        wiki.addChildren(children);
    }

    return wiki;
}
```


#### 方式 3：使用配置文件定义层级

```java
// hierarchy.json
{
  "root.md": ["intro.md", "guide.md"],
  "guide.md": ["guide/basic.md", "guide/advanced.md"]
}

// 在 Provider 中加载配置
private Map<String, List<String>> loadHierarchy() {
    // 从 JSON/YAML 文件加载
}
```


### 3. 性能优化

#### 缓存策略

```java
public class CachedWikiProvider implements WikiProvider {

    private final Map<String, Wiki> cache = new ConcurrentHashMap<>();
    private final WikiProvider delegate;

    @Override
    public Wiki getWiki(String path) {
        return cache.computeIfAbsent(path, delegate::getWiki);
    }
}
```


#### 懒加载子节点

```java
// 只在真正需要时才构建子节点
@Override
public Wiki getWiki(String path) {
    Wiki wiki = metadataStore.getWiki(path); // 快速获取元数据

    if (needsFullContent) {
        wiki.setContent(loadContent(path));  // 按需加载完整内容

        // 按需构建子节点
        if (shouldLoadChildren(path)) {
            List<Wiki> children = loadChildren(path);
            wiki.addChildren(children);
        }
    }

    return wiki;
}
```


### 4. 错误处理

```java
@Override
public Wiki getWiki(String path) {
    try {
        File wikiFile = new File(WIKI_ROOT, path);

        // 安全检查：防止路径遍历攻击
        if (!wikiFile.getCanonicalPath().startsWith(WIKI_ROOT)) {
            throw new SecurityException("Invalid wiki path: " + path);
        }

        if (!wikiFile.exists()) {
            return null; // 或抛出异常
        }

        String content = IOUtil.readUtf8(Files.newInputStream(wikiFile.toPath()));
        Wiki wiki = new Wiki(path, wikiFile.getName(), "");
        wiki.setContent(content);

        // 安全地添加子节点
        try {
            List<Wiki> children = loadChildrenSafely(path);
            if (children != null) {
                wiki.addChildren(children);
            }
        } catch (Exception e) {
            log.warn("Failed to load children for {}: {}", path, e.getMessage());
            // 继续返回父节点，只是没有子节点
        }

        return wiki;

    } catch (IOException e) {
        log.error("Failed to load wiki: {}", path, e);
        throw new RuntimeException("Wiki loading failed", e);
    }
}
```


### 5. 元数据使用

利用 `frontMatter` 存储额外信息：

```java
Wiki wiki = new Wiki("api-auth.md", "API 认证", "OAuth2 和 JWT 认证方式");

// 添加版本信息
wiki.addFrontMatter("version", "2.0");

// 添加标签
wiki.addFrontMatter("tags", Arrays.asList("security", "authentication"));

// 添加作者
wiki.addFrontMatter("author", "开发团队");

// 添加更新时间
wiki.addFrontMatter("updated_at", "2025-01-15");

// 标记是否有子节点（即使实际未加载）
wiki.addFrontMatter("has_children", true);
```


## 扩展开发

### 示例 1：从数据库加载带层级关系的 Wiki

```java
import com.agentsflex.wiki.Wiki;
import com.agentsflex.wiki.WikiProvider;
import java.sql.*;
import java.util.*;

public class DatabaseWikiProvider implements WikiProvider {

    private final DataSource dataSource;

    public DatabaseWikiProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Wiki getWiki(String path) {
        String sql = "SELECT title, summary, content, metadata FROM wikis WHERE path = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, path);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                String title = rs.getString("title");
                String summary = rs.getString("summary");
                String content = rs.getString("content");
                String metadataJson = rs.getString("metadata");

                Wiki wiki = new Wiki(path, title, summary);
                wiki.setContent(content);

                // 解析元数据
                if (metadataJson != null) {
                    Map<String, Object> frontMatter = parseMetadata(metadataJson);
                    wiki.setFrontMatter(frontMatter);
                }

                // 加载子节点
                List<Wiki> children = loadChildrenFromDb(conn, path);
                if (!children.isEmpty()) {
                    wiki.addChildren(children);
                }

                return wiki;
            }

        } catch (SQLException e) {
            throw new RuntimeException("Database error", e);
        }
    }

    private List<Wiki> loadChildrenFromDb(Connection conn, String parentPath) throws SQLException {
        List<Wiki> children = new ArrayList<>();

        String sql = "SELECT path, title, summary FROM wikis WHERE parent_path = ? ORDER BY sort_order";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, parentPath);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Wiki child = new Wiki(
                        rs.getString("path"),
                        rs.getString("title"),
                        rs.getString("summary")
                    );
                    children.add(child);
                }
            }
        }

        return children;
    }

    private Map<String, Object> parseMetadata(String json) {
        // 使用 JSON 库解析
        // ...
    }
}
```


### 示例 2：从 Git 仓库加载 Wiki

```java
import com.agentsflex.wiki.Wiki;
import com.agentsflex.wiki.WikiProvider;
import org.eclipse.jgit.api.Git;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class GitWikiProvider implements WikiProvider {

    private final String repoPath;
    private final String branch;
    private final Map<String, List<String>> hierarchy;

    public GitWikiProvider(String repoPath, String branch) {
        this.repoPath = repoPath;
        this.branch = branch;
        this.hierarchy = loadHierarchy();
    }

    @Override
    public Wiki getWiki(String path) {
        try (Git git = Git.open(new File(repoPath))) {
            // 切换到指定分支
            git.checkout().setName(branch).call();

            // 读取文件
            File wikiFile = new File(git.getRepository().getWorkTree(), path);

            if (!wikiFile.exists()) {
                return null;
            }

            String content = Files.readString(wikiFile.toPath());

            // 从文件名提取标题
            String title = extractTitleFromFilename(path);
            String summary = extractSummaryFromCommit(git, path);

            Wiki wiki = new Wiki(path, title, summary);
            wiki.setContent(content);

            // 添加子节点
            List<String> childPaths = hierarchy.get(path);
            if (childPaths != null) {
                for (String childPath : childPaths) {
                    Wiki child = loadChildWiki(git, childPath);
                    if (child != null) {
                        wiki.addChild(child);
                    }
                }
            }

            return wiki;

        } catch (Exception e) {
            throw new RuntimeException("Git operation failed", e);
        }
    }

    private Wiki loadChildWiki(Git git, String childPath) {
        // 类似主逻辑，加载子节点
        // ...
    }

    private Map<String, List<String>> loadHierarchy() {
        // 从 .hierarchy.yml 或其他配置文件加载
        // ...
    }
}
```


### 示例 3：多级 Wiki 导航（递归加载）

```java
public class RecursiveWikiProvider implements WikiProvider {

    private final WikiProvider baseProvider;
    private final int maxDepth;

    public RecursiveWikiProvider(WikiProvider baseProvider, int maxDepth) {
        this.baseProvider = baseProvider;
        this.maxDepth = maxDepth;
    }

    @Override
    public Wiki getWiki(String path) {
        return getWikiWithChildren(path, 0);
    }

    private Wiki getWikiWithChildren(String path, int currentDepth) {
        if (currentDepth >= maxDepth) {
            return baseProvider.getWiki(path);
        }

        Wiki wiki = baseProvider.getWiki(path);
        if (wiki == null || wiki.getChildren() == null) {
            return wiki;
        }

        // 递归加载子节点的子节点
        for (Wiki child : wiki.getChildren()) {
            Wiki fullChild = getWikiWithChildren(child.getPath(), currentDepth + 1);
            if (fullChild != null) {
                // 更新子节点为完整版本
                updateChild(wiki, fullChild);
            }
        }

        return wiki;
    }

    private void updateChild(Wiki parent, Wiki fullChild) {
        // 替换占位子节点为完整子节点
        List<Wiki> children = parent.getChildren();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).getPath().equals(fullChild.getPath())) {
                children.set(i, fullChild);
                break;
            }
        }
    }
}
```


### 示例 4：自定义工具描述

```java
String customTemplate =
    "你是一个专业的技术文档助手。\n" +
    "\n" +
    "你可以访问以下技术文档：\n" +
    "%s\n" +
    "\n" +
    "使用说明：\n" +
    "1. 根据用户问题，选择合适的文档路径\n" +
    "2. 如果不确定，可以先查看概览文档\n" +
    "3. 注意查看返回内容中的 'Children Wikis' 部分，了解相关子主题\n" +
    "4. 提供准确、简洁的答案，并引用文档来源\n" +
    "\n" +
    "注意事项：\n" +
    "- 只回答文档范围内的问题\n" +
    "- 如果文档中没有相关信息，请明确告知用户\n" +
    "- 保持回答的专业性和准确性\n" +
    "- 善用子节点进行深度探索";

Tool wikiTool = WikiTool.builder()
    .addWikis(wikis)
    .wikiProvider(provider)
    .toolDescriptionTemplate(customTemplate)
    .build();
```



## 常见问题

### Q1: Wiki 模块与 RAG 有什么区别？

**A**:
- **RAG**：基于向量相似度搜索，适合非结构化文档的语义检索
- **Wiki**：基于树状结构的精确导航，适合结构化知识的渐进式探索

两者可以结合使用：用 Wiki 管理知识索引，用 RAG 在具体内容中搜索。

### Q2: 如何处理大量 Wiki 节点？

**A**:
1. 使用分页或分类组织顶层 Wiki
2. 实现缓存机制避免重复加载
3. 考虑使用搜索引擎辅助定位
4. 限制单次返回的 Wiki 数量

```java
// 示例：分类组织
List<Wiki> categoryWikis = Arrays.asList(
    new Wiki("cat/api.md", "API 文档", "查看所有 API 接口"),
    new Wiki("cat/guide.md", "使用指南", "入门和进阶教程"),
    new Wiki("cat/faq.md", "常见问题", "常见问题解答")
);
```


### Q3: 如何实现 Wiki 的版本控制？

**A**: 利用 `frontMatter` 存储版本信息：

```java
Wiki wiki = new Wiki(path, title, summary);
wiki.addFrontMatter("version", "2.1.0");
wiki.addFrontMatter("changelog", "修复了...");
wiki.addFrontMatter("deprecated", false);
```


### Q4: Wiki 内容可以是 HTML 或其他格式吗？

**A**: 当前主要支持 Markdown 格式（通过 `toMarkdown()` 方法）。如果使用其他格式，需要：

1. 在 `content` 字段存储原始内容
2. 自定义序列化方法
3. 确保 LLM 能够理解该格式

### Q5: 如何保证 Wiki 路径的安全性？

**A**: 实现路径验证：

```java
@Override
public Wiki getWiki(String path) {
    // 防止路径遍历攻击
    if (path.contains("..") || path.startsWith("/")) {
        throw new IllegalArgumentException("Invalid path");
    }

    // 规范化路径
    String normalizedPath = Paths.get(path).normalize().toString();

    // 继续处理...
}
```


### Q6: Wiki 模块支持并发访问吗？

**A**: 是的，但需要注意：
- `Wiki` 对象本身不是线程安全的
- `WikiProvider` 的实现需要保证线程安全
- 建议使用不可变对象或同步机制

```java
public class ThreadSafeWikiProvider implements WikiProvider {
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public Wiki getWiki(String path) {
        lock.readLock().lock();
        try {
            return loadWiki(path);
        } finally {
            lock.readLock().unlock();
        }
    }
}
```


### Q7: 子节点会无限递归加载吗？

**A**: 不会。子节点只在 `WikiProvider.getWiki()` 中被显式创建时才存在。如果需要控制递归深度，可以实现：

```java
public class DepthLimitedWikiProvider implements WikiProvider {
    private final int maxDepth;

    @Override
    public Wiki getWiki(String path) {
        int depth = calculateDepth(path);
        if (depth > maxDepth) {
            return null; // 或返回不带子节点的 Wiki
        }
        // 正常加载
    }
}
```


### Q8: `toXml()` 和 `toMarkdown()` 中的子节点处理有何不同？

**A**:
- **`toXml()`**：不包含 `children` 信息，仅用于在工具描述中展示顶层可用的 Wiki 列表
- **`toMarkdown()`**：如果 `children` 不为空，会在内容末尾追加 "## Children Wikis:" 部分，以 XML 格式列出所有子节点

这种设计使得：
- 工具描述保持简洁，只显示当前层级的 Wiki
- 内容返回时自动携带子节点信息，方便 LLM 继续探索


## 附录

### A. 完整示例项目

参考 `demos/wiki-demo` 模块：

```
demos/wiki-demo/
├── src/main/java/com/agentsflex/wiki/
│   ├── Main.java                    # 主程序入口
│   ├── Wikis.java                   # Wiki 数据定义
│   └── MybatisflexWikiProvider.java # Wiki 提供者实现
└── src/main/resources/mybatis-flex/ # Wiki 文件目录
    ├── active-record.md
    ├── query.md
    └── ...
```


### B. 相关模块

- **agents-flex-core**: 核心 SPI 和工具类
- **agents-flex-chat**: 聊天模型集成
- **agents-flex-skill**: 技能管理系统（类似概念）
- **agents-flex-websearch**: 网络搜索工具


</div>
