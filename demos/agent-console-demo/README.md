# Agent Console Demo

该 Demo 使用真实的 OpenAI-compatible 大模型，在同一个控制台会话中演示：

- 普通持续对话；
- 模型原生 ToolCall 和工具结果回传；
- 高风险工具的人工批准或拒绝；
- 阻塞 Turn 的恢复；
- 每轮独立 AgentTurn，以及 Runner 与业务 conversationId、ChatMemory 的增量同步。

先在仓库根目录编译：

```bash
mvn -pl demos/agent-console-demo -am install -DskipTests
```

设置模型连接参数。API Key 不应写入源码：

```bash
export AGENT_DEMO_API_KEY="your-api-key"
export AGENT_DEMO_MODEL="gpt-4o-mini"
```

默认使用 `https://api.openai.com/v1/chat/completions`；API Key 也兼容从 `OPENAI_API_KEY` 读取。

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

最后一条输入会在工具真正执行之前展示 ToolCall 参数，并等待输入 `y` 或 `n`。Runner 通过
`chatMemoryProvider` 分页读取模型历史，并按稳定消息 ID 增量写回本轮消息，不会清空或重写 ChatMemory。
该 Demo 的 Turn Store 和 ChatMemory 都是进程内实现，退出程序后不会保留状态。
