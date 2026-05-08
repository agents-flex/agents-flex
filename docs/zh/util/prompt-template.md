# PromptTemplate 开发者文档

<div v-pre>


## 1. 概述

`PromptTemplate` 是 Agents-Flex 核心模块中用于处理大语言模型（LLM）提示词（Prompt）的轻量级模板引擎。它旨在解决动态构建 Prompt 时的复杂性问题，支持变量替换、JSONPath 深度取值以及优雅的空值兜底机制。

### 核心特性

*   **高性能缓存**：内置 `ConcurrentHashMap` 对模板字符串和编译后的 JSONPath进行双重缓存，避免重复解析与编译，适合高并发场景。
*   **JSONPath 支持**：原生支持 JSONPath 语法，可直接从复杂的嵌套 Map/JSON 对象中提取数据。
*   **空值兜底逻辑 (`??`)**：借鉴 Kotlin/C# 风格，支持 `{{ user.name ?? 'Unknown' }}` 语法，当主表达式为空时自动使用备选值。
*   **安全转义**：支持针对 JSON 输出场景的特殊字符转9义，防止生成的 Prompt 破坏 JSON 结构。
*   **严格模式**：对于未提供兜底且值为空的必填字段，抛出包含详细上下文信息的异常，便于调试。



## 2. 快速开始

### 2.1 基本用法

```java
import com.agentsflex.core.prompt.PromptTemplate;
import java.util.HashMap;
import java.util.Map;

public class Demo {
    public static void main(String[] args) {
        // 1.定义模板
        String template = "你好, {{ user.name }}! 你的角色是 {{ role }}.";

        // 2. 获取模板实例 (自动缓存)
        PromptTemplate promptTemplate = PromptTemplate.of(template);

        // 3. 准备数据
        Map<String, Object> data = new HashMap<>();
        data.put("user", Map.of("name", "Alice"));
        data.put("role", "AI Assistant");

        // 4. 渲染
        String result = promptTemplate.format(data);
        System.out.println(result);
        // 输出: 你好, Alice! 你的角色是 AI Assistant.
    }
}
```

### 2.2 使用 JSONPath 提取嵌套数据

无需手动展开 Map，直接使用点号或 JSONPath 语法访问深层属性。

```java
String template = "用户ID: {{ $.user.profile.id }}, 邮箱: {{ user.contact.email }}";
Map<String, Object> data = new HashMap<>();
// 假设 data 结构复杂，包含嵌套对象
data.put("user", Map.of(
    "profile", Map.of("id", 10086),
    "contact", Map.of("email", "test@example.com")
));

String result = PromptTemplate.of(template).format(data);
// 输出: 用户ID: 10086, 邮箱: test@example.com
```

### 2.3 空值兜底 (Null Safety)

使用 `??` 运算符提供默认值，防止因数据缺失导致 Prompt 出现空白或错误。

```java
// 如果 user.nickname 不存在，则使用 user.name；如果都不存在，使用 'Guest'
String template = "欢迎, {{ user.nickname ?? user.name ?? 'Guest' }}";

Map<String, Object> data = new HashMap<>();
data.put("user", Map.of("name", "Bob")); // 没有 nickname

String result = PromptTemplate.of(template).format(data);
// 输出: 欢迎, Bob
```



## 3. 高级特性

### 3.1 JSON 输出模式

当生成的 Prompt 需要作为 JSON 的一部分（例如在 Function Calling 或结构化输出中）时，开启 `escapeForJsonOutput` 参数，自动转义引号、换行符等敏感字符。

```java
String template = "{\"content\": \"{{ message }}\"}";
Map<String, Object> data = Map.of("message", "He said \"Hello\"\nNew Line");

// 不开启转义会导致 JSON 格式错误
// String badJson = PromptTemplate.of(template).format(data);

// 开启转义
String goodJson = PromptTemplate.of(template).format(data, true);
// 输出: {"content": "He said \"Hello\"\nNew Line"}
```

### 3.2 缓存管理

`PromptTemplate` 内部维护了两个静态缓存：
1.  `TEMPLATE_CACHE`: 缓存解析后的 Token 列表。
2.  `JSONPATH_CACHE`: 缓存编译后的 JSONPath 对象。

在长期运行的应用中，这些缓存会自动增长。如果需要释放内存（例如在测试环境或动态模板极多的场景），可以手动清空：

```java
PromptTemplate.clearCache();
```

> **注意**：在生产环境中，通常不需要手动清空缓存，除非遇到内存泄漏或模板字符串无限不重复生成的极端情况。



## 4. 设计原理与内部实现

为了帮助开发者更好地理解和扩展，以下简述核心实现逻辑。

### 4.1 模板解析流程

1.  **正则匹配**：使用 `Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}")` 识别 `{{ ... }}` 占位符。
2.  **Token 拆分**：将模板字符串拆分为 `TemplateToken` 列表。
    *   `StaticToken`: 普通文本，直接拼接。
    *   `DynamicToken`: 包含表达式解析结果 `ParseResult`。
3.  **表达式解析**：
    *   检测 `??` 符号。
    *   构建链表结构的 `ParseResult`，支持多级兜底（如 `A ?? B ?? C`）。
    *   识别字面量（被单引号或双引号包裹的字符串）。

### 4.2 求值逻辑 (Evaluation)

`evaluate` 方法采用递归方式处理兜底链：

1.  **字面量检查**：如果是 `'string'` 或 `"string"`，直接返回去引号后的内容。
2.  **JSONPath 取值**：
    *   自动补全 `$` 前缀（如果缺失）。
    *   从缓存获取编译好的 `JSONPath`。
    *   执行 `compiled.eval(root)`。
3.  **兜底递归**：如果当前表达式结果为 `null`，则递归调用 `evaluate` 处理 `defaultResult`（即 `??` 后面的部分）。
4.  **空值处理**：如果最终结果为空且没有显式兜底（即不是以 `?? ""` 结尾），抛出 `IllegalArgumentException`，附带当前模板和数据快照，方便排查。

### 4.3 线程安全

*   `PromptTemplate` 实例本身是**不可变**的（Immutable），一旦创建，其 `tokens` 列表不可修改。
*   静态缓存使用 `ConcurrentHashMap` 保证多线程下的读写安全。
*   `format` 方法中使用的局部变量（如 `StringBuilder`）均在线程栈内，无共享状态，因此**线程安全**。



## 5. 最佳实践

1.  **复用实例**：尽量使用 `PromptTemplate.of(template)` 获取实例，不要每次都 `new PromptTemplate()`，以利用缓存提升性能。
2.  **明确兜底**：对于非关键信息，务必使用 `??` 提供默认值，避免程序因缺少某个非核心字段而崩溃。
3.  **关键信息校验**：对于关键业务字段，不要提供兜底，让系统在数据缺失时抛出异常，以便及时发现数据源问题。
4.  **避免复杂逻辑**：PromptTemplate 仅负责简单的取值和拼接。不要在模板中尝试执行复杂的逻辑运算，复杂的逻辑应在 Java 代码中预处理数据后再传入。



## 6. 常见问题 (FAQ)

**Q: 支持 if-else 逻辑判断吗？**
A: 不支持。PromptTemplate 定位为轻量级模板引擎。如果需要复杂的条件判断，建议在 Java 层组装好数据，或使用更强大的模板引擎（如 Thymeleaf/Freemarker）预处理，再将结果传入 LLM。

**Q: JSONPath 的性能如何？**
A: 首次编译 JSONPath 会有少量开销，但 Agents-Flex 内部做了缓存，后续相同路径的取值性能极高，接近直接 Map.get。

**Q: 如何处理包含 `{{` 的原始文本？**
A: 目前不支持转义占位符。如果模板中必须包含 `{{`，建议将其拆分或通过数据传入。



## 7. 附录：API 参考

| 方法签名 | 描述                    |
|:--- |:----------------------|
| `static PromptTemplate of(String template)` | 获取模板实例（带缓存）           |
| `String format(Map<String, Object> rootMap)` | 渲染模板，默认不进行 JSON 转义    |
| `String format(Map<String, Object> rootMap, boolean escapeForJsonOutput)` | 渲染模板，可选是否进行 JSON 转义   |
| `static void clearCache()` | 清空所有缓存                |



*Copyright (c) 2023-2026, Agents-Flex. Licensed under Apache License 2.0.*


</div>
