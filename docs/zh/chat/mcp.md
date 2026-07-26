---
title: MCP 调用
description: 连接本地或远程 MCP Server，并将其工具转换为 Agents-Flex Tool 参与对话。
---

# MCP 调用

<div v-pre>

## 概述

MCP（Model Context Protocol）让应用用统一协议连接文件系统、浏览器、数据库和第三方服务。Agents-Flex 的 `agents-flex-mcp` 模块负责管理 MCP 客户端，并把服务端返回的 Tool Schema 适配为框架的 `Tool`。

```text
MCP Server -> McpSyncClient -> McpTool -> Prompt -> ChatModel
```

模型看到的仍是普通 Tool。第一次响应产生 `ToolCall` 后，应用使用标准 Tool 执行闭环，不需要为 MCP 编写另一套对话流程。

::: warning JDK 要求
MCP 模块要求 JDK 17 或更高版本。
:::

## 适用场景

- 接入已有 MCP Server，避免为文件、Git、数据库等能力重复开发 Java Tool。
- 同一应用需要管理多个本地进程或远程 MCP 服务。
- 希望第三方能力通过统一 Tool、拦截器和审计链进入 Agent。
- MCP Server 的工具清单会独立升级，客户端按协议读取最新 Schema。

如果能力只存在于当前 Java 应用且接口稳定，直接构建 [Tool](./tool-build.md) 通常更简单。

## 快速开始

### 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-mcp</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 配置 MCP Server

在 `src/main/resources/mcp-servers.json` 配置一个 stdio 服务：

```json
{
  "mcpServers": {
    "everything": {
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-everything"],
      "env": {}
    }
  }
}
```

`McpClientManager` 第一次初始化时会自动读取这个类路径资源。可用 JVM 参数更换资源名：

```bash
java -Dmcp.config.servers-resource=my-mcp-servers.json -jar app.jar
```

### 在对话中使用

```java
McpClientManager manager = McpClientManager.getInstance();
Tool echo = manager.getMcpTool("everything", "echo");
if (echo == null) {
    throw new IllegalStateException("MCP tool not found: echo");
}

MemoryPrompt prompt = new MemoryPrompt();
prompt.addUserMessage("使用 echo 原样返回 hello");
prompt.addTool(echo);

AiMessageResponse response = chatModel.chat(prompt);
if (response.hasToolCalls()) {
    prompt.addMessage(response.getMessage());
    prompt.addMessages(response.executeToolCallsAndGetToolMessages());
    response = chatModel.chat(prompt);
}
```

`getMcpTool(serverName, toolName)` 会懒初始化客户端并调用 `listTools()`。服务不存在时抛出 `IllegalArgumentException`；服务存在但工具名不存在时返回 `null`。

## 配置结构

```json
{
  "inputs": [],
  "mcpServers": {
    "server-name": {
      "transport": "stdio",
      "type": "stdio",
      "command": "node",
      "args": ["server.js"],
      "env": {"API_TOKEN": "${input:api-token}"},
      "url": "http://localhost:8080/mcp",
      "headers": {"Authorization": "Bearer token"}
    }
  }
}
```

| 字段 | 用途 |
| --- | --- |
| `transport` / `type` | 传输类型；优先读取 `transport` |
| `command`、`args`、`env` | 启动 stdio 子进程 |
| `url`、`headers` | 连接 SSE 或 Streamable HTTP 服务 |

当前 `ServerSpec` 不包含 `cwd`。需要指定工作目录时，应在启动脚本中处理，不能在 JSON 中配置一个不会生效的字段。

### 输入占位符

`env` 中形如 `${input:api-token}` 的值，会从 JVM System Property `mcp.input.api-token` 读取：

```bash
java -Dmcp.input.api-token=... -jar app.jar
```

没有对应属性时当前实现会解析为空字符串。`inputs` 只承载描述信息，Manager 不会交互式询问用户。

## 传输方式

### stdio

未设置 `transport`、`type` 和 `url` 时默认为 `stdio`：

```json
{
  "command": "python",
  "args": ["mcp_server.py"],
  "env": {"MODE": "production"}
}
```

应用进程负责启动和关闭子进程。命令必须在部署环境的 PATH 中可用。

### SSE

支持 `sse`、`http-sse` 和 `ssehttp`：

```json
{
  "transport": "http-sse",
  "url": "http://localhost:8080/sse",
  "headers": {"Authorization": "Bearer ..."}
}
```

### Streamable HTTP

支持 `http`、`streamable`、`http-stream` 和 `streamablehttp`：

```json
{
  "transport": "http-stream",
  "url": "http://localhost:8080/mcp"
}
```

只配置 `url` 且没有类型时，`getTransportOrType()` 会选择 `http`。

## 生命周期

`McpClientManager` 是进程内单例：

- 第一次获取某个 Client 时才建立连接并执行 MCP initialize。
- 后台单线程每 10 秒检查已初始化客户端；单个描述符最短 ping 间隔为 5 秒。
- MCP 请求超时在当前实现中固定为 20 秒。
- JVM shutdown hook 会调用 `close()`；容器应用也可以在自己的销毁回调中显式关闭。

```java
McpClientManager manager = McpClientManager.getInstance();
boolean online = manager.isClientOnline("everything");
manager.reconnect("everything");
```

客户端尚未初始化时，`isClientOnline(...)` 会返回 false，这不等同于配置不存在。

## 动态加载与重载

```java
McpClientManager manager = McpClientManager.getInstance();
manager.registerFromFile(Path.of("/opt/app/mcp-servers.json"));
manager.registerFromResource("tenant-mcp.json");
manager.registerFromJson(json);
```

注册同名服务时，Manager 会关闭旧描述符并替换它。静态 `reloadConfig()` 会关闭全部连接、清空注册表，然后重新读取默认或 JVM 参数指定的类路径资源。

::: warning 并发重载
不要在正在执行 MCP Tool 时重载或替换对应服务。配置变更应在维护窗口或有调用排空机制的发布流程中完成。
:::

## McpTool 的结果语义

`McpTool` 把 MCP input schema 的顶层 properties 转换为 `Parameter`。调用结果：

- 单个文本内容返回 `String`。
- 多个或非文本内容返回 MCP `Content` 列表。
- 空内容返回 `null`。
- 服务返回 `isError=true` 或调用异常时抛出 `McpCallException`。

复杂嵌套 Schema 的完整约束不一定都能映射为 Agents-Flex `Parameter`，上线前应检查模型实际收到的工具描述。

## 生产建议

1. 不要把真实密钥提交到 `mcp-servers.json`；通过受控启动参数、环境注入或密钥服务生成配置。
2. 远程 MCP 使用 TLS，并在网关限制目标地址和认证 Header。
3. MCP Tool 与本地 Tool 一样需要权限校验、参数校验和审计。
4. 对 stdio 服务监控子进程资源；对远程服务配置外部超时、限流和熔断。
5. 工具数量很多时结合 [ToolSearch](./tool-search.md)，避免每轮发送完整工具目录。

## 常见问题

### 为什么找不到 MCP Client？

检查服务名是否与 `mcpServers` 的 key 一致，以及配置资源是否位于运行时 classpath。自动加载失败只会记录日志，随后 `getMcpClient(...)` 会报告未注册。

### 为什么配置了 `cwd` 但没有生效？

当前配置类没有该字段。请使用包装脚本切换目录后再启动 Server。

### 为什么在线状态一开始是 false？

Client 是懒初始化的。先获取 Client 或 Tool 建立连接，再检查状态。

## 下一步

- [Tool 工具调用](./tool.md)
- [Tool 拦截器](./tool-interceptor.md)
- [ToolSearch 工具搜索](./tool-search.md)

</div>
