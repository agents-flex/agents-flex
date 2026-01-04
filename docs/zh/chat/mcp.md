# MCP 帮助文档
<div v-pre>



> 本文档面向 Java 开发者，介绍如何在 Agents-Flex 项目中集成并使用 Model Context Protocol (MCP) 客户端，以调用外部 MCP 工具服务。
>
> **注意事项：MCP 模块要求必须 JDK 17+，** 其他模块要求 JDK 8+。因此，如果您的需要用到 MCP 模块，请务必确认你的项目已配置 JDK 17+。


## 1. 概述

Agents-Flex 提供了对 **MCP（Model Context Protocol）** 协议的原生支持，通过 `McpClientManager` 统一管理多个 MCP 服务实例，自动加载配置、健康检查、按需初始化，并将 MCP 工具无缝转换为 Agents-Flex 的 `Tool` 接口，供 Agent 调用。

MCP 客户端模块支持以下传输方式：
- `stdio`：子进程标准输入/输出通信（默认）
- `http-sse`：基于 Server-Sent Events 的 HTTP 流
- `http-stream`：基于分块传输编码的 HTTP 流



## 2. 快速开始

### 2.1 添加依赖
确保你的项目已引入 Agents-Flex 核心模块及 MCP 客户端模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-mcp</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 2.2 配置 MCP 服务

在 `src/main/resources/` 目录下创建配置文件 `mcp-servers.json`（默认路径）：

```json
{
  "mcpServers": {
      "everything": {
          "command": "npx",
          "args": [
              "-y",
              "@modelcontextprotocol/server-everything"
          ]
      },
      "weather-api": {
        "transport": "http-sse",
        "url": "http://localhost:8081/mcp"
      }
  }
}
```

> ⚠️ 默认会自动加载 `mcp-servers.json`。如需自定义配置路径，可通过 JVM 参数指定：
> ```bash
> -Dmcp.config.servers-resource=my-mcp-config.json
> ```

### 2.3 在代码中调用 MCP 工具

```java
// 获取 MCP 客户端（懒加载）
McpSyncClient client = McpClientManager.getInstance().getMcpClient("calculator");

// 或直接获取封装后的 Tool（推荐）
Tool weatherTool = McpClientManager.getInstance().getMcpTool("weather-api", "getWeather");
if (weatherTool != null) {
    Object result = weatherTool.invoke(Map.of("city", "Beijing"));
    System.out.println(result);
}
```

该 `Tool` 可直接注册到 Agent 的 `ToolManager` 中使用。

以下是一个使用示例 和 单元测试代码：

```java
@Test
@DisplayName("Test call tool - 测试工具调用")
void testCallTool() {
    mcpClientManager.registerFromResource("mcp-servers.json");

    Tool mcpTool = mcpClientManager.getMcpTool("everything", "add");
    System.out.println(mcpTool);

    ToolExecutor toolExecutor = new ToolExecutor(mcpTool
        , new ToolCall("add", "add", "{\"a\":1,\"b\":2}"));

    Object result = toolExecutor.execute();

    assertEquals("The sum of 1 and 2 is 3.", result);

    System.out.println(result);
}
```



## 3. 核心组件说明

### 3.1 `McpClientManager`（单例）

- **作用**：MCP 客户端的中央注册与生命周期管理器。
- **特性**：
    - 单例模式，线程安全
    - 自动从 classpath 加载 `mcp-servers.json`
    - 启动后台线程定期健康检查（默认 10 秒）
    - 注册 JVM Shutdown Hook，确保优雅关闭
    - 支持运行时重载配置（`McpClientManager.reloadConfig()`）

#### 常用方法：

| 方法                                               | 说明                                  |
|--------------------------------------------------|-------------------------------------|
| `getInstance()`                                  | 获取单例实例                              |
| `getMcpClient(String name)`                      | 获取原始 `McpSyncClient`                |
| `getMcpTool(String clientName, String toolName)` | 获取封装为 Agents-Flex `Tool` 的 MCP 工具   |
| `isClientOnline(String name)`                    | 检查客户端是否活跃                           |
| `reconnect(String name)`                         | 强制重连指定客户端                           |
| `close()`                                        | 关闭所有客户端（`AutoCloseable`）            |



### 3.2 `McpConfig`

配置映射类，对应 JSON 结构：

```java
public class McpConfig {
    private Map<String, ServerSpec> mcpServers;

    public static class ServerSpec {
        private String transport = "stdio";  // "stdio" | "http-sse" | "http-stream"
        private String command;              // stdio 模式下启动命令
        private List<String> args;           // 命令参数
        private Map<String, String> env;     // 环境变量（支持 ${input:xxx} 占位符）
        private String url;                  // HTTP 模式下的服务地址
    }
}
```

> **环境变量占位符支持**：
> 若 `env` 中值形如 `${input:api_key}`，则会尝试从系统属性 `mcp.input.api_key` 读取：
> ```java
> System.setProperty("mcp.input.api_key", "your-secret-key");
> ```



### 3.3 `McpTool`

- **作用**：将 MCP 协议中的 `Tool` 自动适配为 Agents-Flex 的 `com.agentsflex.core.model.chat.tool.Tool` 接口。
- **功能**：
    - 自动解析 `inputSchema` 生成 `Parameter[]`
    - 支持 `enum`、`required`、`description`、`default` 等字段
    - `invoke()` 方法内部调用 `callTool()` 并处理错误
    - 单文本结果自动解包为 `String`，多内容返回 `List<Content>`

> ✅ 此设计使得 MCP 工具可直接用于 Agent 的工具调用流程，无需额外适配。


以下代码将 MCP 工具注册为 Agents-Flex `Tool`：

```java
@Test
@DisplayName("Test call tool - 测试工具调用")
void testCallTool() {
    mcpClientManager.registerFromResource("mcp-servers.json");

    // 将 MCP 工具注册为 Agents-Flex Tool
    Tool mcpTool = mcpClientManager.getMcpTool("everything", "add");

    Map<String, Object> input = new HashMap<>();
    input.put("a", 1);
    input.put("b", 2);

    Object result = mcpTool.execute(input);

    assertEquals("The sum of 1 and 2 is 3.", result);

    System.out.println(result);
}
```


### 3.4 `McpClientDescriptor`

- **作用**：封装一个 MCP 服务的连接状态、传输层、客户端实例。
- **特性**：
    - 懒初始化（首次 `getMcpClient()` 时创建）
    - 支持多线程安全初始化（避免重复创建）
    - 内置 `ping` 健康检查机制（`isAlive()`）
    - 使用 `CloseableTransport` 抽象底层通信（如进程、HTTP 连接）



## 4. 高级用法

### 4.1 动态注册 MCP 服务

```java
String jsonConfig = """
{
  "mcpServers": {
    "dynamic-service": {
      "transport": "http-stream",
      "url": "https://api.example.com/mcp"
    }
  }
}
""";

McpClientManager.getInstance().registerFromJson(jsonConfig);
```

支持从 JSON 字符串、文件路径（`registerFromFile`）或 classpath 资源（`registerFromResource`）注册。

### 4.2 运行时重载配置

```java
// 关闭所有现有连接并重新加载 mcp-servers.json
McpClientManager.reloadConfig();
```

适用于配置热更新场景。

### 4.3 自定义传输工厂

当前支持的传输类型由 `McpTransportFactory` 决定。如需扩展（如 WebSocket），可实现新工厂并修改 `McpClientDescriptor.getTransportFactory()` 的 switch 分支。



## 5. 异常处理

- **MCP 调用失败**：抛出 `McpCallException`（RuntimeException），包含工具名和错误详情。
- **初始化失败**：`getMcpClient()` 或 `getMcpTool()` 可能抛出 `RuntimeException`，日志中会记录详细错误。
- **客户端离线**：`isClientOnline()` 返回 `false`，但不影响 `getMcpClient()`（会尝试重建）。

建议在生产环境中捕获 `McpCallException` 并降级处理。



## 6. 资源管理与关闭

- `McpClientManager` 实现了 `AutoCloseable`，推荐在应用关闭时调用 `close()`。
- JVM Shutdown Hook 已自动注册，通常无需手动关闭。
- 若在容器环境（如 Spring）中使用，可将其注册为 `@Bean(destroyMethod = "close")`。



## 7. 示例：完整工作流

```java
// 1. 系统属性注入敏感信息（可选）
System.setProperty("mcp.input.weather_key", "secret123");

// 2. 初始化（自动加载 mcp-servers.json）
McpClientManager manager = McpClientManager.getInstance();

// 3. 获取工具
Tool tool = manager.getMcpTool("weather-service", "getCurrentWeather");
if (tool == null) {
    throw new IllegalStateException("Weather tool not found");
}

// 4. 调用
Map<String, Object> args = new HashMap<>();
args.put("location", "Shanghai");
args.put("unit", "celsius");

try {
    Object result = tool.invoke(args);
    System.out.println("Weather: " + result);
} catch (McpCallException e) {
    log.error("MCP tool call failed", e);
}
```



## 8. 注意事项

- **线程安全**：所有公开方法均为线程安全。
- **性能**：工具调用为同步阻塞，建议在异步上下文中使用。
- **日志**：使用 SLF4J，可通过日志级别控制调试信息。
- **兼容性**：依赖 MCP 官方 `io.modelcontextprotocol:java-client`，确保版本匹配。


</div>
