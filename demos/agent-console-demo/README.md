# Agent Console Demo

该 Demo 使用真实的 OpenAI-compatible 大模型，在同一个控制台会话中演示：

- 普通持续对话；
- 模型原生 ToolCall 和工具结果回传；
- 高风险工具的人工批准或拒绝；
- 阻塞 Run 的恢复；
- 每轮独立 AgentRun 和共享 Conversation Memory。

先在仓库根目录编译：

```bash
mvn -pl demos/agent-console-demo -am install -DskipTests
```

设置模型连接参数。API Key 不应写入源码：

```bash
export AGENT_DEMO_API_KEY="your-api-key"
export AGENT_DEMO_MODEL="gpt-4o-mini"
```

使用其他 OpenAI-compatible 服务时可以继续配置：

```bash
export AGENT_DEMO_ENDPOINT="https://your-provider.example.com"
export AGENT_DEMO_REQUEST_PATH="/v1/chat/completions"
export AGENT_DEMO_MODEL="your-tool-calling-model"
```

运行控制台：

```bash
mvn -f demos/agent-console-demo/pom.xml exec:java
```

进入控制台后可依次输入：

```text
你好，我叫小明。
你还记得我叫什么吗？
上海现在几点？
帮我创建一个高优先级登录故障工单。
```

最后一条输入会在工具真正执行之前展示 ToolCall 参数，并等待输入 `y` 或 `n`。
