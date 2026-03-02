# Tool 工具构建



## 1. 概述

在 Agents-Flex 框架中，**Tool（工具）是大语言模型（LLM）调用外部能力的标准接口**。为了让开发者高效、安全、可维护地暴露业务能力给 LLM，框架提供了两种互补的工具构建机制：

- **`ToolScanner`**：通过注解自动扫描 Java 方法，适用于**静态、确定性、可复用**的工具。
- **`Tool.Builder`**：以编程方式动态构建 `Tool` 实例，适用于**运行时生成、Lambda 表达式、插件系统**等动态场景。

本文档系统介绍 **`ToolScanner` 与 `Tool.Builder` 的使用方法、设计原理、适用场景及最佳实践**。



## 2. Tool 的基本组成

```java
public interface Tool {

    String getName();

    String getDescription();

    Parameter[] getParameters();

    Object invoke(Map<String, Object> argsMap);
}
```

无论通过哪种方式构建，每个 `Tool` 都包含以下四个核心要素：

| 组成项        | 说明                                              |
|-|-------------------------------------------------|
| `name`      | 工具的唯一标识名，供 LLM 识别与调用                            |
| `description` | 工具的功能描述，用于 LLM 的工具选择决策                          |
| `parameters` | 参数定义列表（`Parameter[]`），描述输入结构（最终生成 JSON Schema）  |
| `invoke`    | 执行逻辑，类型为 `Map<String, Object>`  |

两种构建方式本质是**同一抽象的不同实现路径**。



## 3. ToolScanner：基于注解的静态工具构建

### 3.1 核心思想

`ToolScanner` 通过反射扫描标注了 `@ToolDef` 的 Java 方法，自动将其转换为 `Tool` 实例（具体为 `MethodTool`）。开发者只需编写普通业务方法并添加注解，即可让 LLM 调用。

> **适用场景**：工具逻辑稳定、参数结构明确、复用性强（如数据库查询、API 封装、通用服务）。

### 3.2 快速入门

#### 定义工具方法

```java
public class WeatherTools {

    @ToolDef(
        name = "getWeather",
        description = "获取指定城市的天气信息"
    )
    public String getWeather(
        @ToolParam(name = "city", description = "城市名称", required = true)
        String city
    ) {
        // 实际业务逻辑
        return "Sunny in " + city;
    }
}
```

#### 扫描并注册工具

```java
// 扫描实例（支持静态 + 非静态方法）
WeatherTools instance = new WeatherTools();
List<Tool> tools = ToolScanner.scan(instance);

// 仅扫描静态方法（传入 Class）
List<Tool> staticTools = ToolScanner.scan(WeatherTools.class);
```

> 扫描结果可直接加入 Agent 的工具列表，供 LLM 调用。

### 3.3 高级用法

#### 仅扫描指定方法

```java
List<Tool> tools = ToolScanner.scan(instance, "getWeather", "getForecast");
```

适用于权限控制、按需暴露等场景。

#### 静态工具类示例

```java
public class SystemTools {
    @ToolDef(description = "获取当前系统时间戳（毫秒）")
    public static long now() {
        return System.currentTimeMillis();
    }
}

List<Tool> tools = ToolScanner.scan(SystemTools.class); // 仅静态方法
```

#### 参数约束：枚举与必填

```java
@ToolDef(description = "执行搜索")
public String search(
    @ToolParam(name = "query", required = true) String query,
    @ToolParam(name = "mode", enums = {"fast", "accurate"}, required = true) String mode
) {
    return "Searching in " + mode + " mode for: " + query;
}
```

LLM 将获得精确的参数 schema，包括枚举值提示。

### 3.4 核心组件说明

| 组件 | 作用                                               |
|--|--------------------------------------------------|
| `@ToolDef` | 方法级注解，声明 `name`（可选，默认为方法名）和 `description`（必填）    |
| `@ToolParam` | 参数级注解，描述参数名、类型、描述、是否必填、枚举值等                      |
| `ToolScanner` | 工具扫描入口，提供 `scan(Object)` 和 `scan(Class<?>)` 两种模式 |
| `MethodTool` | `Tool` 的具体实现，内部通过反射调用原方法，自动处理参数映射                |

### 3.5 注意事项

- **方法可见性**：仅支持 `public` 方法。
- **参数类型**：建议使用基本类型或 `Map<String, Object>`，复杂对象需自行反序列化。
- **性能**：首次调用涉及反射，但后续调用已优化（无额外开销）。
- **线程安全**：若工具方法为非静态，需确保被扫描的实例线程安全。



## 4. Tool.Builder：基于编程的动态工具构建

### 4.1 核心思想

`Tool.Builder` 允许开发者以**链式调用方式**，在运行时构造任意 `Tool` 实例。执行逻辑可来自 Lambda、匿名类、闭包或配置驱动的函数。

> **适用场景**：动态能力暴露、插件系统、工作流节点包装、运行时配置生成工具。

### 4.2 快速入门：加法工具

```java
Tool addTool = Tool.builder()
    .name("add")
    .description("执行两个数字的加法")
    .addParameter(Parameter.builder()
        .name("a").type("number").description("第一个数字").required(true).build())
    .addParameter(Parameter.builder()
        .name("b").type("number").description("第二个数字").required(true).build())
    .function(args -> {
        int a = (int) args.get("a");
        int b = (int) args.get("b");
        return a + b;
    })
    .build();

// 调用
Object result = addTool.invoke(Map.of("a", 5, "b", 7)); // 12
```

### 4.3 进阶用法

#### 嵌套对象参数

```java
Parameter userParam = Parameter.builder()
    .name("user").type("object")
    .addChild(Parameter.builder().name("id").type("string").build())
    .addChild(Parameter.builder().name("age").type("number").build())
    .build();

Tool tool = Tool.builder()
    .name("processUser")
    .description("处理用户对象")
    .addParameter(userParam)
    .function(args -> {
        Map<String, Object> user = (Map<String, Object>) args.get("user");
        return "Processed: " + user.get("id");
    })
    .build();
```

#### 批量动态生成（插件系统）

```java
List<Tool> tools = toolConfigs.stream().map(cfg ->
    Tool.builder()
        .name(cfg.getName())
        .description(cfg.getDescription())
        .parameters(cfg.getParameters())
        .function(cfg.getHandler())
        .build()
).toList();
```

#### 工作流节点集成

```java
Tool nodeTool = Tool.builder()
    .name("run_workflow_node_12")
    .description("执行工作流节点12：用户校验")
    .function(context -> workflowEngine.execute("node_12", context))
    .build();
```

### 4.4 核心 API

| 方法 | 说明                   |
|--|----------------------|
| `name(String)` | 设置工具名                |
| `description(String)` | 设置描述                 |
| `addParameter(Parameter)` | 添加单个参数               |
| `parameters(Parameter...)` | 批量设置参数               |
| `function(Function<Map<String, Object>, Object>)` | 设置执行逻辑               |
| `build()` | 构建 `FunctionTool` 实例 |

`Parameter.Builder` 支持 `name`、`type`、`description`、`required`、`addChild` 等方法。



## 5. ToolScanner 与 Tool.Builder 对比

| 维度        | `ToolScanner` | `Tool.Builder` |
|-----------|-|-|
| **构建时机**  | 启动期 / 编译后 | 运行时 |
| **代码侵入性** | 需添加注解 | 无需修改业务类 |
| **灵活性**   | 低（依赖方法签名） | 高（任意函数） |
| **参数控制**  | 依赖 `@ToolParam` | 完全自定义 `Parameter` |
| **执行性能**  | 反射调用（已优化） | 直接函数调用 |
| **适用场景**  | 通用服务、稳定 API | 动态能力、插件、工作流 |
| **可测试性**  | 可直接单元测试原方法 | 需测试 `Function` 逻辑 |

> **建议**：
> - 通用业务能力 → `@ToolDef` + `ToolScanner`
> - 动态/配置化能力 → `Tool.Builder`



## 6. 最佳实践

### 6.1 工具命名与描述
- 名称使用小写 + 下划线（如 `send_email`）
- 描述清晰、具体，避免模糊词汇（如“处理数据”应改为“根据用户ID查询订单列表”）

### 6.2 错误处理
无论哪种方式，**工具不应抛出未处理异常**。建议返回结构化错误：

```java
// Tool.Builder 示例
.function(args -> {
    try {
        // 业务逻辑
    } catch (Exception e) {
        log.error("Tool 'xxx' failed", e);
        return Map.of("error", "操作失败: " + e.getMessage());
    }
})
```

对于 `ToolScanner`，可在方法内部捕获异常并返回错误对象。

### 6.3 日志与监控
- 记录工具调用入参与结果（敏感信息脱敏）
- 关键工具建议添加调用计数、耗时监控

### 6.4 与 LLM 的协同
- 参数 `description` 要符合 LLM 理解习惯（用自然语言）
- 枚举值 (`enums`) 能显著提升 LLM 调用准确性





## 7. 总结

- `ToolScanner` 提供**低代码、高可维护性**的静态工具构建方式，适合稳定业务能力。
- `Tool.Builder` 提供**极致灵活、动态可编程**的工具构建能力，适合插件化、配置驱动、工作流等高级场景。
- 两者可**无缝共存**于同一 Agent 系统，开发者应根据业务特性选择合适方式，或混合使用。

通过合理运用这两种机制，可构建出既稳定又灵活的 LLM 工具生态。
