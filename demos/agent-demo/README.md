# Agent Demo

该模块使用确定性的脚本模型演示现代 Agent 运行时，不需要配置 API Key。

先在仓库根目录编译：

```bash
mvn -pl demos/agent-demo -am install -DskipTests
```

运行全部场景：

```bash
mvn -f demos/agent-demo/pom.xml exec:java
```

运行单个场景：

```bash
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=tool
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=approval
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=worker
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=planning
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=runtime
```

| 参数 | 场景 |
| --- | --- |
| `tool` | 模型原生 ToolCall、ToolMessage、预算和工具幂等上下文 |
| `approval` | 工具审批、Snapshot、跨 Runner 恢复和统一事件监听 |
| `worker` | 工具失败、持久化重试、Worker 领取和 Lease |
| `planning` | 任务拆分、进度查询和专业子 Agent 调度 |
| `runtime` | Middleware、AgentEvent、上下文压缩和工具进度 |

`DemoScriptedChatModel` 只负责返回预先配置的模型消息，使 Demo 在离线环境中也能稳定运行。接入真实模型时，只需替换 Agent Builder 中的 `chatModel`。
