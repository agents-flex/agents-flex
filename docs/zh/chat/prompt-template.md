<div v-pre>

# PromptTemplate 提示词模板

## 概述

`PromptTemplate` 是一个轻量文本模板引擎，用 `{{ expression }}` 从 Map 数据中取值，并支持 `??` 兜底链。
它适合把稳定的提示结构与每次请求的数据分离，例如系统指令、RAG 上下文、分类任务和结构化输出约束。

它不是完整编程语言：没有 if/else、循环或函数调用。复杂业务判断应先在 Java 中完成，再把整理好的数据传给
模板。

## 适用场景

- 根据用户、语言和产品配置生成系统提示；
- 把检索结果填入固定的 RAG 提示结构；
- 为分类、抽取和评测任务复用同一模板；
- 使用默认值处理可选字段；
- 把动态字符串安全嵌入 JSON 字符串字段。

一次性短字符串直接拼接更简单；涉及复杂布局、国际化资源或条件逻辑时，也可以选择成熟模板引擎。

## 快速开始

```java
String source = "你好，{{ user.name ?? '访客' }}！你有 {{ count }} 条待办。";

Map<String, Object> data = Map.of(
    "user", Map.of("name", "张三"),
    "count", 5
);

String text = PromptTemplate.of(source).format(data);
System.out.println(text);
```

输出：

```text
你好，张三！你有 5 条待办。
```

`PromptTemplate.of()` 会按完整模板字符串使用全局缓存；相同字符串后续复用已解析 Token 和 JSONPath。

## 基础语法

### 字段取值

```text
姓名：{{ user.name }}
邮箱：{{ user.contact.email }}
第一项：{{ items[0] }}
```

表达式会转换为 Fastjson2 JSONPath；没有 `$` 前缀时框架自动添加 `$.`。

### 多级兜底

```text
称呼：{{ user.nickname ?? user.name ?? '匿名用户' }}
```

框架从左到右选取第一个非 null 值。最终结果为空且没有显式空兜底时，默认抛出
`IllegalArgumentException`。

### 允许空值

表达式以 `??` 结尾表示允许最终为空：

```text
备注：{{ note ?? }}
```

### 字符串字面量

```text
角色：{{ '客服助手' }}
语言：{{ "简体中文" }}
```

只支持单引号或双引号包裹的字符串字面量，不执行 Java 表达式。

## 缺失变量策略

默认行为是缺失时报错，适合尽早发现 Prompt 数据问题：

```java
PromptTemplate template = PromptTemplate.of("订单：{{ order.id }}");
template.format(Map.of()); // IllegalArgumentException
```

也可以关闭异常：

```java
PromptTemplate template = new PromptTemplate("订单：{{ order.id }}");
template.setFailOnMissingVariable(false);
String text = template.format(Map.of()); // "订单："
```

或者保留原表达式：

```java
template.setKeepExpressionOnMissingVariable(true);
String text = template.format(Map.of()); // "订单：{{order.id}}"
```

`keepExpressionOnMissingVariable` 优先于 `failOnMissingVariable`。

`PromptTemplate.of()` 返回缓存中的共享可变实例。需要修改缺失变量策略时，推荐使用
`new PromptTemplate(source)` 创建独立实例，避免一个请求修改全局缓存对象的行为。

## JSON 字符串转义

将变量嵌入 JSON 字符串值时，使用第二个参数启用转义：

```java
PromptTemplate template = PromptTemplate.of(
    "{\"query\":\"{{ input }}\"}"
);

String json = template.format(
    Map.of("input", "第一行\n他说：\"你好\""),
    true
);
```

该选项会转义字符串中的反斜杠、引号和控制字符，但它不会验证最终文本一定是合法 JSON。对象、数组和整体 JSON
结构应优先使用 JSON 序列化库构建，不要依赖模板拼接复杂 JSON。

## 缓存机制

框架维护两个进程级 `ConcurrentHashMap`：

- 模板字符串到 `PromptTemplate` 的缓存；
- 完整 JSONPath 到编译结果的缓存。

```java
PromptTemplate.clearCache();
```

`clearCache()` 主要用于测试或模板热更新验证。高并发生产请求频繁清理会导致后续重新解析，也无法限制由大量
用户自定义模板造成的缓存增长。不要把无限多、用户可控的唯一模板字符串直接交给全局缓存。

## 与 Prompt 配合

```java
PromptTemplate systemTemplate = PromptTemplate.of(
    "你是 {{ product }} 的客服，只使用 {{ language }} 回答。"
);

String systemText = systemTemplate.format(Map.of(
    "product", "Agents-Flex",
    "language", "简体中文"
));

SimplePrompt prompt = new SimplePrompt(userQuestion);
prompt.setSystemMessage(SystemMessage.of(systemText));
```

模板只负责生成文本；消息角色、历史、图片和 Tool 仍由 `Prompt` 管理。

## 生产建议

- 关键字段不提供静默空值，让缺失数据在调用模型前失败；
- 可选字段使用明确默认值或显式 `??` 空兜底；
- 不把密钥、认证 Header 或不应发送的数据放入模板上下文；
- 用户输入嵌入 JSON 字符串时启用转义，复杂 JSON 使用序列化库；
- 模板版本纳入代码或配置管理，记录调用使用的版本；
- 限制动态模板数量、长度和数据规模，避免全局缓存无界增长。

## 常见问题

### 支持 if/else 或循环吗？

不支持。先在 Java 层计算条件和列表文本，再作为字段传入。

### 为什么字段存在但仍得到空值？

检查 JSONPath、Map Key 大小写和对象 Getter。表达式解析异常会按“未取到值”处理，再进入兜底或缺失策略。

### `format(data, true)` 会返回完整 JSON 字面量吗？

不会。它只对动态字符串值做 JSON 转义，不会给结果自动加引号，也不会校验整个文档。

### 可以修改 `PromptTemplate.of()` 返回对象的策略吗？

技术上可以，但对象由全局缓存共享，会影响后续相同模板。需要定制策略时使用构造器创建独立对象。

## 下一步

- [构建 Prompt](./prompt)
- [理解 Message](./message)
- [使用 Memory](./memory)

</div>
