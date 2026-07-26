---
title: Tool 构建
description: 使用注解扫描、Map Builder 和类型化 Builder 构建可供大模型调用的工具。
---

# Tool 构建

## 概述

Tool 的定义质量直接影响模型能否选对能力、填对参数。Agents-Flex 提供三条构建路径，它们最终都实现同一个 `Tool` 接口：

| 方式 | 适合场景 | 特点 |
| --- | --- | --- |
| `@ToolDef` + `ToolScanner` | 已存在的稳定 Java 服务 | 方法签名就是参数来源 |
| Map Builder | 动态插件、配置化能力 | 使用 `Map<String, Object>` 接收参数 |
| 类型化 Builder | 参数较复杂且希望强类型 | 将参数反序列化为输入类 |

## 适用场景

- 把订单查询、库存检查等现有 Service 方法开放给 Agent。
- 根据租户套餐或插件配置动态生成工具。
- 用嵌套对象、数组和枚举描述复杂业务输入。
- 为 MCP、工作流节点或远程 API 包装统一的 `Tool` 接口。

## 快速开始

稳定业务代码优先使用注解扫描：

```java
public class OrderTools {
    @ToolDef(name = "get_order", description = "根据订单号查询订单状态")
    public OrderView getOrder(
        @ToolParam(name = "orderNo", description = "订单号", required = true)
        String orderNo
    ) {
        return orderService.find(orderNo);
    }
}

SimplePrompt prompt = new SimplePrompt("查询订单 A1001");
prompt.addToolsFromObject(new OrderTools());
AiMessageResponse response = chatModel.chat(prompt);
```

`Prompt.addToolsFromObject(...)` 内部调用 `ToolScanner.scan(object)`；传入 `Class` 时只扫描带 `@ToolDef` 的静态方法。

## Tool 的四个组成部分

```java
public interface Tool {
    String getName();
    String getDescription();
    Parameter[] getParameters();
    Object invoke(Map<String, Object> argsMap);
}
```

- `name` 是协议中的唯一标识，建议使用稳定的英文小写下划线名称。
- `description` 应说明何时使用、返回什么，不要只写“查询数据”。
- `parameters` 会被序列化为模型看到的 Schema。
- `invoke` 是可信边界，仍需校验模型生成的参数。

## 注解扫描

`ToolScanner.scan(instance, methodNames...)` 会扫描对象类及其有效父类中带 `@ToolDef` 的方法，并生成 `JavaMethodTool`。指定方法名可以只暴露允许的能力：

```java
List<Tool> readOnlyTools = ToolScanner.scan(
    new OrderTools(),
    "getOrder",
    "listOrders"
);
```

同一签名只会扫描一次。传入 `OrderTools.class` 时，非静态方法会被忽略。

参数可声明枚举约束：

```java
@ToolDef(name = "search_order", description = "按状态查询订单")
public List<OrderView> search(
    @ToolParam(
        name = "status",
        description = "订单状态",
        enums = {"created", "paid", "shipped"},
        required = true
    ) String status
) {
    return orderService.search(status);
}
```

## Map Builder

运行时才知道工具定义时，可使用 `Tool.builder()` 返回的 `MapBuilder`：

```java
Tool add = Tool.builder("add", "计算两个整数之和")
    .addParameter(Parameter.builder()
        .name("a").type("integer").description("第一个整数")
        .required(true).build())
    .addParameter(Parameter.builder()
        .name("b").type("integer").description("第二个整数")
        .required(true).build())
    .function(args ->
        ((Number) args.get("a")).intValue()
            + ((Number) args.get("b")).intValue())
    .build();
```

数值经过 JSON 解析后不应强转为某个固定包装类型，使用 `Number` 更稳妥。

## 类型化 Builder

对于复杂输入，可以让框架把参数 Map 转换为 Java 对象：

```java
public class CreateTicketInput {
    private String title;
    private String priority;
    // getter / setter
}

Tool createTicket = Tool.builder(
        "create_ticket",
        CreateTicketInput.class,
        input -> ticketService.create(input.getTitle(), input.getPriority())
    )
    .description("创建客服工单")
    .build();
```

类型化工具由 `TypedFunctionTool` 执行，参数通过 Fastjson2 转换为输入类型。输入类应能被正常反序列化。

## 复杂 Parameter

对象使用 `children`，数组使用 `itemsParameter`：

```java
Parameter address = Parameter.builder()
    .name("address")
    .type("object")
    .required(true)
    .addChild(Parameter.builder()
        .name("city").type("string").required(true).build())
    .addChild(Parameter.builder()
        .name("street").type("string").required(true).build())
    .build();

Parameter tags = Parameter.builder()
    .name("tags")
    .type("array")
    .itemsParameter(Parameter.builder().type("string").build())
    .build();
```

`required`、`enums` 和 `defaultValue` 是 Schema 描述，不应替代服务端校验。

## 如何选择

- 能直接修改业务类，且工具稳定：使用注解，代码最少。
- 工具来自数据库、插件或租户配置：使用 Map Builder。
- 参数结构复杂并会在业务层继续传递：使用类型化 Builder。
- 需要完全自定义行为：实现 `Tool` 或继承 `BaseTool`。

## 生产建议

1. 工具名保持唯一和稳定，改名会影响已有提示词与调用日志。
2. 描述同时写清使用条件和边界，例如“只查询，不修改订单”。
3. 只暴露必要参数；租户 ID、当前用户等可信信息应由上下文或拦截器注入。
4. 工具对象可能被多个请求复用，实例字段和所依赖 Service 必须满足并发要求。
5. 在注册模型之前先直接调用 `invoke(...)` 或原方法做单元测试。

## 常见问题

### 为什么 `ToolScanner.scan(SomeClass.class)` 没有结果？

Class 形式只扫描静态方法。扫描实例方法请传入对象。

### 方法必须是 public 吗？

扫描器会查找带注解的方法并通过反射调用。为了避免模块访问和反射权限问题，工具入口应设计为 public。

### Builder 会自动校验必填参数吗？

`Parameter` 主要用于生成 Schema。业务执行前仍应显式校验缺失值、类型、范围和权限。

## 下一步

- [Tool 工具调用](./tool.md)：完成从 ToolCall 到最终回答的闭环。
- [Tool 拦截器](./tool-interceptor.md)：集中处理权限和审计。
- [ToolGroup 工具组](./tool-group.md)：按请求暴露一组工具。
