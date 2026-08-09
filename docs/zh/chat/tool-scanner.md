# ToolScanner
<div v-pre>

ToolScanner 是 Agents-Flex 框架中用于 **自动发现工具方法（Tool）** 的核心组件。它通过注解扫描的方式，将开发者编写的 Java 方法转换为可供 LLM 调用的结构化工具定义。

本文档将从概念到实现，分层次介绍 ToolScanner 的工作方式、适用场景、快速上手方式、扩展配置以及源码设计原理。



## 1. 概念与使用场景

### 1.1 什么是 Tool？

在 Agents-Flex 中，**Tool** 表示 LLM 可以主动调用的外部能力，例如：

* 发送 HTTP 请求
* 查询数据库
* 调用业务逻辑
* 执行计算任务
* 操作文件系统


### 1.2 ToolScanner 的作用

ToolScanner 的职责是：

- 自动扫描类或对象中的方法
- 找出标注了 `@ToolDef` 的方法
- 将方法转为 `Tool` 对象（底层使用 MethodTool）
- 提供给 Agent/LLM 用于工具调用

换句话说，它是 **“从 Java 代码 → Agents-Flex 工具系统” 的桥梁**。

## 2. 快速入门

### 2.1 定义一个 Tool 方法

```java
public class WeatherTools {

    @ToolDef(
        name = "getWeather",
        description = "获取指定城市的天气信息"
    )
    public String getWeather(
            @ToolParam(name="city", description="城市名称", required=true)
            String city
    ) {
        return "Sunny";
    }
}
```

### 2.2 扫描 Tool

#### 扫描对象实例（非静态 + 静态方法）

```java
WeatherTools tools = new WeatherTools();
List<Tool> toolList = ToolScanner.scan(tools);
```

#### 扫描类（仅静态方法）

```java
List<Tool> toolList = ToolScanner.scan(WeatherTools.class);
```

### 2.3 结果示例（概念层）

扫描结果会得到 MethodTool 列表，例如：

* tool.name = "getWeather"
* tool.description = "获取指定城市的天气信息"
* 参数定义来源于每个 `@ToolParam`
* 调用时 MethodTool 会完成反射执行



## 3. 配置与高级用法

### 3.1 指定扫描的方法名

```java
ToolScanner.scan(tools, "getWeather", "getForecast");
```

只扫描给定的方法，适用于：

* 只暴露部分功能
* 动态选择子集工具

### 3.2 静态工具类

```java
public class SysTools {

    @ToolDef(description = "获取系统时间")
    public static long now() {
        return System.currentTimeMillis();
    }
}

List<Tool> tools = ToolScanner.scan(SysTools.class);
```

### 3.3 工具方法参数能力 (`@ToolParam`)

参数支持：

| 特性          | 用法            |
| -- |---------------|
| name        | 参数字段名称        |
| description | 用于 LLM 的解释说明  |
| enums       | 限定枚举值         |
| required    | 必填字段          |

示例：

```java
@ToolParam(
    name = "mode",
    description = "查询模式",
    enums = {"fast", "accurate"},
    required = true
)
String mode
```


## 4. 核心组件说明

### 4.1 `@ToolDef`

方法级注解，用于声明：

* name（可选，默认方法名）
* description（必填）

LLM 会基于这些信息决定是否调用该方法。

```java
@ToolDef(
    name = "searchUser",
    description = "用户搜索接口"
)
```

### 4.2 `@ToolParam`

参数级注解，用于描述单个参数：

```java
@ToolParam(name="uid", description="用户ID", required=true)
```

### 4.3 ToolScanner

扫描工具方法并生成 `Tool` 列表，是整个工具体系的入口。

关键方法：

```java
ToolScanner.scan(Object obj)
ToolScanner.scan(Class<?> clazz)
```

### 4.4 MethodTool

`Tool` 的具体实现，包装反射方法调用：

* 自动映射参数
* 处理静态与实例方法
* 生成 JSON Schema 提供给 LLM



## 5. 源码解析（设计与实现原理）

以下基于你提供的源码，从整体设计上解析 ToolScanner。

### 5.1 入口方法

```java
public static List<Tool> scan(Object object, String... methodNames)
public static List<Tool> scan(Class<?> clazz, String... methodNames)
```

两种扫描模式：

* **实例扫描**：扫描所有 `@ToolDef`，包括非静态方法
* **类扫描**：只扫描静态方法（非静态不能实例化）

### 5.2 核心扫描逻辑

所有逻辑进入 `doScan`：

```java
private static List<Tool> doScan(Class<?> clazz, Object object, String... methodNames)
```



</div>
