# PromptTemplate 提示词模板
<div v-pre>

## 概述

`PromptTemplate` 是 Agents-Flex 框架提供的**高性能文本模板引擎**，用于将包含 `{{xxx}}` 占位符的提示词模板动态渲染为最终文本。它专为 LLM 应用场景设计，支持：

- **JSONPath 表达式**：从复杂嵌套数据中提取字段
- **空值兜底逻辑（`??`）**：提供多级默认值，避免缺失报错
- **JSON 转义支持**：安全输出用于 JSON 结构的字符串
- **编译缓存机制**：自动缓存模板解析与 JSONPath 编译结果，提升性能

> ✅ **典型用途**：
> 动态生成系统指令、用户提示、工具描述、RAG 上下文等结构化提示内容。


## 基础语法

### 1. 基本占位符

```text
Hello {{ name }}!
Today is {{ date }}.
```

### 2. Object

```text
用户：{{ user.name }}
邮箱：{{ user.contact.email }}
订单数：{{ stats.orderCount }}
```

### 3. 空值兜底

```text
称呼：{{ user.nick ?? user.realName ?? "匿名用户" }}
城市：{{ address.city ?? "未填写" }}
```


- 支持**链式兜底**：从左到右尝试，直到取到非空值
- 若以 `??` 结尾，表示允许空值，不报错，例如：

```
{{ user.nick ??  }}
```


### 4. 字符串字面量
```text
默认角色：{{ '客服助手' }}
提示语：{{ "请提供订单号" }}
```

> 支持单引号 `'xxx'` 或双引号 `"xxx"`。

## 使用方式

### 1. 基础渲染
```java
String template = "你好，{{ user.name ?? '访客' }}！你有 {{ count }} 条未读消息。";
Map<String, Object> data = Map.of(
    "user", Map.of("name", "张三"),
    "count", 5
);

String result = PromptTemplate.of(template).format(data);
// 输出：你好，张三！你有 5 条未读消息。
```

### 2. 处理缺失字段（无兜底 → 抛异常）
```java
String template = "欢迎 {{ user.fullName }}";
Map<String, Object> data = Map.of("user", Map.of("name", "李四")); // 无 fullName

// 抛出 IllegalArgumentException，提示缺失字段及上下文数据
PromptTemplate.of(template).format(data);
```

### 3. 允许空值（显式兜空）
```java
String template = "备注：{{ note ?? }}"; // 允许 note 为空
String result = PromptTemplate.of(template).format(Map.of());
// 输出：备注：
```

### 4. JSON 安全输出（用于嵌入 JSON）
```java
String template = "{ \"query\": \"{{ input }}\" }";
Map<String, Object> data = Map.of("input", "他说：\"你好！\"");

String jsonOutput = PromptTemplate.of(template).format(data, true);
// 输出：{ "query": "他说：\"你好！\"" }
// → 双引号被转义为 \"，确保 JSON 合法
```

## 高级特性

### 1. 编译缓存（自动启用）
- 首次使用某模板时解析并缓存
- 后续相同模板直接复用解析结果
- JSONPath 表达式也单独缓存，避免重复编译

> 📈 **性能提示**：
> 在高频场景（如 Agent 循环）中，使用 `PromptTemplate.of(template)` 而非 `new PromptTemplate(template)` 以利用缓存。

### 2. 清理缓存（仅限测试/热更新场景）
```java
// 清空所有模板与 JSONPath 缓存
PromptTemplate.clearCache();
```

> ⚠️ **生产环境慎用**：会导致后续请求重新编译，短暂性能下降。


## 数据结构支持

`format(Map<String, Object> rootMap)` 支持任意嵌套的 Java 对象结构，包括：

| 数据类型 | 示例 | 说明 |
|--------|------|------|
| **Map** | `Map.of("user", Map.of("name", "Alice"))` | 推荐，天然匹配 JSONPath |
| **POJO** | `new User("Bob")` | 需 getter 方法，字段名匹配 |
| **List** | `List.of("a", "b")` | 可通过索引访问：`{{ items[0] }}` |
| **基本类型** | `"text"`, `123`, `true` | 直接渲染 |

> ✅ **最佳实践**：使用 `Map<String, Object>` 构建上下文，避免反射开销。


## 错误处理

当模板中**未提供兜底**且**数据缺失**时，抛出 `IllegalArgumentException`，包含：

- 缺失的表达式
- 原始模板
- 已提供的参数（Pretty JSON 格式）

**示例错误信息**：
```text
Missing value for expression: "user.fullName"
Template: 欢迎 {{ user.fullName }}
Provided parameters:
{
	"user": {
		"name": "李四"
	}
}
```

> 💡 **调试建议**：
> 开发阶段保留此异常，确保提示词数据完整性；生产环境可在外层捕获并提供通用兜底。

## 最佳实践

1. **优先使用 Map 构建上下文**
   ```java
   Map<String, Object> ctx = new HashMap<>();
   ctx.put("user", Map.of("name", "Alice"));
   ```

2. **关键字段提供兜底**
   ```text
   角色：{{ role ?? '默认助手' }}
   ```

3. **复杂逻辑在模板外处理**
    - 避免在模板中写业务逻辑
    - 预处理数据后再传入 `format()`

4. **多语言/多场景模板复用**
   ```java
   // 全局缓存常用模板
   private static final PromptTemplate WELCOME_TEMPLATE =
       PromptTemplate.of("欢迎 {{ name ?? '用户' }}！");
   ```

5. **JSON 输出务必开启转义**
   ```java
   template.format(data, true); // 用于嵌入 JSON 字段时
   ```

## 常见问题

**Q：支持 `if/else` 条件判断吗？**

A：不支持。`PromptTemplate` 是**纯取值模板**，复杂逻辑应在 Java 层处理后传入。

**Q：如何访问 List 元素？**

A：使用 JSONPath 索引语法：

```text
第一项：{{ items[0] }}
最后一项：{{ items[-1] }}
```

**Q：性能如何？**

A：首次编译后，后续渲染仅需字段查找，性能接近字符串拼接。缓存机制确保高并发下高效。

**Q：能和 Spring EL 或 Thymeleaf 一起用吗？**

A：可以，但不推荐。`PromptTemplate` 专为 LLM 场景优化，语法更简洁，且避免引入额外依赖。


##  示例汇总

| 场景 | 模板 | 数据 | 输出 |
|------|------|------|------|
| 基础替换 | `你好 {{name}}` | `{"name": "Alice"}` | `你好 Alice` |
| 多级兜底 | `{{a ?? b ?? "默认"}}` | `{"b": "B"}` | `B` |
| 空值允许 | `备注：{{note ?? }}` | `{}` | `备注：` |
| JSON 转义 | `{"text": "{{msg}}"}` | `{"msg": "他说\"你好\""}` | `{"text": "他说\"你好\""}` |




</div>
