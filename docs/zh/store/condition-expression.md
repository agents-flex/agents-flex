<div v-pre>

# SQL 风格条件表达式

## 概述

`SearchWrapper` 支持使用接近 SQL WHERE 的字符串编写 metadata 过滤条件。相比逐个调用 `eq()`、`in()`、
`between()`，字符串表达式更适合复杂逻辑、动态筛选和配置化规则。

```java
SearchWrapper query = new SearchWrapper()
    .text("生产环境部署")
    .condition(
        "tenant = 'tenant-a' " +
        "AND status NOT IN ('deleted', 'blocked') " +
        "AND year BETWEEN 2024 AND 2026"
    );

List<Document> result = store.search(query);
```

这里的条件用于过滤文档 metadata，不是可以执行任意 SQL 的入口。它不支持 `SELECT`、表名、函数、子查询、排序
或数据修改语句。

## 使用场景

### 复杂检索条件

当查询包含多层 AND、OR、NOT 和括号时，字符串通常比链式调用更接近业务规则本身：

```java
query.condition(
    "tenant = 'tenant-a' " +
    "AND (category = 'guide' OR featured = true) " +
    "AND NOT (status = 'deleted' OR visibility = 'private')"
);
```

### 配置化查询规则

知识库可以保存自己的检索规则，例如只搜索已发布并处于有效期内的内容。应用读取配置后，把表达式追加到本次
`SearchWrapper`。

### 搜索表单与规则编辑器

高级搜索页面可以把分类、状态、年份和标签转换为统一条件字符串。服务端仍需对白名单字段、表达式长度和规则
复杂度进行限制。

### 链式条件难以表达的动态组合

固定的 tenant 条件继续使用链式 API，动态业务部分使用字符串表达式，两者可以安全分组组合。

### 不适合的场景

- 需要数据库函数、全文搜索函数、地理查询或字段间比较；
- 希望访问 Collection、Index 或 Partition；
- 需要执行完整 SQL；
- 目标 Store 当前没有接入 `SearchWrapper.condition`。

这些需求应使用 `StoreOptions`、Store 专属能力或扩展对应适配器。

## 快速开始

### 添加一个条件表达式

```java
SearchWrapper query = new SearchWrapper()
    .text("向量数据库部署")
    .condition("category = 'guide' AND year >= 2025")
    .maxResults(10);
```

`condition(String)` 会把表达式设置为当前查询条件。查询中已有链式条件时，表达式会作为一个完整分组，通过
AND 追加。

### 与链式条件组合

```java
SearchWrapper query = new SearchWrapper()
    .text("访问控制")
    .eq("tenant", authenticatedTenant)
    .condition(
        "visibility = 'public' OR ownerId = 'user-1001'"
    );
```

最终逻辑是：

```text
tenant = authenticatedTenant
AND (visibility = 'public' OR ownerId = 'user-1001')
```

追加的字符串表达式会保留自己的括号边界，不会让其中的 OR 意外改变外部 tenant 条件。

### 使用指定连接符追加

```java
query.condition(
    Connector.OR,
    "featured = true AND year >= 2025"
);
```

只有业务确实需要与已有条件进行 OR、AND NOT 等组合时才显式传入 `Connector`。权限和 tenant 条件通常不应
通过 OR 放宽。

## 条件设置方法

`SearchWrapper` 提供以下入口：

| 方法 | 行为 | 适用情况 |
| --- | --- | --- |
| `condition(expression)` | 使用 AND 追加表达式分组 | 最常用，保留已有条件 |
| `condition(connector, expression)` | 使用指定连接符追加分组 | 需要控制外层 AND/OR 关系 |
| `setConditionExpression(expression)` | 解析表达式并替换全部已有条件 | 从头设置完整条件 |
| `setCondition(condition)` | 直接替换为条件树 | 框架扩展或高级用法 |

::: warning 替换会移除已有安全条件
`setConditionExpression(...)` 会替换整个条件树。如果 wrapper 中已经加入 tenant 或权限条件，调用该方法会将
它们移除。普通业务查询优先使用 `condition(...)` 追加。
:::

表达式会先完成语法校验，再修改 wrapper。追加非法表达式抛出异常时，原有条件不会被写入半条无效规则。

## 比较条件

### 等于和不等于

```java
query.condition(
    "tenant = 'tenant-a' " +
    "AND status != 'deleted' " +
    "AND version == 3 " +
    "AND category <> 'internal'"
);
```

| 语义 | 支持写法 |
| --- | --- |
| 等于 | `=`、`==` |
| 不等于 | `!=`、`<>` |

### 大小比较

```java
query.condition(
    "views > 100 " +
    "AND rating >= 4.5 " +
    "AND retryCount < 3 " +
    "AND year <= 2026"
);
```

支持 `>`、`>=`、`<` 和 `<=`。字段在数据库中必须是可比较类型，尤其不要把数字 metadata 写成字符串。

## IN 与 NOT IN

```java
query.condition(
    "category IN ('guide', 'reference') " +
    "AND status NOT IN ('deleted', 'blocked') " +
    "AND priority IN (1, 2, 3)"
);
```

- IN 列表至少包含一个值；
- 列表中不能使用 NULL；
- 同一个列表建议保持相同值类型；
- 对动态列表设置最大数量，避免生成过大的数据库请求。

## BETWEEN 与 NOT BETWEEN

```java
query.condition(
    "year BETWEEN 2024 AND 2026 " +
    "AND score NOT BETWEEN 0.0 AND 0.5"
);
```

BETWEEN 包含上下边界。两个边界不能为 NULL，类型应保持一致。不同 Store 在适配时可能将 BETWEEN 转换为
`>=` 与 `<=` 的组合。

## NULL 条件

以下写法受支持：

```java
query.condition(
    "deletedAt IS NULL " +
    "AND owner IS NOT NULL " +
    "AND optionalField = NULL " +
    "AND archivedAt != NULL"
);
```

NULL 只支持等值和非等值语义，不能用于大小比较、IN 或 BETWEEN。Chroma 等后端可能不支持 NULL metadata
过滤，因此语法合法不代表目标 Store 一定能执行。

## AND、OR、NOT 与括号

```java
query.condition(
    "tenant = 'tenant-a' " +
    "AND (category = 'guide' OR category = 'reference') " +
    "AND NOT (status = 'deleted' OR status = 'blocked')"
);
```

运算优先级从高到低为：

```text
NOT > AND > OR
```

因此：

```text
a = 1 OR b = 2 AND c = 3
```

等价于：

```text
a = 1 OR (b = 2 AND c = 3)
```

业务规则存在多种逻辑运算时，建议始终显式使用括号，避免规则维护者误解优先级。

## 值的写法

### 字符串

字符串必须使用单引号或双引号：

```text
name = 'Agents-Flex'
title = "Vector Store"
emptyValue = ''
```

未加引号的普通单词不会被自动当作字符串。

### 数字

```text
count = -12
ratio >= 1.25
distance < -1.2e3
```

支持正负整数、小数和科学计数法。超出 Long 范围的整数会被拒绝。

### Boolean

```text
enabled = true
archived = FALSE
```

Boolean 不需要引号，关键字不区分大小写。

### 字符串转义

可以使用 SQL 风格的双写引号：

```text
author = 'O''Reilly'
quote = "say ""hello"""
```

也支持 `\n`、`\r`、`\t`、`\b`、`\f`、`\\`、`\'` 和 `\"`。无法识别的转义会直接报错，
不会静默修改原始值。

## 字段名

普通字段可以使用 Unicode 字符、字母、数字、下划线、`$`、点和连字符：

```text
metadata.category = 'guide'
profile.level >= 3
user-name = 'alice'
中文字段 = '示例'
```

字段包含空格、关键字或特殊字符时使用反引号：

```text
`order` = 1
`display name` = 'Alice'
`a``b` = '字段名中包含反引号'
```

字段名能通过表达式语法不代表目标数据库一定支持该字段路径。应用应维护 metadata schema 和字段白名单，不允许
客户端任意探测内部字段。

## 构建动态表达式

不要用字符串拼接直接插入未经处理的用户值：

```java
// 不推荐
query.condition("owner = '" + request.getOwner() + "'");
```

固定字段和值更适合使用链式 API：

```java
SearchWrapper query = new SearchWrapper()
    .eq("tenant", authenticatedTenant)
    .eq("owner", request.getOwner());

if (request.getRuleExpression() != null) {
    query.condition(request.getRuleExpression());
}
```

如果业务允许提交完整规则表达式，应限制可用字段、字符串长度、括号深度和 IN 列表数量。tenant 与权限条件始终
由服务端根据认证上下文添加。

## 错误处理

`condition(...)` 和 `setConditionExpression(...)` 在表达式非法时抛出 `IllegalArgumentException`，错误消息
包含从 0 开始的位置：

```text
Invalid condition expression at position 16: Expected AND between BETWEEN bounds
```

业务接口可以统一转换为参数错误：

```java
try {
    query.condition(ruleExpression);
} catch (IllegalArgumentException exception) {
    throw new BadRequestException("过滤条件语法错误", exception);
}
```

不要自动删除无法识别的条件后继续查询。这样会扩大查询范围，在权限过滤场景中可能造成数据泄露。

## 不同 Store 的执行差异

SQL 风格表达式是统一的输入方式，但数据库没有统一的过滤协议。支持条件查询的 Store 会把同一条件再次适配为
自己的表达式、SQL 或原生 Filter。

| Store | 当前适配方式 |
| --- | --- |
| Redis | 转换为 RediSearch TAG、NUMERIC 和组合查询 |
| Milvus | 转换为 Milvus 标量过滤表达式 |
| Pgvector | 转换为参数化 SQL 和 JSONB 条件 |
| MariaDB | 转换为参数化 SQL 和 `JSON_VALUE` 条件 |
| Elasticsearch | 转换为 Elasticsearch query string |
| OpenSearch | 转换为 OpenSearch query string，并作为向量评分的基础查询 |
| Chroma | 构建 Chroma `where` JSON |
| Qdrant | 构建 Qdrant 原生 `Filter` |
| 阿里云 DashVector | 使用专属适配器生成 DashVector `filter` |
| 腾讯云向量数据库 | 转换为 VectorDB filter；支持比较、IN/NOT IN、BETWEEN 和分组 |

这意味着：

- 表达式语法校验成功，只表示可以生成通用条件结构；
- 字段路径、NULL、精确字符串、NOT 和嵌套能力仍受 Store 限制；
- `minScore`、向量召回和条件过滤的执行顺序也可能不同；
- 上线前必须在目标数据库运行真实条件集成测试。

详细能力参见[存储选型与能力矩阵](./providers)和各 Store 独立文档。

## 安全建议

1. tenant 和权限条件由服务端强制追加；
2. 对动态表达式实施字段白名单；
3. 限制表达式长度、嵌套层数和 IN 列表数量；
4. Collection、Index 和 Partition 使用 `StoreOptions` 管理并单独验证；
5. 不在日志中记录包含敏感值的完整表达式；
6. 使用目标数据库验证特殊字符、类型不一致和异常条件；
7. 不把“语法解析成功”当作“数据库执行成功”或“权限校验通过”。

## 工作原理

正常业务代码只需要调用 `SearchWrapper.condition(...)`，不需要直接操作内部解析类。完整执行过程如下：

```text
SearchWrapper.condition("tenant = 'a' AND year >= 2025")
                │
                ▼
ConditionExpressionParser
将字符串解析为通用 Condition / Group / Not 条件树
                │
                ▼
SearchWrapper 保存条件树
                │
                ▼
具体 Store 再次适配
ExpressionAdaptor 或原生 ConditionBuilder
                │
                ▼
RediSearch / Milvus 表达式 / 参数化 SQL /
Elasticsearch query string / Chroma where / Qdrant Filter
                │
                ▼
数据库执行过滤与向量检索
```

`ConditionExpressionParser` 只负责理解通用字符串语法，不负责生成任何特定数据库查询。数据库字段转义、参数
绑定、NULL 语义和原生 Filter 构建，都由具体 Store 的适配层负责。

### 直接使用 ConditionExpressionParser

只有需要提前校验固定规则、缓存业务规则定义，或者开发 Store 扩展时，才需要直接使用解析类：

```java
Condition condition = ConditionExpressionParser.parse(
    "views > 100 AND (category = 'guide' OR featured = true)"
);

SearchWrapper query = new SearchWrapper();
query.setCondition(condition);
```

`Condition` 是可变结构，不应在并发查询之间共享并修改。大多数业务场景直接使用
`SearchWrapper.condition(...)` 更清晰，也更不容易错误替换已有条件。

## 测试建议

测试应分为两层：

1. 通用语法测试：比较运算、IN、BETWEEN、NULL、AND/OR/NOT、括号、转义和非法输入；
2. Store 集成测试：验证条件经过该 Store 二次适配后，在真实数据库中返回正确结果。

对于权限敏感查询，还应覆盖空表达式、错误表达式、两个 tenant、跨 Collection、特殊字符和条件绕过测试。

</div>
