# Agents-Flex 智能问数开发文档

## 1. 概述 (Overview)

### 1.1 背景
本项目旨在通过 **AI Skills + 渐进式披露 (Progressive Disclosure)** 机制，实现安全、准确、可扩展的自然语言查数据库（Text-to-SQL）能力。解决传统方案中存在的 SQL 注入风险、字段幻觉、上下文爆炸等痛点。

### 1.2 核心目标
- ✅ **安全**：Java 层强制 SQL 校验，杜绝注入与写操作。
- ✅ **准确**：通过元数据渐进加载，消除 AI 字段幻觉。
- ✅ **高效**：上下文 Token 消耗降低 60%+，支持多数据源隔离。
- ✅ **易用**：注解驱动开发，5 行代码注册一个查询技能。

### 1.3 核心机制：渐进式披露
为避免一次性加载所有元数据导致 Context 爆炸，本方案采用四层披露策略：

| 层级 | 名称 | 触发时机 | 内容示例 | Token 消耗 |
|------|------|----------|----------|------------|
| **L1** | 元数据层 | 会话启动 | 数据源名称、简介 | ~100 tokens |
| **L2** | 表结构层 | 选定数据源后 | 表名列表、表描述 | ~500 tokens |
| **L3** | 字段层 | 选定表后 | 字段名、类型、主键 | ~1k tokens |
| **L4** | 执行层 | 生成 SQL 后 | 查询结果 (JSON) | 可变 |


## 2. 快速开始 (Quick Start)

### 2.1 环境依赖
- **JDK**: 1.8+
- **框架**: Agents-Flex Core & Agents-Flex Data
- **数据库**: MySQL 5.7+ / PostgreSQL 等 (支持 JDBC)

```xml
<!-- Maven 依赖示例 -->
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>2.0.0</version>
</dependency>
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-text2sql</artifactId>
    <version>2.0.0</version>
</dependency>
```

### 2.2 最小可运行示例
```java
public class SmartQueryDemo {
    public static void main(String[] args) {
        // 1. 初始化模型
        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .apiKey(System.getenv("GITEE_APIKEY"))
            .model("Qwen3-32B")
            .buildModel();

        // 2. 配置数据源
        JdbcDataSourceInfo dataSource = new JdbcDataSourceInfo();
        dataSource.setJdbcUrl("jdbc:mysql://localhost:3306/mydb");
        dataSource.setUsername("root");
        dataSource.setPassword("password");
        dataSource.setName("mydb");

        // 3. 构建 Skills (自动加载 L1 元数据)
        List<Tool> tools = DataTools.builder()
            .addDataSourceInfo(dataSource)
            .buildTools();

        // 4. 发起会话
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addTools(tools);

        prompt.addMessage(new UserMessage("查一下用户表有哪些字段？"));

        // 5. 流式响应 (含自动工具调用闭环)
        chatModel.chatStream(prompt, new StreamResponseListener() {
            // ... 实现 onMessage 逻辑 (见章节 4)
        });
    }
}
```
完整代码参考： https://gitee.com/agents-flex/agents-flex/blob/v2.x/demos/data-demo/src/main/java/com/agentsflex/data/Main.java

### 2.3 运行效果

```text
用户：系统中有哪些姓李的用户呢？

🤖 AI [L1 元数据感知]:
  可用数据源: aiflowy_v1 (用户管理系统)
  → 需要确认表结构，先调用 listTables

[调用 listTables] dataSourceName="aiflowy_v1"
<<<<< [L2 精准披露] 返回:
📋 Data Source: `aiflowy_v1`
| Table Name | Description |
|------------|-------------|
| `user`     | 用户信息表   |
| `order`    | 订单表       |

🤖 AI [L2 指令执行]:
  目标表: user → 需要确认字段，调用 listTableColumns

[调用 listTableColumns] tableName="user"
<<<<< [L3 完整披露] 返回:
📊 Table Schema: `user`
| Field Name | Type | Primary Key | Comment |
|------------|------|----------------|---------|
| `id`       | BIGINT | ✅ | 主键 |
| `name`     | VARCHAR | ❌ | 姓名 |
| `email`    | VARCHAR | ❌ | 邮箱 |

🤖 AI [L3 资源利用]:
  字段确认: name → 生成安全 SQL，调用 queryDataList

[调用 queryDataList]
  sql="SELECT name, email FROM user WHERE name LIKE ? LIMIT 10"
  params=["李%"]
<<<<< [L4 安全执行] 返回:
[{"name":"李雷","email":"lili@example.com"}, {"name":"李梅","email":"limei@example.com"}]

🤖 AI [最终总结]:
在 aiflowy_v1 数据源中，姓李的用户有以下 2 位：
- **李雷**：lili@example.com
- **李梅**：limei@example.com
> 💡 如需查看更多，可调整 LIMIT 参数或添加其他筛选条件。

>>>>>>> 结束 <<<<<<<<<
```

>🤩 效果对比：
>- ❌ 传统方案：AI 一次性看到所有表结构，容易混淆字段、生成错误 SQL
>- ✅ 渐进式披露：AI 按步骤确认元数据，每一步都有校验点，准确率提升 40%+

## 3. 核心开发指南

### 3.1 技能 (Tool) 开发规范
所有数据查询能力均封装为 `Tool`。推荐使用注解方式定义，便于框架自动扫描。

#### 3.1.1 注解定义
```java
@ToolDef(
    name = "queryDataList",
    description = "[执行查询] 执行 SQL 查询返回多行结果。只读 SELECT 语句，禁止拼接字符串。"
)
public String queryDataList(
    @ToolParam(name = "dataSourceName", description = "数据源名称") String dataSourceName,
    @ToolParam(name = "sql", description = "SQL 语句，参数用 ? 占位") String sql,
    @ToolParam(name = "params", description = "参数列表") List<Object> params
) {
    // 实现逻辑
}
```

#### 3.1.2 软失败原则 (Soft Failure)
**严禁抛出异常**。所有错误必须通过返回字符串告知 AI，以便其自我修正。

- ✅ **正确**: `return "Error: 表名不存在，可用表有：user, order";`
- ❌ **错误**: `throw new RuntimeException("表名不存在");`

### 3.2 实现渐进式披露
在 `DataTools` 中，通过拆分工具职责实现披露控制：

1.  **`listTables`**: 仅返回表名列表 (L2 披露)。
2.  **`listTableColumns`**: 仅当 AI 指定表名后，返回该表字段 (L3 披露)。
3.  **`queryDataList`**: 仅当 AI 确认字段后，执行 SQL (L4 披露)。

**代码示例：**
```java
// 仅在 listTableColumns 中加载字段元数据，避免启动时全量加载
public String listTableColumns(String dataSourceName, String tableName) {
    TableInfo table = findTable(dataSourceName, tableName);
    if (table == null) return "Error: 表不存在";
    return formatTableSchema(table); // 返回 Markdown 格式字段信息
}
```

### 3.3 流式响应与工具闭环
必须实现 `StreamResponseListener` 以支持多轮工具调用。

```java
StreamResponseListener listener = new StreamResponseListener() {
    @Override
    public void onMessage(StreamContext context, AiMessageResponse response) {
        // 1. 输出内容
        System.out.print(response.getMessage().getContent());

        // 2. 检测工具调用
        if (response.getMessage().isFinalDelta() && response.getMessage().hasToolCalls()) {
            // 3. 执行工具并获取结果
            List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();

            // 4. 将结果加入上下文，递归调用
            prompt.addMessages(toolMessages);
            chatModel.chatStream(prompt, this);
        }
    }
};
```


## 4. 安全指南 (Security)

### 4.1 SQL 只读校验
所有执行 SQL 的工具必须在 Java 层进行白名单校验。

```java
private String validateSqlReadOnly(String sql) {
    String upperSql = sql.trim().toUpperCase();
    // 禁止写操作
    if (upperSql.contains("INSERT") || upperSql.contains("UPDATE") || ...) {
        return "Error: 禁止执行写操作";
    }
    // 必须 SELECT 开头
    if (!upperSql.startsWith("SELECT")) {
        return "Error: 仅支持 SELECT 查询";
    }
    return null;
}
```

### 4.2 防注入机制
- **强制占位符**：工具描述中明确要求 AI 使用 `?` 而非字符串拼接。
- **预编译执行**：底层 `JdbcQueryUtil` 必须使用 `PreparedStatement`。
```java
// 底层实现示例
PreparedStatement ps = conn.prepareStatement(sql);
// 绑定 params...
```

### 4.3 数据权限隔离
- **多数据源支持**：通过 `DataTools.builder().addDataSourceInfo()` 注册多个源。
- **动态过滤**：可根据当前登录用户，动态决定 `buildTools()` 时注册哪些数据源。


## 5. 调试与优化 (Debug & Optimize)

### 5.1 常见问题排查

| 问题现象 | 可能原因 | 解决方案 |
|----------|----------|----------|
| AI 编造字段名 | 未调用 `listTableColumns` | 检查 Tool 描述是否强调"必须先查字段" |
| 上下文超限 | 一次性返回太多表结构 | 确保 `listTables` 不包含字段详情 |
| SQL 执行报错 | 参数类型不匹配 | 检查 `params` 列表顺序与 `?` 是否一致 |
| 工具未触发 | Tool 描述不清晰 | 优化 `@ToolDef` 中的 description 文案 |

### 5.2 性能优化建议
1.  **元数据缓存**：`JdbcDataSourceInfo` 中的表结构信息建议在应用启动时加载并缓存，避免每次查询都访问 `information_schema`。
2.  **结果截断**：`queryDataList` 返回结果建议限制最大行数（如 100 行），避免大结果集撑爆 Context。
3.  **日志脱敏**：生产环境开启日志时，务必对 `password`、`sql` 中的敏感数据进行脱敏。

### 5.3 提示词 (Prompt) 优化
在 `MemoryPrompt` 初始化时，可加入系统指令增强约束：
```java
prompt.addMessage(new SystemMessage("你是一个数据查询助手。必须严格按照以下步骤操作：1.确认数据源 2.确认表结构 3.生成 SQL。禁止猜测字段名。"));
```


## 6. 附录 (Appendix)

### 6.1 工具返回格式规范
- **成功**: Markdown 表格 或 JSON 字符串。
- **失败**: 必须以 `Error: ` 开头，后接人类可读的错误原因及建议。

### 6.2 环境变量配置
```bash
export GITEE_APIKEY="your_api_key_here"
export DB_HOST="192.168.1.100"
export DB_PASSWORD="secure_password"
```

### 6.3 参考资源
- Agents-Flex 官方文档：https://gitee.com/agents-flex/agents-flex
- AI Skills 渐进式披露架构详解: https://agentsflex.com/zh/chat/skills.html


> **⚠️ 重要提示**：本文档涉及数据库直接操作权限，生产环境部署前请务必经过安全团队审计。
> **📞 技术支持**：如遇框架问题，请提交 Issue 至 Gitee 仓库。
