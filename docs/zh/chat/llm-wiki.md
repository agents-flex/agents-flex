<div v-pre>


# Agents-Flex LLM Wiki 知识树开发设计文档

## 1. 概述 (Overview)

本文档旨在描述 Agents-Flex 中 `WikiTool` 及其相关组件的设计与实现。该模块的核心目标是让 LLM（大语言模型）能够以**渐进式披露（Progressive Disclosure）**和**递归导航**的方式访问层级化的知识库（Wiki）。

通过将该能力封装为标准 `Tool`，Agent 可以根据当前上下文，动态决定是直接使用当前节点的知识回答问题，还是深入子节点获取更详细的信息从而避免一次性加载过多无关内容 token 消耗过大或信息过载。

### 1.1 核心特性
- **层级化知识导航**：将知识库建模为树状结构，支持通过 `path` 进行节点定位。
- **渐进式披露策略**：LLM 首先看到当前节点的摘要和可用子节点列表，按需深入。
- **标准化 Tool 集成**符合 Agents-Flex 的 `Tool` 接口规范，无缝集成到 Chat 流程中。
- **灵活的数据源适配**：通过 `WikiProvider` 接口解耦数据存储与工具逻辑。



## 2. 核心概念与架构 (Core Concepts & Architecture)

### 2.1 数据模型：Wiki
`com.agentsflex.wiki.Wiki` 代表知识库中的一个最小知识单元（节点）。

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `path` | String | 唯一标识符，通常体现层级关系如 `/tech/java/spring` |
| `title` | String | 节点标题 |
| `summary` | String | 节点摘要，用于 LLM 快速判断是否需深入 |
| `content` | String | 节点详细内容，仅在深入该节点时返回 |
| `frontMatter` | Map<String, Object> | 元数据，如作者、版本、标签等，可扩展 |

**序列化支持：**
- `toXml()`: 用于在 Tool Description 中紧凑地展示可用子节点列表。
- `toMarkdown()`: 用于作为最终内容返回给 LLM，包含 Front-matter 头信息。

### 2.2 数据源接口：WikiProvider
`com.agentsflex.wiki.WikiProvider` 是知识获取的标准接口。

```java
public interface WikiProvider {
    /**
     * 根据路径获取 Wiki 节点
     * @param path 知识节点路径
     * @return Wiki 对象，若不存在则返回 null
     */
    Wiki getWiki(String path);
}
```

**设计意图：**
开发者可以实现该接口对接文件系统、数据库、向量数据库元数据表或远程 API，实现知识源的灵活切换。

### 2.3 工具构建器：WikiTool.Builder
`com.agentsflex.wiki.WikiTool` 采用 Builder 模式构建标准的 `Tool` 对象。

- **Tool Name**: `get_wiki_content`
- **Input Parameter**: `path` (String, Required) - 目标知识节点的路径。
- **Execution Logic**:
    1. 接收 `path`。
    2. 调用 `WikiProvider.getWiki(path)`。
    3. 若找到，返回 `wiki.toMarkdown()`。
    4. 若未找到，返回错误提示字符串。



## 3. 渐进式披露策略详解 (Progressive Disclosure Strategy)

这是本模块设计的灵魂所在。传统的 RAG 往往一次性检索大量片段，而 WikiTool 模拟人类查阅文档的过程。

### 3.1 Prompt 指令模板
`WikiTool` 在构建 Tool Description 时，注入了详细的导航指令 (`TOOL_DESCRIPTION_TEMPLATE`)。关键指令包括：

1. **认知模型**：告知 LLM Wiki 是一个树状结构，子节点不会自动嵌入，必须通过再次调用工具获取。
2. **导航步骤**：
    - 检查当前节点的 `title`, `summary`, `frontMatter`。
    - 查看 `<available_wikis>` 列表。
    - 决策：直接回答 OR 递归进入子节点。
3. **约束规则**：
    - 不要假设当前节点包含所有信息。
    - 避免过度导航（Over-navigation），只在必要时深入。

### 3.2 工作流程示例

假设知识库结构如下：
- `/` (根节点: 公司简介)
    - `/products` (子节点: 产品列表)
        - `/products/ai-flowy` (孙节点: AIFlowy 详情)

**Step 1: Agent 初始调用**
Agent 可能首先调用 `get_wiki_content(path="/")`。
**返回内容：**
```markdown

path: /
title: 公司简介
summary: 这里是公司的总体介绍...

(根节点的详细内容...)
```
*注意：此时 Tool Description 中可能已经包含了根节点下的 `available_wikis` 预览（取决于构建时传入的初始 Wikis 列表）。*

**Step 2: Agent 决策**
用户问：“AIFlowy 是什么？”
Agent 分析根节点内容，发现不够详细，但看到有子节点 `/products`。
Agent 调用 `get_wiki_content(path="/products")`。

**Step 3: 递归深入**
Agent 获取 `/products` 内容，发现其中列出了多个产品，包括 `ai-flowy` 的子路径或引用。
Agent 继续调用 `get_wiki_content(path="/products/ai-flowy")`。

**Step 4: 最终回答**
Agent 获取到 AIFlowy 的详细 Markdown 内容，结合之前的上下文，生成最终回答。



## 4. 开发指南 (Developer Guide)

### 4.1 引入依赖
确保项目中已包含 Agents-Flex Core 依赖。

### 4.2 实现 WikiProvider
根据你的业务场景实现数据源。

**示例：基于内存 Map 的简单实现**

```java
import com.agentsflex.wiki.Wiki;
import com.agentsflex.wiki.WikiProvider;
import java.util.HashMap;
import java.util.Map;

public class InMemoryWikiProvider implements WikiProvider {

    private final Map<String, Wiki> wikiStore = new HashMap<>();

    public InMemoryWikiProvider() {
        // 初始化一些测试数据
        Wiki root = new Wiki("/", "Home", "Welcome to Agents-Flex Wiki");
        root.setContent("# Welcome\nThis is the root page.");

        Wiki javaDoc = new Wiki("/dev/java", "Java Dev Guide", "Guide for Java developers");
        javaDoc.setContent("# Java Guide\nUse JDK 17+...");

        wikiStore.put(root.getPath(), root);
        wikiStore.put(javaDoc.getPath(), javaDoc);
    }

    @Override
    public Wiki getWiki(String path) {
        return wikiStore.get(path);
    }
}
```

**示例：基于文件系统的实现（伪代码）**

```java
public class FileWikiProvider implements WikiProvider {
    private final String basePath;

    public FileWikiProvider(String basePath) {
        this.basePath = basePath;
    }

    @Override
    public Wiki getWiki(String path) {
        // 1. 将 path 转换为文件路径, e.g., "/dev/java" -> basePath + "/dev/java.md"
        // 2. 读取文件内容
        // 3. 解析 Front-matter (YAML/JSON header)
        // 4. 构建并返回 Wiki 对象
        // 注意：需要处理文件不存在的情况
        return null;
    }
}
```

### 4.3 构建并注册 Tool

在创建 Agent 或 Chain 时，构建 WikiTool 并注册。

```java
import com.agentsflex.wiki.WikiTool;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.chain.Chain;

// 1. 准备 Provider
WikiProvider provider = new InMemoryWikiProvider();

// 2. (可选) 预加载顶层 Wikis 用于初始 Prompt 中的 available_wikis 展示
// 如果希望 LLM 一开始就知道有哪些顶级目录，可以添加它们
// List<Wiki> topWikis = Arrays.asList(provider.getWiki("/"));

// 3. 构建 Tool
Tool wikiTool = WikiTool.builder()
    .wikiProvider(provider)
    // .addWikis(topWikis) // 如果需要初始可见性
    .build();

// 4. 注册到 Agent/Chain
// agent.addTool(wikiTool);
// 或者在 Chain 定义中使用
```

### 4.4 自定义 Prompt 模板
如果需要调整 LLM 的导航行为，可以自定义 `toolDescriptionTemplate`。

```java
String customTemplate = "You are a wiki navigator. ...\n<available_wikis>\n%s\n</available_wikis>";

Tool wikiTool = WikiTool.builder()
    .wikiProvider(provider)
    .toolDescriptionTemplate(customTemplate)
    .build();
```



## 5. 最佳实践与注意事项 (Best Practices)

1. **Path 设计规范**：
    - 建议使用类似 URL 或文件路径的层级结构（如 `/category/sub-category/item`）。
    - 保持 Path 的唯一性和语义清晰性，有助于 LLM 推断路径。

2. **Summary 的重要性**：
    - 在 `Wiki` 对象中，`summary` 字段至关重要。它是 LLM 决定是否“点击”进入该节点的依据。
    - Summary 应简明扼要地概括子节点的内容范围，而非详细内容。

3. **避免循环引用**：
    - 虽然 Tool 本身无状态，但在构建知识库时应确保路径树无环，防止 Agent 陷入无限递归调用。

4. **Token 优化**：
    - `toXml()` 方法生成的 XML 结构紧凑，适合放在 System Prompt 或 Tool Description 中。
    - 仅在 LLM 明确请求具体路径时，才返回完整的 `toMarkdown()` 内容，节省 Token。

5. **错误处理**：
    - `WikiProvider.getWiki` 返回 `null` 时，Tool 会返回 "Wiki not found: {path}"。
    - 建议在 Prompt 中引导 LLM 在收到此错误时尝试修正路径或回退到上级目录。



## 6. 扩展方向 (Future Extensions)

- **向量检索集成**：在 `WikiProvider` 内部集成向量搜索，当 `path` 模糊时，自动推荐最匹配的 Path 列表。
- **权限控制**：在 `WikiProvider` 中加入用户上下文，实现基于角色的知识访问控制（RBAC）。
- **动态图谱**：支持非树状的图结构知识导航，允许节点间存在多向关联。



</div>
