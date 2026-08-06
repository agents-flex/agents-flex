# Agent Console Demo

该 Demo 使用真实的 OpenAI-compatible 大模型，在同一个控制台会话中演示：

- 普通持续对话；
- 模型按需创建顺序任务计划，并由父 Turn 汇总子任务结果；
- 模型原生 ToolCall 和工具结果回传；
- 工具执行时请求 JSON Schema 表单，并在提交后恢复原工具；
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
请帮我收集会议安排信息。
帮我创建一个高优先级登录故障工单。
分别查询上海和东京当前时间，并比较时差给出会议建议。
```

收集会议信息时，模型会调用 `request_user_input(meeting_request)`；Schema 不发送给模型，而是由
Runner 保存并交给控制台渲染。创建工单时，`prepare_support_ticket` 会通过
`AgentFormRequiredException` 请求“受影响系统、影响范围、错误提示”表单。控制台根据
Schema 逐项读取输入，提交后 Runner 从头恢复同一个准备工具；资料整理完成后，模型再调用
`create_support_ticket`，控制台展示最终 ToolCall 参数并等待输入 `y` 或 `n`。

为了稳定演示规划能力，Demo 要求包含两个或更多独立工具调用的请求必须先调用内置规划工具；跨时区
请求会顺序执行两个查询
子 Turn，由父 Agent 比较结果并给出建议。控制台会输出计划和任务事件。Runner 通过
`chatMemoryProvider` 分页读取模型历史，并按稳定消息 ID 增量写回本轮消息，不会清空或重写 ChatMemory。
该 Demo 的 Turn Store 和 ChatMemory 都是进程内实现，退出程序后不会保留状态。
