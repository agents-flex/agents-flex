---
title: Demo：持久化工具审批 Agent
description: 一个包含工具审批、Checkpoint、跨 Runner 恢复和事件读取的完整示例。
---

# Demo：持久化工具审批 Agent

<div v-pre>

## Demo 目标

这个示例实现一个生产发布 Agent。模型可以读取部署状态，但执行 `deploy_service` 前必须获得审批。第一次 Runner 执行到审批点后退出，第二个 Runner 使用共享 Store 恢复并完成任务。

示例使用内存 Store 便于阅读。部署到多进程环境时，应替换为数据库实现，但 Runner 的使用方式不变。

仓库中对应的离线可运行源码位于：

```text
demos/agent-demo/src/main/java/com/agentsflex/demo/agent/HumanApprovalAgentDemo.java
```

运行命令：

```bash
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=approval
```

源码使用脚本模型保证无需 API Key 即可运行，本页后续完整代码展示了替换为在线 ChatModel 后的同一架构。

## 完整代码

```java
import com.agentsflex.core.agent.Agent;
import com.agentsflex.core.agent.AgentResumeCommand;
import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.agent.AgentRunStatus;
import com.agentsflex.core.agent.AgentRunner;
import com.agentsflex.core.agent.event.AgentRunEvent;
import com.agentsflex.core.agent.event.InMemoryAgentRunEventStore;
import com.agentsflex.core.agent.registry.InMemoryAgentRegistry;
import com.agentsflex.core.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.agent.tool.InMemoryAgentToolRegistry;
import com.agentsflex.core.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DurableToolAgentDemo {

    public static void main(String[] args) {
        ChatModel chatModel = OpenAIChatConfig.builder()
            .apiKey(System.getenv("AI_API_KEY"))
            .model(System.getenv("AI_MODEL"))
            .buildModel();

        // Demo 使用 Map 模拟外部部署系统。真实工具应调用部署平台并传递幂等键。
        Map<String, String> deployments = new ConcurrentHashMap<>();

        Tool getDeployment = Tool.builder(
                "get_deployment",
                "查询服务当前部署版本")
            .addParameter(Parameter.builder()
                .name("service")
                .type("string")
                .description("服务名称")
                .required(true)
                .build())
            .function(arguments -> {
                String service = String.valueOf(arguments.get("service"));
                return deployments.getOrDefault(service, "尚未部署");
            })
            .build();

        Tool deployService = Tool.builder(
                "deploy_service",
                "将指定服务部署到生产环境")
            .addParameter(Parameter.builder()
                .name("service")
                .type("string")
                .description("服务名称")
                .required(true)
                .build())
            .addParameter(Parameter.builder()
                .name("version")
                .type("string")
                .description("待部署版本")
                .required(true)
                .build())
            .addParameter(Parameter.builder()
                .name("requestId")
                .type("string")
                .description("业务幂等键")
                .required(true)
                .build())
            .function(arguments -> {
                String service = String.valueOf(arguments.get("service"));
                String version = String.valueOf(arguments.get("version"));
                String requestId = String.valueOf(arguments.get("requestId"));

                // 生产代码应由部署平台按 requestId 保证重复调用返回同一结果。
                String result = "已部署 " + service + ":" + version
                    + ", requestId=" + requestId;
                deployments.put(service, result);
                return result;
            })
            .build();

        Agent releaseAgent = Agent.builder("release-agent")
            .id("release-agent")
            .version("1")
            .instructions(
                "你是发布助手。先确认服务和版本。"
                    + "查询可以直接执行；部署必须调用 deploy_service。"
                    + "requestId 使用用户提供的变更单号。")
            .chatModel(chatModel)
            .tool(getDeployment)
            .tool(deployService)
            .toolApprovalPolicy((run, call, tool) -> {
                if ("deploy_service".equals(call.getName())) {
                    return ToolApprovalDecision.requireApproval()
                        .code("PRODUCTION_DEPLOYMENT_REVIEW")
                        .message("生产发布需要人工批准")
                        .reason("部署工具会修改生产环境")
                        .build();
                }
                return ToolApprovalDecision.ALLOW;
            })
            .build();

        // 这些对象在 Demo 中由两个 Runner 共享，模拟持久化基础设施。
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InMemoryAgentRegistry agentRegistry = new InMemoryAgentRegistry();
        InMemoryAgentToolRegistry toolRegistry = new InMemoryAgentToolRegistry();
        InMemoryAgentRunEventStore eventStore = new InMemoryAgentRunEventStore();
        InMemoryAgentRunCommandStore commandStore = new InMemoryAgentRunCommandStore();
        InMemoryAgentArtifactStore artifactStore = new InMemoryAgentArtifactStore();

        // Runner A 创建任务并执行到审批点。
        AgentRunner runnerA = new AgentRunner(
            runStore, agentRegistry, toolRegistry, eventStore,
            commandStore, artifactStore);

        AgentRun waiting = runnerA.run(
            releaseAgent,
            "把 inventory-service 部署到 2.4.0，变更单号 CR-20260731-18"
        );

        if (waiting.getStatus() != AgentRunStatus.WAITING_FOR_APPROVAL) {
            throw new IllegalStateException(
                "预期等待审批，实际状态：" + waiting.getStatus());
        }

        String runId = waiting.getId();
        String callId = waiting.getSuspension().getCorrelationId();

        System.out.println("任务已暂停：" + runId);
        System.out.println("待审批调用：" + callId);

        // Runner B 代表另一个请求或进程。生产中 Registry 通常由 Spring 容器初始化。
        InMemoryAgentRegistry registryB = new InMemoryAgentRegistry();
        registryB.register(releaseAgent);

        AgentRunner runnerB = new AgentRunner(
            runStore, registryB, toolRegistry, eventStore,
            commandStore, artifactStore);

        runnerB.submitCommand(
            "approval-" + callId,
            runId,
            AgentResumeCommand.approveTool(callId)
        );
        List<AgentRun> processed;
        try (AgentWorker worker = new AgentWorker("release-worker", runnerB, 30_000)) {
            processed = worker.pollAndRun(10);
        }
        AgentRun completed = processed.get(0);

        System.out.println("最终状态：" + completed.getStatus());
        System.out.println("最终回答：" + completed.getFinalOutput());

        List<AgentRunEvent> events = eventStore.load(runId, 0, 100);
        for (AgentRunEvent event : events) {
            System.out.println(event.getSequence()
                + " " + event.getType()
                + " " + event.getAttributes());
        }
    }
}
```

## 关键观察点

### 工具在批准前没有执行

`runnerA.run()` 返回 `WAITING_FOR_APPROVAL` 时，部署 Map 仍为空。审批策略在 Tool Executor 之前运行，因此不会先执行再补审批。

### 恢复依赖稳定 Agent ID

Checkpoint 保存 `agentId=release-agent` 和 `agentVersion=1`，不保存 Java Lambda、ChatModel 或网络连接。Runner B 必须注册相同 ID 与版本的 Agent 定义，否则 `restore(runId)` 会失败。

### 工具需要跨进程可解析

当前示例共享 `InMemoryAgentToolRegistry`。真正的多进程部署中，每个进程都可以使用一致的启动配置注册静态工具，也可以让 Registry 根据 Snapshot 中的 `AgentToolReference` 从容器、MCP Server 或配置中心重新构造工具代理。

所谓“跨进程解析”不是序列化工具代码，而是持久化 `toolName`、binding 信息和 Tool metadata 的不可变引用，在新进程中重新绑定对应实现。引用中不保存客户端、连接和密钥。

### 审批命令绑定 ToolCall

```java
AgentResumeCommand.approveTool(callId)
```

如果 callId 与当前 Suspension 不一致，Runner 会拒绝命令。这可以防止旧审批页面批准新的工具调用。

## 改为异步 Worker 恢复

HTTP 审批接口通常只提交恢复命令：

```java
runnerB.submitResume(
    runId,
    AgentResumeCommand.approveTool(callId)
);
```

然后由 Worker 执行：

```java
try (AgentWorker worker = new AgentWorker("release-worker-01", runnerB, 30_000)) {
    worker.startPolling(1_000, 4);
    // 应用生命周期结束时关闭 Worker。
}
```

这样审批请求可以立即返回 `202 Accepted`，模型调用和部署操作在后台完成。

## 演示拒绝

把批准命令替换为：

```java
AgentRun rejected = runnerB.resume(
    runId,
    AgentResumeCommand.rejectTool(
        callId,
        "当前不在发布窗口"
    )
);
```

工具不会执行，模型会收到拒绝 ToolMessage，并生成面向用户的说明。

## 生产替换项

| Demo 组件 | 生产实现 |
| --- | --- |
| `InMemoryAgentRunStore` | 数据库或可靠 KV，实现 CAS 与 Lease |
| `InMemoryAgentRegistry` | Spring Bean、配置中心或版本化 Agent Catalog |
| `InMemoryAgentToolRegistry` | 每个 Worker 一致注册的工具目录 |
| `InMemoryAgentRunEventStore` | 追加式数据库表、日志系统或事件平台 |
| Map 部署工具 | 支持鉴权、超时、幂等和结果查询的部署 API |

## 建议验证

- 审批前部署工具执行次数为 0；
- 批准后执行次数为 1；
- 重复提交相同审批不会重复部署；
- 使用错误 callId 时恢复失败；
- Runner A 不再存在时 Runner B 仍能恢复；
- 事件序列包含暂停、恢复、工具开始、工具完成和 Run 完成；
- 达到预算或审批拒绝时不会产生部署副作用。

## 延伸阅读

- [Human-in-the-loop 场景](../scenarios/human-in-the-loop.md)
- [工具执行与审批](../tools-and-approval.md)
- [Checkpoint 与中断恢复](../checkpoint-resume.md)

</div>
