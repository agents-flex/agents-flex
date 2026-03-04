# 智能问数（text2sql）开发文档


## 1. 概述 (Overview)

### 1.1 背景
本项目旨在通过 **AI Skills + 渐进式披露 (Progressive Disclosure)** 机制，实现安全、准确、可扩展的自然语言查数据库（Text-to-SQL）能力。解决传统方案中存在的 SQL 注入风险、字段幻觉、上下文爆炸等痛点。

### 1.2 核心目标
- ✅ **安全**：Java 层强制 SQL 校验 + 自定义验证器链，杜绝注入与越权访问。
- ✅ **准确**：通过元数据渐进加载 + 字段级校验，消除 AI 字段幻觉。
- ✅ **灵活**：支持自定义 SQL 重写器，轻松实现租户隔离、软删除、LIMIT 强制等业务规则。
- ✅ **高效**：上下文 Token 消耗降低 60%+，支持多数据源隔离。
- ✅ **易用**：注解驱动 + 函数式接口，5 行代码注册一个查询技能，Lambda 表达式定义扩展逻辑。

### 1.3 核心机制：渐进式披露
为避免一次性加载所有元数据导致 Context 爆炸，本方案采用四层披露策略：

| 层级 | 名称 | 触发时机 | 内容示例 | Token 消耗 |
|------|------|----------|----------|------------|
| **L1** | 元数据层 | 会话启动 | 数据源名称、简介 | ~100 tokens |
| **L2** | 表结构层 | 选定数据源后 | 表名列表、表描述 | ~500 tokens |
| **L3** | 字段层 | 选定表后 | 字段名、类型、主键 | ~1k tokens |
| **L4** | 执行层 | 生成 SQL 后 | 查询结果 (JSON) | 可变 |

### 1.4 扩展机制：验证器 + 重写器


为满足不同业务场景的安全与合规需求，Text2SqlTools 提供两套扩展接口：

```
┌─────────────────────────────────────┐
│  SQL 执行流程                        │
├─────────────────────────────────────┤
│  1. 基础安全校验 (只读/关键词拦截)    │
│  2. ▶ SqlValidator 链 (业务规则校验)  │
│  3. ▶ SqlRewriter 链 (SQL 增强/重写)  │
│  4. 执行查询 (PreparedStatement)     │
└─────────────────────────────────────┘
```

| 接口 | 职责 | 返回值 | 典型场景 |
|------|------|--------|----------|
| `SqlValidator` | 校验 SQL 合法性 | `String`：`null`=通过，`非null`=错误信息 | 敏感字段拦截、JOIN 数量限制、权限校验 |
| `SqlRewriter` | 重写/增强 SQL | `SqlContext`：重写后的 SQL+参数 | 租户隔离、软删除过滤、LIMIT 强制、方言适配 |

---

## 2. 快速开始 (Quick Start)

### 2.1 环境依赖
- **JDK**: 1.8+
- **框架**: Agents-Flex Core & Agents-Flex Text2SQL
- **数据库**: MySQL 5.7+ / PostgreSQL / Oracle 等 (支持 JDBC)

```xml
<!-- Maven 依赖 -->
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>2.0.2</version>
</dependency>
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-text2sql</artifactId>
    <version>2.0.2</version>
</dependency>
```

### 2.2 最小可运行示例（基础版）
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

        // 3. 构建 Tools (自动加载 L1 元数据)
        List<Tool> tools = Text2SqlTools.builder()
            .addDataSourceInfo(dataSource)
            .buildTools();

        // 4. 发起会话
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addTools(tools);
        prompt.addMessage(new UserMessage("查一下用户表有哪些字段？"));

        // 5. 流式响应 (含自动工具调用闭环)
        chatModel.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                System.out.print(response.getMessage().getContent());
                if (response.getMessage().isFinalDelta() && response.getMessage().hasToolCalls()) {
                    List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();
                    prompt.addMessages(toolMessages);
                    chatModel.chatStream(prompt, this);
                }
            }
        });
    }
}
```

### 2.3 进阶示例：启用自定义验证器 + 重写器
```java
// 构建带扩展能力的 Text2SqlTools
List<Tool> tools = Text2SqlTools.builder()
    .addDataSourceInfo(dataSource)

    // 🔐 安全验证：禁止访问敏感字段
    .addSqlValidator(new SensitiveColumnValidator("password", "secret_key", "credit_card"))

    // ⚡ 性能验证：限制 JOIN 数量，防止复杂查询拖垮数据库
    .addSqlValidator(new JoinCountValidator(3))

    // 🏢 业务重写：自动添加租户隔离条件 (WHERE org_id = ?)
    .addSqlRewriter(new TenantSqlRewriter("org_id"))

    // 🗑️ 业务重写：自动过滤已删除数据 (WHERE deleted = 0)
    .addSqlRewriter(new SoftDeleteRewriter("deleted", 0))

    // 🚀 性能重写：强制添加 LIMIT，防止全表扫描
    .addSqlRewriter(new LimitEnforcerRewriter(1000))

    // 💡 Lambda 快速定义自定义规则
    .addSqlValidator(ctx ->
        ctx.getOriginalSql().toLowerCase().contains("drop")
            ? "DROP operation detected, prohibited" : null)

    .buildTools();
```

### 2.4 运行效果（含扩展能力）

```text
用户：查询所有用户的邮箱

🤖 AI [L1 元数据感知]:
  → 调用 listTables 确认表结构

[调用 listTables] dataSourceName="mydb"
<<<<< [L2 精准披露] 返回:
📋 Data Source: `mydb`
| Table Name | Description |
|------------|-------------|
| `user`     | 用户信息表   |

🤖 AI [L2 指令执行]:
  → 调用 listTableColumns 确认字段

[调用 listTableColumns] tableName="user"
<<<<< [L3 完整披露] 返回:
📊 Table Schema: `user`
| Field Name | Type | Primary Key | Comment |
|------------|------|-------------|---------|
| `id`       | BIGINT | ✅ | 主键 |
| `name`     | VARCHAR | ❌ | 姓名 |
| `email`    | VARCHAR | ❌ | 邮箱 |
| `org_id`   | VARCHAR | ❌ | 租户ID |
| `deleted`  | TINYINT | ❌ | 软删除标记 |

🤖 AI [L3 资源利用]:
  → 生成 SQL 并调用 queryDataList
  sql="SELECT email FROM user"

🔐 [扩展能力生效 - 验证器链]
  ✓ SensitiveColumnValidator: 未访问敏感字段，通过
  ✓ JoinCountValidator: 无 JOIN，通过

✏️ [扩展能力生效 - 重写器链]
  ✓ TenantSqlRewriter: 添加 WHERE org_id = ? + 参数 "tenant_001"
  ✓ SoftDeleteRewriter: 追加 AND deleted = 0
  ✓ LimitEnforcerRewriter: 追加 LIMIT 1000
  → 最终执行 SQL:
     SELECT email FROM user
     WHERE org_id = ? AND deleted = 0
     LIMIT 1000

<<<<< [L4 安全执行] 返回:
[{"email":"user1@example.com"}, {"email":"user2@example.com"}]

🤖 AI [最终总结]:
在 mydb 数据源中，当前租户共有 2 位有效用户：
- user1@example.com
- user2@example.com
> 💡 结果已自动过滤已删除数据，并限制最大返回 1000 条
```

> 🤩 **效果对比**：
> - ❌ 无扩展：AI 可能查询全量数据，存在越权/性能风险
> - ✅ 有扩展：租户隔离 + 软删除 + LIMIT 三重保障，安全合规


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
在 `Text2SqlTools` 中，通过拆分工具职责实现披露控制：

1.  **`listTables`**: 仅返回表名列表 (L2 披露)。
2.  **`listTableColumns`**: 仅当 AI 指定表名后，返回该表字段 (L3 披露)。
3.  **`queryDataList` / `querySingleRow` / `querySingleValue`**: 仅当 AI 确认字段后，执行 SQL (L4 披露)。

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


## 4. 扩展开发指南

### 4.1 SqlValidator 开发规范

#### 4.1.1 接口定义
```java
@FunctionalInterface
public interface SqlValidator {
    /**
     * 验证 SQL 合法性
     * @param context 验证上下文，包含原始 SQL、参数、数据源、调用方等信息
     * @return null 表示验证通过；非 null 表示验证失败，返回错误信息
     */
    ValidationResult validate(SqlValidationContext context);

    // 快捷创建：支持 Lambda 表达式
    static SqlValidator of(Function<SqlValidationContext, ValidationResult> func) {
        return func::apply;
    }
}
```

#### 4.1.2 上下文对象
```java
public class SqlValidationContext {
    public String getDataSourceName();  // 数据源名称
    public String getOriginalSql();     // 原始 SQL（重写前）
    public List<Object> getOriginalParams(); // 原始参数
}
```

#### 4.1.3 实现示例

**方式一：Lambda 表达式（简单场景）**
```java
.addSqlValidator(ctx -> {
    // 禁止查询包含 "password" 字段的 SQL
    if (ctx.getOriginalSql().toLowerCase().contains("password")) {
        return ValidationResult.fail("Access to column 'password' is prohibited");
    }
    return ValidationResult.pass(); // 验证通过
})
```

**方式二：实现接口（复杂场景）**
```java
public class BusinessRuleValidator implements SqlValidator {
    @Override
    public String validate(SqlValidationContext context) {
        // 示例：同一查询最多允许 3 个表 JOIN
        String sql = context.getOriginalSql();
        long joinCount = Pattern.compile("\\bJOIN\\b", Pattern.CASE_INSENSITIVE)
            .matcher(sql).results().count();

        return joinCount > 3
            ? ValidationResult.fail(
                "Too many JOINs: " + joinCount,
                "PERF_001",
                "Break query into CTEs or use application-side aggregation")
            : ValidationResult.pass();
    }
}
```

#### 4.1.4 执行顺序建议
验证器按**注册顺序**依次执行，建议顺序：
```
1. 安全类验证器（敏感字段、权限校验）← 优先执行，快速失败
2. 业务类验证器（JOIN 限制、表访问控制）
3. 性能类验证器（查询复杂度、子查询限制）
```

### 4.2 SqlRewriter 开发规范

#### 4.2.1 接口定义
```java
@FunctionalInterface
public interface SqlRewriter {
    /**
     * 重写/增强 SQL
     * @param context 重写上下文，包含当前 SQL、参数、数据源、调用方等信息
     * @return 重写后的 SqlContext；如需保持原样，直接返回 context.getCurrentSql()
     */
    SqlContext rewrite(SqlRewriteContext context);

    // 快捷创建：支持 Lambda 表达式
    static SqlRewriter of(Function<SqlRewriteContext, SqlContext> func) {
        return func::apply;
    }
}
```

#### 4.2.2 上下文对象
```java
public class SqlRewriteContext {
    public String getDataSourceName();      // 数据源名称
    public SqlContext getCurrentSql();      // 当前 SQL（可能已被前置重写器修改）
}

public class SqlContext {
    public String getSql();                 // SQL 语句
    public List<Object> getParams();        // 参数列表
    public SqlContext with(String sql, List<Object> params); // 创建新实例
}
```

#### 4.2.3 实现示例

**方式一：Lambda 表达式（简单追加条件）**
```java
.addSqlRewriter(ctx -> {
    String sql = ctx.getCurrentSql().getSql();
    List<Object> params = new ArrayList<>(ctx.getCurrentSql().getParams());

    // 简单追加：WHERE status = 1
    String newSql = sql + " AND status = ?";
    params.add(1);

    return ctx.getCurrentSql().with(newSql, params);
})
```

**方式二：实现接口（智能插入 WHERE）**
```java
public class TenantSqlRewriter implements SqlRewriter {
    private final String tenantColumn;

    public TenantSqlRewriter(String tenantColumn) {
        this.tenantColumn = tenantColumn;
    }

    @Override
    public SqlContext rewrite(SqlRewriteContext context) {
        String sql = context.getCurrentSql().getSql();
        List<Object> params = new ArrayList<>(context.getCurrentSql().getParams());
        String lowerSql = sql.toLowerCase().trim();

        String newSql;
        if (lowerSql.contains(" where ")) {
            // 已有 WHERE，追加 AND
            newSql = sql + " AND " + tenantColumn + " = ?";
        } else {
            // 无 WHERE，智能插入（避开 ORDER BY / LIMIT）
            int insertPos = findWhereInsertPosition(sql, lowerSql);
            newSql = sql.substring(0, insertPos)
                   + " WHERE " + tenantColumn + " = ?"
                   + sql.substring(insertPos);
        }

        // 从调用方或上下文获取 tenantId
        Object tenantId = context.getCaller();
        if (tenantId != null) {
            params.add(tenantId);
        }

        return context.getCurrentSql().with(newSql, params);
    }

    private int findWhereInsertPosition(String sql, String lowerSql) {
        // 简化实现：在 ORDER BY / LIMIT / GROUP BY 前插入
        int pos = sql.length();
        for (String keyword : Arrays.asList(" order by ", " limit ", " group by ", " having ")) {
            int idx = lowerSql.lastIndexOf(keyword);
            if (idx > 0) pos = Math.min(pos, idx);
        }
        return pos;
    }
}
```

#### 4.2.4 执行顺序建议
重写器按**注册顺序**依次执行，建议顺序：
```
1. 安全类重写器（权限过滤、字段脱敏）← 优先执行
2. 业务类重写器（租户隔离、软删除、数据范围）
3. 性能类重写器（LIMIT 强制、查询优化）
```

> ⚠️ **重要**：重写器应保持**幂等性**，避免多次应用产生副作用（如重复添加 WHERE 条件）。

### 4.3 获取调用方信息（高级用法）

若需在验证器/重写器中获取当前用户/租户信息，可通过 `SqlValidationContext.getCaller()` 或 `SqlRewriteContext.getCaller()` 传递：

```java
// 1. 构建 Tools 时传入 caller 标识
Text2SqlTools tools = new Text2SqlTools(dataSourceInfos, validators, rewriters);

// 2. 在调用查询工具时，通过自定义逻辑传递 caller
// （框架预留扩展点，实际项目中可结合 ThreadLocal/RequestContext 实现）

// 3. 在重写器中使用
public class TenantSqlRewriter implements SqlRewriter {
    @Override
    public SqlContext rewrite(SqlRewriteContext context) {
        Object caller = context.getCaller(); // 如: "tenant_123"
        // ... 使用 caller 构建参数
    }
}
```

> 💡 **最佳实践**：建议通过 `ThreadLocal<RequestContext>` 或框架级上下文传递调用方信息，避免参数透传污染业务代码。


## 5. 内置扩展组件

框架提供开箱即用的常用实现，位于 `com.agentsflex.text2sql.core.impl` 包：

| 组件类 | 类型 | 功能描述 | 使用示例 |
|--------|------|----------|----------|
| `SensitiveColumnValidator` | SqlValidator | 拦截访问敏感字段（如 password, secret_key） | `new SensitiveColumnValidator("password", "credit_card")` |
| `JoinCountValidator` | SqlValidator | 限制 SQL 中 JOIN 的最大数量 | `new JoinCountValidator(3)` |
| `TenantSqlRewriter` | SqlRewriter | 自动添加租户隔离条件 `WHERE tenant_id = ?` | `new TenantSqlRewriter("org_id")` |
| `SoftDeleteRewriter` | SqlRewriter | 自动过滤已删除数据 `WHERE deleted = 0` | `new SoftDeleteRewriter("deleted", 0)` |
| `LimitEnforcerRewriter` | SqlRewriter | 强制添加 LIMIT，防止全表扫描 | `new LimitEnforcerRewriter(1000)` |

### 5.1 组合使用示例
```java
List<Tool> tools = Text2SqlTools.builder()
    .addDataSourceInfo(dataSource)

    // 🔐 安全层
    .addSqlValidator(new SensitiveColumnValidator("password", "secret"))
    .addSqlValidator(ctx -> {
        // 自定义：禁止跨库查询
        if (ctx.getOriginalSql().contains(".")) {
            return ValidationResult.fail("Cross-database query is not allowed");
        }
        return ValidationResult.pass();
    })

    // 🏢 业务层
    .addSqlRewriter(new TenantSqlRewriter("tenant_id"))
    .addSqlRewriter(new SoftDeleteRewriter("is_deleted", false))

    // ⚡ 性能层
    .addSqlValidator(new JoinCountValidator(5))
    .addSqlRewriter(new LimitEnforcerRewriter(500))

    .buildTools();
```


## 6. 安全指南 (Security)

### 6.1 多层 SQL 校验体系
```
┌─────────────────────────────────┐
│  SQL 校验流程                    │
├─────────────────────────────────┤
│  1. 基础校验 (框架内置)           │
│     • 只读校验：仅允许 SELECT/WITH │
│     • 关键词拦截：INSERT/UPDATE 等 │
│     • 占位符检查：强制使用 ?      │
│                                  │
│  2. 自定义校验 (SqlValidator 链)  │
│     • 敏感字段拦截                │
│     • 业务规则校验                │
│     • 权限/角色校验               │
│                                  │
│  3. 执行前重写 (SqlRewriter 链)   │
│     • 租户隔离注入                │
│     • 数据范围过滤                │
│     • 性能保护 (LIMIT)           │
│                                  │
│  4. 底层执行 (PreparedStatement) │
│     • 预编译防注入                │
│     • 参数类型安全绑定            │
└─────────────────────────────────┘
```

### 6.2 自定义安全策略示例

**场景：禁止查询超过 1 年前的数据**
```java
.addSqlValidator(ctx -> {
    String sql = ctx.getOriginalSql().toLowerCase();
    // 简单检查：如果 WHERE 中没有时间条件，拒绝执行
    if (!sql.contains("create_time") && !sql.contains("updated_at")) {
        return ValidationResult.fail("Query must include time filter (create_time/updated_at)");
    }
    return ValidationResult.pass();
})
```

**场景：字段脱敏重写**
```java
.addSqlRewriter(ctx -> {
    String sql = ctx.getCurrentSql().getSql();
    // 将 SELECT password 替换为 SELECT '***' AS password
    String newSql = sql.replaceAll(
        "(?i)\\bpassword\\b",
        "'***' AS password"
    );
    return ctx.getCurrentSql().with(newSql, ctx.getCurrentSql().getParams());
})
```

### 6.3 数据权限隔离
- **多数据源支持**：通过 `builder().addDataSourceInfo()` 注册多个源，AI 根据用户意图自动选择。
- **动态注册**：根据当前登录用户权限，动态决定 `buildTools()` 时注册哪些数据源/表。
- **行级权限**：通过 `TenantSqlRewriter` 或自定义重写器实现行级数据隔离。


## 7. 调试与优化 (Debug & Optimize)

### 7.1 常见问题排查

| 问题现象 | 可能原因 | 解决方案 |
|----------|----------|----------|
| AI 编造字段名 | 未调用 `listTableColumns` | 检查 Tool 描述是否强调"必须先查字段" |
| 上下文超限 | 一次性返回太多表结构 | 确保 `listTables` 不包含字段详情 |
| SQL 执行报错 | 参数类型不匹配 | 检查 `params` 列表顺序与 `?` 是否一致 |
| 工具未触发 | Tool 描述不清晰 | 优化 `@ToolDef` 中的 description 文案 |
| 自定义验证器未生效 | 注册顺序错误或返回格式错误 | 确认返回 `null`=通过，`非null`=错误；检查注册顺序 |
| 重写器重复添加条件 | 重写器未实现幂等性 | 在重写前检查 SQL 是否已包含目标条件 |

### 7.2 性能优化建议
1.  **元数据缓存**：`JdbcDataSourceInfo` 中的表结构信息建议在应用启动时加载并缓存，避免每次查询都访问 `information_schema`。
2.  **结果截断**：`queryDataList` 返回结果建议限制最大行数（如 100 行），避免大结果集撑爆 Context。
3.  **重写器轻量化**：避免在重写器中进行复杂 SQL 解析，简单场景用字符串操作，复杂场景考虑集成 JSqlParser。
4.  **验证器短路**：将高概率失败的验证器放在链前端，快速失败减少后续开销。

### 7.3 日志与监控
```java
// 建议在重写器/验证器中添加日志（生产环境注意脱敏）
public class TenantSqlRewriter implements SqlRewriter {
    @Override
    public SqlContext rewrite(SqlRewriteContext context) {
        String originalSql = context.getCurrentSql().getSql();
        // ... 重写逻辑 ...

        // 日志记录（脱敏后）
        System.out.println("[SqlRewriter] Tenant isolation applied: "
            + maskSensitiveInfo(newSql));

        return context.getCurrentSql().with(newSql, newParams);
    }

    private String maskSensitiveInfo(String sql) {
        // 脱敏逻辑：替换密码、token 等敏感信息
        return sql.replaceAll("(?i)password\\s*=\\s*'[^']+'", "password='***'");
    }
}
```

### 7.4 提示词 (Prompt) 优化
在 `MemoryPrompt` 初始化时，可加入系统指令增强约束：
```java
prompt.addMessage(new SystemMessage(
    "你是一个数据查询助手。必须严格按照以下步骤操作：\n" +
    "1. 确认数据源 → 2. 确认表结构 → 3. 确认字段 → 4. 生成 SQL\n" +
    "禁止猜测字段名。SQL 必须使用 ? 占位符。" +
    "注意：系统已自动添加租户隔离和软删除过滤，无需手动添加。"
));
```


## 8. 附录 (Appendix)

### 8.1 工具返回格式规范
- **成功**: Markdown 表格 或 JSON 字符串（PrettyFormat 便于 AI 解析）。
- **失败**: 必须以 `Error: ` 开头，后接人类可读的错误原因及建议。
- **验证器失败**: `Error: Validation failed: <具体原因>`
- **重写器失败**: `Error: SQL rewrite failed: <具体原因>`

### 8.2 接口方法签名速查

#### SqlValidator
```java
@FunctionalInterface
public interface SqlValidator {
    ValidationResult validate(SqlValidationContext context);
    static SqlValidator of(Function<SqlValidationContext, ValidationResult> func);
}
```

#### SqlRewriter
```java
@FunctionalInterface
public interface SqlRewriter {
    SqlContext rewrite(SqlRewriteContext context);
    static SqlRewriter of(Function<SqlRewriteContext, SqlContext> func);
}
```

#### Text2SqlTools.Builder
```java
public static class Builder {
    public Builder addSqlValidator(SqlValidator validator);
    public Builder addSqlValidators(List<SqlValidator> validators);
    public Builder addSqlRewriter(SqlRewriter rewriter);
    public Builder addSqlRewriters(List<SqlRewriter> rewriters);
    public List<Tool> buildTools();
}
```

### 8.3 环境变量配置
```bash
# AI 模型配置
export GITEE_APIKEY="your_api_key_here"

# 数据库配置（示例）
export DB_HOST="192.168.1.100"
export DB_PORT="3306"
export DB_NAME="mydb"
export DB_USERNAME="app_user"
export DB_PASSWORD="secure_password"

# 业务配置（可选）
export DEFAULT_TENANT_ID="tenant_default"
export MAX_QUERY_LIMIT="1000"
```

### 8.4 参考资源
- 📚 Agents-Flex 官方文档：https://gitee.com/agents-flex/agents-flex
- 🧠 AI Skills 渐进式披露架构详解: https://agentsflex.com/zh/chat/skills.html


> **⚠️ 重要提示**
> 1. 本文档涉及数据库直接操作权限，生产环境部署前请务必经过安全团队审计。
> 2. 自定义 `SqlRewriter` 时请确保 SQL 语法正确，避免生成非法 SQL 导致执行失败。
> 3. 验证器/重写器的执行顺序会影响最终效果，请根据业务需求合理注册。

