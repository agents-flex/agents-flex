# JsonSanitizer
<div v-pre>

`JsonSanitizer` 是面向大模型输出的类 JSON 容错工具。它可以将工具参数中常见的 JavaScript 风格值转换为 JSON 字符串，从夹带说明文字的输出中提取对象，并补齐缺失的对象花括号。

```java
import com.agentsflex.core.util.JsonSanitizer;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

String raw = "{\"formatter\": function(v) { return v * 2; }}";
String sanitized = JsonSanitizer.sanitize(raw);
JSONObject arguments = JSON.parseObject(sanitized);

// arguments.getString("formatter")
// -> function(v) { return v * 2; }
```

该类位于 `agents-flex-core` 模块的 `com.agentsflex.core.util` 包中，不需要额外依赖。

## 1. 适用场景

大模型生成 Tool Calling 参数时，可能返回以下非标准 JSON 内容：

```text
参数如下：{
  "status": success,
  "formatter": (value) => ({ result: value }),
  "createdAt": new Date(),
  "pattern": /foo{1,3}/gi,
  "fallback": undefined
}
```

直接交给严格 JSON 解析器通常会失败。`JsonSanitizer` 负责在解析前做有限、可预测的文本修复，适合：

- Tool Calling 参数容错。
- 从带 Markdown 或说明文字的模型输出中提取对象。
- 保留 JavaScript 表达式源码供业务后续识别或展示。
- 补齐流式输出截断造成的外层对象花括号缺失。

::: warning
`JsonSanitizer` 只修复文本，不验证字段类型、必填项、数值范围或调用权限。修复后仍须使用 JSON 解析器解析，并执行正常的业务校验。
:::

## 2. sanitize

`sanitize(String text)` 扫描类 JSON 文本，将已识别的非 JSON 值写成合法的 JSON 字符串值。

```java
String raw = "{"
    + "\"name\": 'demo',"
    + "\"status\": success,"
    + "\"enabled\": true,"
    + "\"count\": 3,"
    + "\"empty\": null"
    + "}";

JSONObject result = JSON.parseObject(JsonSanitizer.sanitize(raw));

result.getString("name");       // demo
result.getString("status");     // success
result.getBoolean("enabled");  // true
result.getInteger("count");    // 3
result.get("empty");            // null
```

当前支持的转换包括：

| 输入形式 | 转换结果 |
| --- | --- |
| 单引号字符串，如 `'demo'` | 标准双引号 JSON 字符串 |
| `function (...) {...}`、`async function (...) {...}` | 保留完整源码的字符串 |
| 箭头函数，如 `(v) => ({value: v})` | 保留完整源码的字符串 |
| `new Date()` 等 `new` 表达式 | 保留完整源码的字符串 |
| 正则字面量，如 `/foo[,}]bar/gi` | 保留完整源码的字符串 |
| `undefined`、`NaN`、`Infinity` | 对应源码字符串 |
| 冒号后的普通未加引号值，如 `status: success` | 字符串 `"success"` |

标准 JSON 的 `true`、`false`、`null` 和数值会保持原有类型。已经位于双引号字符串内部的 `function`、`new` 或正则样式文本也不会被再次转换。

```java
String raw = "{\"code\":\"function() { return new Date(); }\"}";
String sanitized = JsonSanitizer.sanitize(raw);

// sanitized 与 raw 保持一致
```

表达式中的嵌套函数、对象、数组、字符串、模板字符串、注释和常见正则字面量会按字符层级扫描，内部逗号或花括号不会被误认为当前 JSON 值的结束位置。

### 空值行为

- 参数为 `null` 时返回 `null`。
- 参数为空字符串时返回空字符串。
- 方法返回的是待解析文本，不保证结果一定能被 JSON 解析器接受。

## 3. extractObjects

`extractObjects(String text)` 从混合文本中提取所有花括号配对完整的对象候选，并按出现顺序返回。

```java
String output = "候选一：{\"id\":1}，候选二：{\"id\":2}";

List<String> candidates = JsonSanitizer.extractObjects(output);
// ["{\"id\":1}", "{\"id\":2}"]
```

扫描会忽略双引号字符串、单引号字符串、模板字符串、JavaScript 注释和正则字面量内部的花括号：

```java
String output = "说明 {\"pattern\": /a{1,3}/g, \"text\": \"}\"} 结束";
List<String> candidates = JsonSanitizer.extractObjects(output);

JSONObject value = JSON.parseObject(
    JsonSanitizer.sanitize(candidates.get(0))
);
```

需要注意：

- 只返回花括号完整配对的对象；未闭合对象不会出现在结果中。
- 没有候选、输入为 `null` 或空字符串时返回空列表。
- 返回的是文本候选，不代表候选一定是合法 JSON。
- 文本中存在多个对象时，应由业务根据协议选择，而不是默认信任第一个对象。

## 4. completeObject

`completeObject(String text)` 用于补齐缺失的外层左花括号和未闭合的右花括号。

```java
String missingStart = "\"name\":\"demo\",\"enabled\":true}";
String completedStart = JsonSanitizer.completeObject(missingStart);
// {"name":"demo","enabled":true}

String missingEnd = "{\"id\":42,\"options\":{\"debug\":true";
String completedEnd = JsonSanitizer.completeObject(missingEnd);
// {"id":42,"options":{"debug":true}}
```

该方法按实际未闭合的对象层数追加右花括号，并忽略字符串、注释和正则字面量内部的花括号。它不会补全缺失的引号、方括号、冒号或逗号，也不会删除多余的结构符号。

输入为 `null` 或空字符串时原样返回。

## 5. 组合容错流程

处理不受信任的模型文本时，建议优先尝试原始 JSON，再逐步扩大容错范围：

```java
static JSONObject parseModelObject(String raw) {
    try {
        return JSON.parseObject(raw);
    } catch (RuntimeException ignored) {
        // 继续尝试有限容错
    }

    try {
        return JSON.parseObject(JsonSanitizer.sanitize(raw));
    } catch (RuntimeException ignored) {
        // 尝试从说明文字中提取完整对象
    }

    for (String candidate : JsonSanitizer.extractObjects(raw)) {
        try {
            return JSON.parseObject(JsonSanitizer.sanitize(candidate));
        } catch (RuntimeException ignored) {
            // 继续尝试下一个候选
        }
    }

    String completed = JsonSanitizer.completeObject(raw);
    return JSON.parseObject(JsonSanitizer.sanitize(completed));
}
```

先解析原始 JSON 可以避免对合法输入做不必要的转换；后续每一步只针对一种常见模型输出偏差。

## 6. 与 ToolCall 的集成

通常无需手动清洗框架收到的 Tool Calling 参数。`ToolCall.getArgsMap()` 已内置以下解析顺序：

1. 直接解析原始参数。
2. 调用 `sanitize()` 后解析。
3. 使用 `extractObjects()` 提取候选，分别尝试原始候选和清洗后的候选。
4. 使用 `completeObject()` 补齐对象，再尝试原始和清洗后的内容。

```java
ToolCall toolCall = new ToolCall();
toolCall.setArguments(
    "参数如下：{\"status\": success, \"pattern\": /abc/g}"
);

Map<String, Object> arguments = toolCall.getArgsMap();
arguments.get("status");  // success
arguments.get("pattern"); // /abc/g
```

直接使用 `JsonSanitizer` 更适合自定义模型协议、非 ToolCall 的结构化输出，或者需要自行控制候选选择策略的场景。

## 7. 安全与边界

`JsonSanitizer` 不执行任何 JavaScript。函数、箭头函数、正则和 `new` 表达式只会作为普通字符串保留。但调用方仍应遵守以下约束：

- 不要将保留下来的表达式交给脚本引擎执行。
- 不要把“能够解析”视为参数可信；仍要校验字段白名单、类型、长度、范围和权限。
- 对多个对象候选建立明确选择协议，避免模型前缀中的示例对象被误当成实际参数。
- 对输入大小设置上限，避免对不受控的超长模型输出执行重复解析。
- 该工具不是完整的 JavaScript 词法分析器，只覆盖工具参数中常见且能够确定边界的语法。

</div>
