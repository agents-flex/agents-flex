# Maps 工具类
<div v-pre>


`Maps` 是 Agents-Flex 提供的一个轻量、链式、增强型 `HashMap<String, Object>` 实现，旨在提升 Java 后端编写动态结构（如 JSON、上下文对象、链式参数构造）的开发体验。

它特别适用于：

* 需要快速构造 JSON 对象
* 管理上下文、配置、动态参数
* 构建链式可读性强的 Map 结构
* 处理条件赋值（if-not-null / if-not-empty / if xxx）
* 处理多级 key（"a.b.c" → 自动构建嵌套子 Map）
* 避免大量模板式 Map 判空代码


## 1. 核心概念

### 1.1 Maps 是什么？

`Maps` 是一个继承自 `HashMap<String, Object>` 的增强型工具类，专为构建结构化数据对象而设计。它提供流式 API、智能判空、嵌套设置能力，同时保持原生 `Map` 的语义。

### 1.2 Maps 为什么存在？

在 Java 后端常见场景如：

* 组装返回 JSON
* 生成 LLM 请求参数
* 构造配置对象
* 批量条件赋值

往往都需要大量冗余的 null 判断或重复代码：

```java
Map<String, Object> map = new HashMap<>();
if (value != null) {
    map.put("key", value);
}
```

使用 `Maps`：

```java
Maps.of().setIfNotNull("key", value);
```

更简洁、更安全、更可读。

### 1.3 Maps 要解决的核心问题

* 构建复杂 Map 过于繁琐
* 嵌套结构（child map）不方便
* 需要大量 null/空值判断
* 不支持链式调用
* JSON 序列化不够友好

Maps 对这些痛点进行了增强和封装。


## 2. 使用场景

### 2.1 构造 JSON 参数

```java
Maps params = Maps.of()
        .set("model", "gpt-4")
        .setIfNotNull("temperature", temp)
        .setChild("options.max_tokens", 2048);
```

### 2.2 构造上下文对象（context）

```java
Maps context = Maps.of()
        .set("userId", uid)
        .setIfNotEmpty("metadata", metadata)
        .setOrDefault("source", source, "system");
```

### 2.3 条件逻辑处理

```java
Maps.of()
    .setIf(condition, "enabled", true)
    .setIfNotContainsKey("debug", "debug", false);
```

### 2.4 自动处理多级 key

```java
Maps.of().setChild("a.b.c", 123);

// 等效于
{ "a": { "b": { "c": 123 } } }
```


## 3. 快速入门

### 3.1 创建 Maps

#### 空对象

```java
Maps m = Maps.of();
```

#### 单 key-value

```java
Maps m = Maps.of("name", "agents-flex");
```

#### 仅当 value 不为 null

```java
Maps m = Maps.ofNotNull("id", id);
```

#### 仅当 value 不为空（包括空数组、空 map、空集合、空字符串）

```java
Maps m = Maps.ofNotEmpty("list", list);
```



### 3.2 基本操作：set()

```java
Maps m = Maps.of()
        .set("name", "Michael")
        .set("age", 20);
```

返回 this，支持链式调用。


### 3.3 setChild() 多级 key

```java
Maps m = Maps.of()
        .setChild("user.info.email", "xxx@gmail.com");

// 输出
{
  "user": {
    "info": {
      "email": "xxx@gmail.com"
    }
  }
}
```



### 3.4 条件设置

#### setIf(condition, key, value)

```java
m.setIf(age > 18, "adult", true);
```

#### setIf(Function)

```java
m.setIf(maps -> maps.containsKey("name"), "hasName", true);
```

#### setIfNotNull

```java
m.setIfNotNull("title", title);
```

#### setIfNotEmpty

```java
m.setIfNotEmpty("tags", tags);
```


### 3.5 JSON 序列化

```java
String json = Maps.of("k", "v").toJSON();
```

底层使用 `fastjson2`。



## 4. 进阶使用

### 4.1 setOrDefault

```java
m.setOrDefault("timeout", timeout, 30);
```

当 `timeout` 为空值（null、空集合、空字符串等）时自动使用默认值。



### 4.2 通过 Map 批量赋值（仅当不为空）

```java
Maps meta = Maps.of().set("version", 1);
Maps m = Maps.of().setIfNotEmpty(meta);
```



### 4.3 setIfContainsKey / setIfNotContainsKey

#### 如果包含某 key，则设置另一个 key

```java
m.setIfContainsKey("debug", "logLevel", "verbose");
```

#### 如果不包含某 key，则设置另一个 key

```java
m.setIfNotContainsKey("id", "id", UUID.randomUUID().toString());
```



## 5. 高级应用示例

### 5.1 构建 OpenAI / LLM 请求 payload（典型用法）

```java
Maps payload = Maps.of()
    .set("model", "gpt-4.1")
    .set("messages", messages)
    .setIfNotNull("temperature", temperature)
    .setIfNotEmpty("tools", tools)
    .setChild("response_format.type", "json_schema")
    .setChild("response_format.schema", schema);
```

非常适合动态参数构造。



### 5.2 构建复杂配置树

```java
Maps config = Maps.of()
    .setChild("db.mysql.host", "localhost")
    .setChild("db.mysql.port", 3306)
    .setChild("db.redis[0].host", "127.0.0.1"); // 数组 key 也支持
```



### 5.3 构建 API 返回结果

```java
return Maps.of()
        .set("code", 0)
        .set("message", "success")
        .setIfNotEmpty("data", data)
        .toJSON();
```



## 6. 核心组件说明

Maps 继承自：

```java
public class Maps extends HashMap<String, Object>
```

核心能力包括：

| 方法              | 功能         |
|-----------------| - |
| `set`           | 普通赋值（链式）   |
| `setChild`      | 多级 key 赋值  |
| `setIf`         | 条件赋值       |
| `setIfNotNull`  | 非 null 才赋值 |
| `setIfNotEmpty` | 非空才赋值      |
| `setOrDefault`  | 空值使用默认值    |
| `ofXxx` 系列      | 工厂方法       |
| `toJSON`        | JSON 序列化   |



## 7. 深入源码解析

以下是 Maps 的核心实现思路：

### 7.1 工厂方法

```java
public static Maps of() { return new Maps(); }
public static Maps of(String key, Object value) { ... }
```

目标：提升构建体验，减少 `new Maps()` 的模板代码。



### 7.2 链式 set 实现

```java
public Maps set(String key, Object value) {
    super.put(key, value);
    return this;
}
```

链式返回 this。



### 7.3 setChild：多级 key 处理逻辑

```java
String[] keys = key.split("\\.");
currentMap = (Map<String, Object>) currentMap.computeIfAbsent(currentKey, k -> Maps.of());
```

关键点：

* 自动构建子 map
* 兼容任意层级
* 不覆盖已有 map（而是往下钻）



### 7.4 isNullOrEmpty

这是 Maps 的“智能空判断”：

* null
* 空字符串
* 空集合
* 空 Map
* 空数组

都视为 empty，使得 setIfNotEmpty 更稳健。


</div>
