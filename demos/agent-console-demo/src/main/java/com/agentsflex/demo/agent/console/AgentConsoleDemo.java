/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent.console;

import com.agentsflex.agent.*;
import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.DefaultChatMemory;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用真实大模型演示持续对话、原生 ToolCall 和 Human-in-the-loop 的控制台程序。
 *
 * <p>业务代码维护 conversationId 和 ChatMemory，每条普通用户消息都携带历史消息创建新的 AgentTurn。
 * 模型直接回答时 Turn 一步完成；模型选择工具时 Runner 自动执行工具并继续调用模型；高风险工具会
 * 先进入审批等待状态，控制台收集人的决定后按 turnId 恢复原 Turn。</p>
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

        // Runner 只执行独立 Turn；会话标识和 ChatMemory 由业务代码维护。
        AgentRunner runner = AgentRunner.builder()
            .agentLoader(new InMemoryAgentLoader(agent))
            .build();
        runner.addEventListener(AgentConsoleDemo::printEvent);
        String conversationId = "console-" + UUID.randomUUID();
        ChatMemory memory = new DefaultChatMemory(conversationId);

        printWelcome(configuration, conversationId);
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            runConsole(reader, runner, agent, conversationId, memory);
        }
    }

    /** 持续读取控制台输入；每次循环表示一次新的正常对话轮次。 */
    private static void runConsole(BufferedReader reader, AgentRunner runner, Agent agent,
                                   String conversationId, ChatMemory memory) throws IOException {
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
                printHistory(memory);
                continue;
            }

            UserMessage userMessage = new UserMessage(input);
            try {
                AgentTurn turn = runner.run(agent, memory.getMessages(Integer.MAX_VALUE), userMessage,
                    AgentTurnOptions.builder()
                        .metadata("conversationId", conversationId)
                        .build());
                turn = handleBlockedTurn(reader, runner, turn);
                if (turn == null) {
                    System.out.println("对话已结束，尚未审批的 Turn 保留在等待状态。");
                    return;
                }
                if (turn.getStatus().isTerminal()) {
                    replaceMemory(memory, turn.getConversationHistory());
                }
                printTurnResult(turn);
            } catch (RuntimeException error) {
                System.err.println("本轮执行异常: " + error.getMessage());
            }
        }
    }

    /**
     * 处理 Turn 的外部等待状态。
     *
     * <p>审批和用户补充都恢复当前 turnId，不会创建新的 AgentTurn。循环结构允许一次任务中连续出现
     * 多个审批点。</p>
     */
    private static AgentTurn handleBlockedTurn(BufferedReader reader, AgentRunner runner,
                                             AgentTurn turn)
        throws IOException {
        AgentTurn current = turn;
        while (current.getStatus().isBlocked()) {
            if (current.getStatus() == AgentTurnStatus.WAITING_FOR_APPROVAL) {
                AgentResumeCommand decision = readApprovalDecision(reader, current);
                if (decision == null) {
                    return null;
                }
                current = runner.resume(current.getId(), decision);
                continue;
            }
            if (current.getStatus() == AgentTurnStatus.WAITING_FOR_USER) {
                System.out.println("\n[需要补充信息] " + current.getSuspension().getMessage());
                System.out.print("补充 > ");
                String value = reader.readLine();
                if (value == null || isExit(value)) {
                    return null;
                }
                current = runner.resume(current.getId(), AgentResumeCommand.userInput(value)
                    .withMetadata("source", "console"));
                continue;
            }

            // 子 Agent、延迟重试等状态通常由 Worker 或外部事件恢复，不应在控制台中盲目推进。
            System.out.println("Turn 正在等待外部事件: " + current.getStatus());
            return current;
        }
        return current;
    }

    /** 展示审批上下文并把人的选择转换为结构化恢复命令。 */
    private static AgentResumeCommand readApprovalDecision(BufferedReader reader, AgentTurn turn)
        throws IOException {
        AgentSuspension suspension = turn.getSuspension();
        ToolCall pending = findPendingCall(turn, suspension.getCorrelationId());
        System.out.println("\n-------------------- 人工审批 --------------------");
        System.out.println("Turn ID   : " + turn.getId());
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
            .toolApprovalPolicy((turn, call, tool) ->
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
            || type == AgentEventType.TURN_SUSPENDED
            || type == AgentEventType.TURN_RESUMED) {
            System.out.println("[事件] " + type + " turn=" + event.getTurnId());
        }
    }

    private static ToolCall findPendingCall(AgentTurn turn, String callId) {
        for (ToolCall call : turn.getPendingToolCalls()) {
            if (call != null && callId != null && callId.equals(call.getId())) {
                return call;
            }
        }
        return turn.getPendingToolCalls().isEmpty() ? null : turn.getPendingToolCalls().get(0);
    }

    private static void printTurnResult(AgentTurn turn) {
        if (turn.getStatus() == AgentTurnStatus.COMPLETED) {
            System.out.println("\n助手 > " + turn.getFinalOutput());
        } else if (turn.getStatus().isBlocked()) {
            System.out.println("\n助手 > Turn 仍在等待外部事件: " + turn.getStatus());
        } else {
            System.out.println("\n助手 > 本轮未正常完成，status=" + turn.getStatus()
                + ", error=" + (turn.getError() == null ? null : turn.getError().getMessage()));
        }
        System.out.println("[Turn] id=" + turn.getId() + ", iterations=" + turn.getIterationCount()
            + ", toolCalls=" + turn.getToolCallCount() + ", tokens=" + turn.getTotalTokens());
    }

    private static void printHistory(ChatMemory memory) {
        List<Message> messages = memory.getMessages(Integer.MAX_VALUE);
        System.out.println("\n当前会话共 " + messages.size() + " 条协议消息：");
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            System.out.println("  " + (i + 1) + ". " + message.getClass().getSimpleName()
                + "  " + abbreviate(message.getTextContent(), 120));
        }
    }

    /** 使用 Turn 返回的完整历史替换业务侧 Memory，避免重复追加已有消息。 */
    private static void replaceMemory(ChatMemory memory, List<Message> messages) {
        memory.clear();
        for (Message message : messages) {
            memory.addMessage(message);
        }
    }

    private static void printWelcome(DemoConfiguration configuration,
                                     String conversationId) {
        System.out.println("============================================================");
        System.out.println("Agents-Flex 真实模型 Agent 控制台 Demo");
        System.out.println("============================================================");
        System.out.println("model        : " + configuration.model);
        System.out.println("endpoint     : " + configuration.endpoint + configuration.requestPath);
        System.out.println("conversation : " + conversationId);
        printHelp();
    }

    private static void printHelp() {
        System.out.println("\n建议依次尝试：");
        System.out.println("  1. 你好，我叫小明。             （普通持续对话）");
        System.out.println("  2. 你还记得我叫什么吗？         （读取业务 ChatMemory）");
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
                environment(ENDPOINT_ENV, "https://api.deepseek.com"),
                environment(REQUEST_PATH_ENV, "/chat/completions"),
                environment(MODEL_ENV, "deepseek-v4-pro"));
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
