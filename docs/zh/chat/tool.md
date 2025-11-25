# 工具调用/ Tool / Function Calling
<div v-pre>


## 概述

Agents-Flex 的**工具调用（Tool Calling）** 机制允许大语言模型（LLM）**安全、可控地调用外部函数**，实现：
- **扩展能力**：访问数据库、调用 API、执行计算
- **精准交互**：结构化参数传递与结果反馈
- **安全隔离**：通过拦截器实现权限控制、日志审计

Agents-Flex 框架提供完整的工具调用生命周期管理：
1. **工具定义** → 2. **LLM 请求** → 3. **参数解析** → 4. **拦截执行** → 5. **结果返回**


## 核心组件

### 1. `Tool` 接口：工具定义
```java
public interface Tool {
    String getName();           // 工具唯一标识（如 "getWeather"）
    String getDescription();    // 工具描述（供 LLM 理解用途）
    Parameter[] getParameters(); // 参数定义（类型、描述、是否必填等）
    Object invoke(Map<String, Object> argsMap); // 执行逻辑
}
```
> ✅ 开发者需实现此接口，或使用注解自动注册（推荐）

---

### 2. `ToolExecutor` 工具执行器
```java
public class ToolExecutor {
    public Object execute(); // 触发拦截链并执行工具
    public void addInterceptor(ToolInterceptor interceptor); // 添加实例级拦截器
}
```
- 自动构建**责任链**：全局拦截器 → 用户拦截器 → 实际调用
- 管理**执行上下文**（`ToolContext`）

---

### 3. `ToolInterceptor` 工具执行拦截器
```java
public interface ToolInterceptor {
    Object intercept(ToolContext context, ToolChain chain) throws Exception;
}
```
- 实现日志、权限、监控等横切逻辑
- 可通过 `GlobalToolInterceptors` 注册全局拦截器

---

### 4. `ToolContext` 工具执行上下文
```java
public class ToolContext {
    public Tool getTool();         // 当前工具
    public ToolCall getToolCall(); // LLM 请求的调用详情（含参数）
    public Map<String, Object> getArgsMap(); // 解析后的参数
    public void setAttribute(String key, Object value); // 传递临时数据
}
```
- 贯穿整个拦截链
- 通过 `ToolContextHolder.currentContext()` 获取（线程安全）


## 工具（Tool）的定义方式

### 方式一：注解驱动（推荐）
通过 `@ToolDef` 和 `@ToolParam` 自动注册方法为工具。

#### 示例：天气查询工具
```java
public class WeatherService {

    @ToolDef(name = "getWeather", description = "获取指定城市的天气信息")
    public WeatherResult getWeather(
        @ToolParam(name = "city", description = "城市名称", required = true) String city,
        @ToolParam(name = "unit", description = "温度单位", enums = {"celsius", "fahrenheit"}) String unit
    ) {
        // 实际业务逻辑
        return weatherClient.query(city, unit);
    }
}
```

#### 注册到 Prompt
```java
UserMessage user = new UserMessage("北京今天天气如何？");
user.addToolsFromObject(new WeatherService()); // 自动扫描 @ToolDef 方法

SimplePrompt prompt = new SimplePrompt();
prompt.setUserMessage(user);
```

> 💡 **注解说明**：
> - `@ToolDef.name()`：工具唯一标识（默认为方法名）
> - `@ToolParam.required`：参数是否必填
> - `@ToolParam.enums`：枚举值约束（LLM 会优先选择）

---

### 方式二：手动实现 `Tool` 接口
适用于复杂逻辑或动态工具。

```java
public class CalculatorTool extends BaseTool {
    public CalculatorTool() {
        setName("calculate");
        setDescription("执行数学计算");
        setParameters(new Parameter[]{
            new Parameter("expression", "数学表达式，如 '2+3*4'", "string", true)
        });
    }

    @Override
    public Object invoke(Map<String, Object> argsMap) {
        String expr = (String) argsMap.get("expression");
        return evaluateExpression(expr); // 自定义计算逻辑
    }
}
```


## 工具调用流程

### 1. LLM 请求工具调用

```java
// 用户提问 + 注册工具
UserMessage userMsg = new UserMessage("计算 15 乘以 85");
userMsg.addTool(new CalculatorTool());

MemoryPrompt prompt = new MemoryPrompt();
prompt.addMessages(userMsg);

// 调用 LLM
AiMessageResponse response = chatModel.chat(prompt);

// 检查是否有工具调用请求
if (response.hasToolCalls()) {
    // 执行并生成结果消息
    List<ToolMessage> results = response.executeToolCallsAndGetToolMessages();
    prompt.addMessages(results);

    //重新发起 chat
    chatModel.chat(prompt);
}
```

### 2. 执行过程（拦截链示例）
```
[全局日志拦截器] → [权限校验拦截器] → [实际工具 invoke()]
```

### 拦截器示例：记录调用日志
```java
public class ToolLoggingInterceptor implements ToolInterceptor {
    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        String toolName = context.getTool().getName();
        Map<String, Object> args = context.getArgsMap();

        System.out.println("▶ 调用工具: " + toolName + ", 参数: " + args);

        long start = System.currentTimeMillis();
        try {
            Object result = chain.proceed(context);
            System.out.println("✅ 工具返回: " + result);
            return result;
        } finally {
            long duration = System.currentTimeMillis() - start;
            System.out.println("⏱️ 耗时: " + duration + "ms");
        }
    }
}

// 注册为全局拦截器
GlobalToolInterceptors.addInterceptor(new ToolLoggingInterceptor());
```


## 拦截器使用

### 1. 全局拦截器
适用于统一的安全策略：
```java
// 权限控制
GlobalToolInterceptors.addInterceptor(new PermissionInterceptor());

// 参数校验
GlobalToolInterceptors.addInterceptor(new ValidationInterceptor());
```

### 2. 实例级拦截器
针对特定工具的定制逻辑：
```java
ToolExecutor executor = new ToolExecutor(tool, toolCall);
executor.addInterceptor(new SensitiveDataMaskInterceptor());
Object result = executor.execute();
```

### 3. 上下文传递
在拦截器间共享数据：
```java
// 拦截器 A：设置 traceId
context.setAttribute("traceId", UUID.randomUUID().toString());

// 拦截器 B：读取 traceId
String traceId = context.getAttribute("traceId");
```


## 工具调用结果处理

### `AiMessageResponse` 辅助方法
| 方法 | 说明 |
|------|------|
| `hasToolCalls()` | 判断是否存在工具调用请求 |
| `getToolExecutors()` | 获取可执行的 `ToolExecutor` 列表 |
| `executeToolCallsAndGetResults()` | 执行并返回原始结果 |
| `executeToolCallsAndGetToolMessages()` | 执行并生成标准 `ToolMessage` |

#### 生成 ToolMessage 示例
```java
// 自动将结果转为 JSON 字符串（非字符串/数字类型）
ToolMessage msg = new ToolMessage();
msg.setToolCallId("call_abc123"); // 必须匹配 AiMessage 中的 ID
msg.setContent("{\"temperature\": 22, \"unit\": \"celsius\"}");
```


## 最佳实践

1. **工具命名规范**
    - 使用动词开头：`getWeather`, `createOrder`, `sendEmail`
    - 保持全局唯一性

2. **参数最小化**
    - 仅暴露必要参数
    - 使用 `required = true` 标记关键参数

3. **错误处理**
    - 工具内部捕获异常，返回结构化错误信息
    - 避免抛出未处理异常导致流程中断

4. **拦截器职责分离**
    - 全局拦截器：日志、监控、基础安全
    - 实例拦截器：业务特定逻辑（如数据脱敏）

5. **性能敏感工具异步化**
    - 长耗时操作（如文件处理）建议返回“任务已提交”，通过轮询获取结果



</div>
