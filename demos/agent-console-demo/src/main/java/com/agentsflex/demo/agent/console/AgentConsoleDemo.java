/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent.console;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentBudget;
import com.agentsflex.agent.AgentConversation;
import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentInvocationContext;
import com.agentsflex.agent.AgentResumeCommand;
import com.agentsflex.agent.AgentRun;
import com.agentsflex.agent.AgentRunOptions;
import com.agentsflex.agent.AgentRunStatus;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentSuspension;
import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用真实大模型演示持续对话、原生 ToolCall 和 Human-in-the-loop 的控制台程序。
 *
 * <p>每条普通用户消息都会在同一个 AgentConversation 中创建新的 AgentRun。模型直接回答时 Run
 * 一步完成；模型选择工具时 Runner 自动执行工具并继续调用模型；高风险工具会先进入审批等待状态，
 * 控制台收集人的决定后恢复原 Run。</p>
 */
public final class AgentConsoleDemo {

    private static final String API_KEY_ENV = "AGENT_DEMO_API_KEY";
    private static final String ENDPOINT_ENV = "AGENT_DEMO_ENDPOINT";
    private static final String REQUEST_PATH_ENV = "AGENT_DEMO_REQUEST_PATH";
    private static final String MODEL_ENV = "AGENT_DEMO_MODEL";

    private AgentConsoleDemo() {
    }

    public static void main(String[] args) throws IOException {
        DemoConfiguration configuration = DemoConfiguration.fromEnvironment();
        ChatModel chatModel = createChatModel(configuration);
        AtomicInteger ticketSequence = new AtomicInteger(1000);

        Tool currentTime = createCurrentTimeTool();
        Tool createTicket = createTicketTool(ticketSequence);
        Agent agent = createAgent(chatModel, currentTime, createTicket);

        // Runner 是可复用的执行引擎；Conversation 才保存这个控制台用户的持续对话 Memory。
        AgentRunner runner = AgentRunner.builder()
            .agentLoader(new InMemoryAgentLoader(agent))
            .build();
        runner.addEventListener(AgentConsoleDemo::printEvent);
        AgentConversation conversation = AgentConversation.create("console-" + UUID.randomUUID(), agent);

        printWelcome(configuration, conversation);
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            runConsole(reader, runner, conversation);
        }
    }

    /** 持续读取控制台输入；每次循环表示一次新的正常对话轮次。 */
    private static void runConsole(BufferedReader reader, AgentRunner runner,
                                   AgentConversation conversation) throws IOException {
        while (true) {
            System.out.print("\n你 > ");
            String input = reader.readLine();
            if (input == null || isExit(input)) {
                System.out.println("对话已结束。");
                return;
            }
            if (input.trim().isEmpty()) {
                continue;
            }
            if ("/help".equalsIgnoreCase(input.trim())) {
                printHelp();
                continue;
            }
            if ("/history".equalsIgnoreCase(input.trim())) {
                printHistory(conversation);
                continue;
            }

            UserMessage userMessage = new UserMessage(input);
            AgentRunOptions options = AgentRunOptions.builder()
                // sessionId 和 requestId 只作用于本轮调用链，不会代替 Conversation 的消息历史。
                .invocationContext(AgentInvocationContext.builder()
                    .sessionId(conversation.getId())
                    .requestId(UUID.randomUUID().toString())
                    .userId("console-user")
                    .build())
                .build();

            try {
                AgentRun run = runner.run(conversation, userMessage, options);
                run = handleBlockedRun(reader, runner, conversation, run);
                if (run == null) {
                    System.out.println("对话已结束，尚未审批的 Run 保留在等待状态。");
                    return;
                }
                printRunResult(run);
            } catch (RuntimeException error) {
                System.err.println("本轮执行异常: " + error.getMessage());
            }
        }
    }

    /**
     * 处理 Run 的外部等待状态。
     *
     * <p>审批和用户补充都恢复当前 activeRunId，不会创建新的 AgentRun。循环结构允许一次任务中连续
     * 出现多个审批点。</p>
     */
    private static AgentRun handleBlockedRun(BufferedReader reader, AgentRunner runner,
                                             AgentConversation conversation, AgentRun run)
        throws IOException {
        AgentRun current = run;
        while (current.getStatus().isBlocked()) {
            if (current.getStatus() == AgentRunStatus.WAITING_FOR_APPROVAL) {
                AgentResumeCommand decision = readApprovalDecision(reader, current);
                if (decision == null) {
                    return null;
                }
                current = runner.resume(conversation, decision);
                continue;
            }
            if (current.getStatus() == AgentRunStatus.WAITING_FOR_USER) {
                System.out.println("\n[需要补充信息] " + current.getSuspension().getMessage());
                System.out.print("补充 > ");
                String value = reader.readLine();
                if (value == null || isExit(value)) {
                    return null;
                }
                current = runner.resume(conversation, AgentResumeCommand.userInput(value)
                    .withMetadata("source", "console"));
                continue;
            }

            // 子 Agent、延迟重试等状态通常由 Worker 或外部事件恢复，不应在控制台中盲目推进。
            System.out.println("Run 正在等待外部事件: " + current.getStatus());
            return current;
        }
        return current;
    }

    /** 展示审批上下文并把人的选择转换为结构化恢复命令。 */
    private static AgentResumeCommand readApprovalDecision(BufferedReader reader, AgentRun run)
        throws IOException {
        AgentSuspension suspension = run.getSuspension();
        ToolCall pending = findPendingCall(run, suspension.getCorrelationId());
        System.out.println("\n-------------------- 人工审批 --------------------");
        System.out.println("Run ID   : " + run.getId());
        System.out.println("工具     : " + (pending == null ? "未知" : pending.getName()));
        System.out.println("参数     : " + (pending == null ? "{}" : pending.getArguments()));
        System.out.println("说明     : " + suspension.getMessage());
        System.out.println("风险信息 : " + suspension.getMetadata());
        System.out.print("是否批准执行？[y/N] > ");
        String answer = reader.readLine();
        if (answer == null || isExit(answer)) {
            return null;
        }

        String callId = suspension.getCorrelationId();
        if (isApproved(answer)) {
            return AgentResumeCommand.approveTool(callId)
                .withMetadata("approverId", "console-user")
                .withMetadata("approvalChannel", "terminal");
        }
        return AgentResumeCommand.rejectTool(callId, "控制台用户拒绝执行")
            .withMetadata("approverId", "console-user")
            .withMetadata("approvalChannel", "terminal");
    }

    /** 当前时间工具没有副作用，因此审批策略会允许 Runner 立即执行。 */
    private static Tool createCurrentTimeTool() {
        return Tool.builder("get_current_time", "查询指定时区的真实当前日期和时间")
            .addParameter(Parameter.builder()
                .name("zoneId")
                .type("string")
                .description("IANA 时区，例如 Asia/Shanghai；不确定时使用 Asia/Shanghai")
                .required(true)
                .build())
            .metadata("sideEffect", false)
            .metadata("category", "read-only")
            .function(arguments -> {
                String zoneValue = String.valueOf(arguments.get("zoneId"));
                try {
                    ZoneId zone = ZoneId.of(zoneValue);
                    return ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (DateTimeException error) {
                    return "无效时区: " + zoneValue + "，请使用 IANA ZoneId";
                }
            })
            .build();
    }

    /** 创建工单工具模拟真实写操作，只有审批通过后函数体才会执行。 */
    private static Tool createTicketTool(AtomicInteger sequence) {
        return Tool.builder("create_support_ticket", "在支持系统中创建工单，这是会产生业务副作用的写操作")
            .addParameter(Parameter.builder()
                .name("title")
                .type("string")
                .description("简短明确的工单标题")
                .required(true)
                .build())
            .addParameter(Parameter.builder()
                .name("priority")
                .type("string")
                .description("优先级：LOW、MEDIUM、HIGH")
                .required(true)
                .build())
            .addParameter(Parameter.builder()
                .name("description")
                .type("string")
                .description("根据当前对话整理的工单详情")
                .required(true)
                .build())
            .metadata("sideEffect", true)
            .metadata("riskLevel", "MEDIUM")
            .metadata("approvalType", "HUMAN")
            .function(arguments -> {
                String ticketId = "TICKET-" + sequence.incrementAndGet();
                System.out.println("\n[工具已执行] 创建工单 " + ticketId + "，参数=" + arguments);
                return "工单创建成功，ticketId=" + ticketId;
            })
            .build();
    }

    /** 配置 Agent 的模型指令、工具、审批规则和执行预算。 */
    private static Agent createAgent(ChatModel chatModel, Tool currentTime, Tool createTicket) {
        return Agent.builder("console-assistant")
            .id("console-assistant")
            .version("1")
            .instructions(
                "你是一个支持持续对话的中文助手。请结合完整会话历史理解代词和省略信息。"
                    + "普通问候或知识问题直接回答。用户询问当前日期或时间时，必须调用 get_current_time，"
                    + "不能依靠训练数据猜测。只有用户明确要求创建、提交或登记支持工单时，才调用 "
                    + "create_support_ticket；调用前从对话中整理标题、优先级和描述。"
                    + "工具返回后必须根据真实结果回答，绝不能虚构工具已经执行。")
            .chatModel(chatModel)
            .tool(currentTime)
            .tool(createTicket)
            .toolApprovalPolicy((run, call, tool) ->
                Boolean.TRUE.equals(tool.getMetadata().get("sideEffect"))
                    ? ToolApprovalDecision.requireApproval()
                        .code("CONSOLE_WRITE_APPROVAL")
                        .message("该工具会写入支持系统，需要人工确认")
                        .reason("工具 metadata.sideEffect=true")
                        .metadata("riskLevel", tool.getMetadata().get("riskLevel"))
                        .build()
                    : ToolApprovalDecision.ALLOW)
            .executionPolicy(AgentExecutionPolicy.builder()
                .maxIterations(8)
                .budget(AgentBudget.builder()
                    .maxToolCalls(8)
                    .maxTotalTokens(100_000)
                    .maxDurationMillis(120_000)
                    .build())
                .build())
            .build();
    }

    /** 创建真实的 OpenAI-compatible ChatModel。 */
    private static ChatModel createChatModel(DemoConfiguration configuration) {
        return OpenAIChatConfig.builder()
            .apiKey(configuration.apiKey)
            .endpoint(configuration.endpoint)
            .requestPath(configuration.requestPath)
            .model(configuration.model)
            .supportTool(true)
            .observabilityEnabled(false)
            .logEnabled(false)
            .buildModel();
    }

    /** 只打印对学习执行过程有帮助的实时事件，避免控制台被所有 Snapshot 事件淹没。 */
    private static void printEvent(AgentEvent event) {
        AgentEventType type = event.getType();
        if (type == AgentEventType.MODEL_STARTED
            || type == AgentEventType.MODEL_COMPLETED
            || type == AgentEventType.TOOL_STARTED
            || type == AgentEventType.TOOL_COMPLETED
            || type == AgentEventType.TOOL_APPROVAL_REQUESTED
            || type == AgentEventType.RUN_SUSPENDED
            || type == AgentEventType.RUN_RESUMED) {
            System.out.println("[事件] " + type + " run=" + event.getRunId());
        }
    }

    private static ToolCall findPendingCall(AgentRun run, String callId) {
        for (ToolCall call : run.getPendingToolCalls()) {
            if (call != null && callId != null && callId.equals(call.getId())) {
                return call;
            }
        }
        return run.getPendingToolCalls().isEmpty() ? null : run.getPendingToolCalls().get(0);
    }

    private static void printRunResult(AgentRun run) {
        if (run.getStatus() == AgentRunStatus.COMPLETED) {
            System.out.println("\n助手 > " + run.getFinalOutput());
        } else if (run.getStatus().isBlocked()) {
            System.out.println("\n助手 > Run 仍在等待外部事件: " + run.getStatus());
        } else {
            System.out.println("\n助手 > 本轮未正常完成，status=" + run.getStatus()
                + ", error=" + (run.getError() == null ? null : run.getError().getMessage()));
        }
        System.out.println("[Run] id=" + run.getId() + ", iterations=" + run.getIterationCount()
            + ", toolCalls=" + run.getToolCallCount() + ", tokens=" + run.getTotalTokens());
    }

    private static void printHistory(AgentConversation conversation) {
        List<Message> messages = conversation.getMessages();
        System.out.println("\n当前 Conversation 共 " + messages.size() + " 条协议消息：");
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            System.out.println("  " + (i + 1) + ". " + message.getClass().getSimpleName()
                + "  " + abbreviate(message.getTextContent(), 120));
        }
    }

    private static void printWelcome(DemoConfiguration configuration,
                                     AgentConversation conversation) {
        System.out.println("============================================================");
        System.out.println("Agents-Flex 真实模型 Agent 控制台 Demo");
        System.out.println("============================================================");
        System.out.println("model        : " + configuration.model);
        System.out.println("endpoint     : " + configuration.endpoint + configuration.requestPath);
        System.out.println("conversation : " + conversation.getId());
        printHelp();
    }

    private static void printHelp() {
        System.out.println("\n建议依次尝试：");
        System.out.println("  1. 你好，我叫小明。             （普通持续对话）");
        System.out.println("  2. 你还记得我叫什么吗？         （读取 Conversation Memory）");
        System.out.println("  3. 上海现在几点？               （自动调用只读工具）");
        System.out.println("  4. 帮我创建一个高优先级登录故障工单。（触发人工审批）");
        System.out.println("命令：/history 查看协议消息，/help 查看帮助，/exit 退出。");
    }

    private static boolean isApproved(String value) {
        String answer = value == null ? "" : value.trim();
        return "y".equalsIgnoreCase(answer)
            || "yes".equalsIgnoreCase(answer)
            || "是".equals(answer)
            || "同意".equals(answer)
            || "批准".equals(answer);
    }

    private static boolean isExit(String value) {
        return value != null && ("/exit".equalsIgnoreCase(value.trim())
            || "exit".equalsIgnoreCase(value.trim())
            || "quit".equalsIgnoreCase(value.trim()));
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= maxLength ? oneLine
            : oneLine.substring(0, maxLength) + "...";
    }

    /** 模型连接参数只从环境变量读取，避免 API Key 进入源码、命令历史或 Git。 */
    private static final class DemoConfiguration {
        private final String apiKey;
        private final String endpoint;
        private final String requestPath;
        private final String model;

        private DemoConfiguration(String apiKey, String endpoint, String requestPath, String model) {
            this.apiKey = apiKey;
            this.endpoint = endpoint;
            this.requestPath = requestPath;
            this.model = model;
        }

        private static DemoConfiguration fromEnvironment() {
            String apiKey = environment(API_KEY_ENV, System.getenv("OPENAI_API_KEY"));
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalStateException("请设置环境变量 " + API_KEY_ENV
                    + "（也兼容 OPENAI_API_KEY），不要把 API Key 写入源码");
            }
            return new DemoConfiguration(
                apiKey,
                environment(ENDPOINT_ENV, "https://api.openai.com"),
                environment(REQUEST_PATH_ENV, "/v1/chat/completions"),
                environment(MODEL_ENV, "gpt-4o-mini"));
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
