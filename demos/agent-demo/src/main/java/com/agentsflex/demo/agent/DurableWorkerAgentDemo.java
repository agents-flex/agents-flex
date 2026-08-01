/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentRetryPolicy;
import com.agentsflex.agent.AgentRun;
import com.agentsflex.agent.AgentRunStatus;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentWorker;
import com.agentsflex.agent.event.AgentRunEvent;
import com.agentsflex.agent.event.InMemoryAgentRunEventStore;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.tool.AgentToolInvocation;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 演示失败工具的持久化重试，以及 Worker 通过 Lease 领取到期任务。 */
public final class DurableWorkerAgentDemo {

    private DurableWorkerAgentDemo() {
    }

    public static void main(String[] args) {
        run();
    }

    static void run() {
        DemoSupport.section("Demo 3 - 持久化重试、Worker 与 Lease");

        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> firstKey = new AtomicReference<>();
        // 该工具第一次抛出临时异常，第二次成功，用于模拟可恢复的外部服务故障。
        Tool unstableTool = Tool.builder("sync_inventory", "同步库存")
            .metadata("sideEffect", true)
            .function(arguments -> {
                AgentToolInvocation invocation = AgentToolInvocation.current();
                String key = invocation.getIdempotencyKey();
                if (firstKey.get() == null) {
                    firstKey.set(key);
                }
                DemoSupport.require(firstKey.get().equals(key),
                    "重试必须沿用同一个 ToolCall 幂等键");
                if (attempts.incrementAndGet() == 1) {
                    throw new RuntimeException("模拟库存服务暂时不可用");
                }
                return "库存同步完成";
            })
            .build();

        DemoScriptedChatModel model = new DemoScriptedChatModel()
            .enqueue(prompt -> DemoSupport.toolCalls(
                new ToolCall("inventory-call-1", "sync_inventory", "{}")))
            .enqueue(prompt -> new AiMessage("库存同步任务已恢复并完成。"));

        Agent agent = Agent.builder("inventory-agent")
            .id("inventory-agent")
            .version("1")
            .chatModel(model)
            .tool(unstableTool)
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder()
                    .maxRetries(2)
                    // Demo 使用 0 延迟，让 Worker 可以立即领取。生产环境通常使用指数退避。
                    .initialDelayMillis(0)
                    .maxDelayMillis(0)
                    .build())
                .build())
            .build();

        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InMemoryAgentLoader agentLoader = new InMemoryAgentLoader(agent);
        InMemoryAgentRunEventStore eventStore = new InMemoryAgentRunEventStore();
        AgentRunner runner = new AgentRunner(runStore, agentLoader, eventStore);

        AgentRun scheduled = runner.run(agent, "同步库存");
        DemoSupport.printRun(scheduled);
        DemoSupport.require(scheduled.getStatus() == AgentRunStatus.RETRY_SCHEDULED,
            "首次失败后应保存为可调度重试状态");
        DemoSupport.require(scheduled.getPendingToolCalls().size() == 1,
            "重试应保留原 ToolCall，而不是重新请求模型决策");

        // claimRunnable 会原子写入 leaseOwner 和 leaseUntil。Worker 推进到终止或再次阻塞后释放租约。
        // 多个 Worker 并发轮询时，只有成功取得 Lease 的 Worker 能处理当前 Snapshot 版本。
        List<AgentRun> processed;
        try (AgentWorker worker = new AgentWorker("inventory-worker-01", runner, 30_000)) {
            processed = worker.pollAndRun(10);
        }
        DemoSupport.require(processed.size() == 1, "Worker 应领取一个到期任务");
        AgentRun completed = processed.get(0);

        DemoSupport.printRun(completed);
        System.out.println("attempts     : " + attempts.get());
        System.out.println("idempotency  : " + firstKey.get());
        DemoSupport.require(completed.getStatus() == AgentRunStatus.COMPLETED,
            "Worker 重试后应完成");
        DemoSupport.require(model.getCallCount() == 2,
            "原工具重试不能重复产生模型决策回合");

        System.out.println("important events:");
        for (AgentRunEvent event : eventStore.load(completed.getId(), 0, 100)) {
            if (event.getType().name().contains("RETRY")
                || event.getType().name().contains("COMPLETED")) {
                System.out.println("  " + event.getSequence() + " " + event.getType());
            }
        }
    }
}
