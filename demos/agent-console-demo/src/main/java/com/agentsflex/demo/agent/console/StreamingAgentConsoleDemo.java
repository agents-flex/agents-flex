/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent.console;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentBudget;
import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentResumeCommand;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentSuspension;
import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentTurnOptions;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.message.AgentFormMessage;
import com.agentsflex.agent.tool.AgentFormDefinition;
import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.AgentUserInputTool;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.ChatMemoryProvider;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 使用真实大模型演示持续对话、原生 ToolCall 和 Human-in-the-loop 的控制台程序。
 *
 * <p>业务代码维护 conversationId 和 ChatMemory，Runner 按配置的消息窗口读取模型历史，并把本轮新增
 * 消息幂等投影回业务 ChatMemory。模型直接回答时 Turn 一步完成；模型选择工具时 Runner 自动执行工具
 * 并继续调用模型；工具可以在副作用前请求结构化表单，控制台按 Schema 收集数据并恢复原调用；复杂
 * 目标可拆成顺序子任务；高风险工具会先进入审批等待状态，控制台收集人的决定后按根 turnId 恢复
 * 实际阻塞的 Turn。</p>
 */
public final class StreamingAgentConsoleDemo {

    private static final Set<String> STREAMED_TURNS = ConcurrentHashMap.newKeySet();

    private static final String API_KEY_ENV = "AGENT_DEMO_API_KEY";
    private static final String ENDPOINT_ENV = "AGENT_DEMO_ENDPOINT";
    private static final String REQUEST_PATH_ENV = "AGENT_DEMO_REQUEST_PATH";
    private static final String MODEL_ENV = "AGENT_DEMO_MODEL";
    private static final int HISTORY_DISPLAY_LIMIT = 50;

    private StreamingAgentConsoleDemo() {
    }

    public static void main(String[] args) throws IOException {
        DemoConfiguration configuration = DemoConfiguration.fromEnvironment();
        ChatModel chatModel = createChatModel(configuration);
        AtomicInteger ticketSequence = new AtomicInteger(1000);

        Tool currentTime = createCurrentTimeTool();
        AgentFormDefinition meetingRequestForm = createMeetingRequestForm();
        Tool reserveMeetingRoom = createMeetingReservationTool();
        AgentFormDefinition ticketDetailsForm = createTicketDetailsForm();
        Tool prepareTicket = createTicketPreparationTool(ticketDetailsForm);
        Tool createTicket = createTicketTool(ticketSequence);
        Agent agent = createAgent(chatModel, currentTime, meetingRequestForm,
            reserveMeetingRoom, prepareTicket, createTicket);

        String conversationId = "console-" + UUID.randomUUID();
        ChatMemory memory = new DefaultChatMemory(conversationId);

        // 业务系统按 conversationId 提供 ChatMemory；Runner 只保存 Turn 状态，不拥有会话生命周期。
        ChatMemoryProvider memoryProvider =
            id -> conversationId.equals(id) ? memory : null;

        AgentRunner runner = AgentRunner.builder()
            .agentLoader(new InMemoryAgentLoader(agent))
            .chatMemoryProvider(memoryProvider)
            .build();

        runner.addEventListener(StreamingAgentConsoleDemo::printEvent);

        printWelcome(configuration, conversationId);
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            runConsole(reader, runner, agent, conversationId, memory);
        }
    }

    /**
     * 持续读取控制台输入；每次循环表示一次新的正常对话轮次。
     */
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
                AgentTurn turn = runner.run(agent.getId(), conversationId, userMessage,
                    AgentTurnOptions.builder()
                        .streaming(true)
                        .metadata("requestId", UUID.randomUUID().toString())
                        .metadata("userId", "console-user")
                        .build());
                turn = handleBlockedTurn(reader, runner, turn);
                if (turn == null) {
                    System.out.println("对话已结束。当前 Demo 使用内存 Store，未完成 Turn 会随进程退出丢失。");
                    return;
                }
                printTurnResult(turn);
            } catch (RuntimeException error) {
                System.err.println("本轮执行异常 [" + error.getClass().getSimpleName()
                    + "]: " + error.getMessage());
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
            AgentTurn active = activeBlockedTurn(runner, current);
            if (active.getStatus() == AgentTurnStatus.WAITING_FOR_APPROVAL) {
                AgentResumeCommand decision = readApprovalDecision(reader, active);
                if (decision == null) {
                    return null;
                }
                // 使用当前 turnId 恢复等待中的审批。
                current = runner.resume(current.getId(), decision);
                continue;
            }
            if (active.getStatus() == AgentTurnStatus.WAITING_FOR_USER) {
                AgentSuspension suspension = requireSuspension(active);
                AgentResumeCommand input = readUserInput(reader, active, suspension);
                if (input == null) {
                    return null;
                }
                current = runner.resume(current.getId(), input);
                continue;
            }

            // 延迟重试等状态通常由 Worker 或外部事件恢复，不应在控制台中盲目推进。
            System.out.println("Turn 正在等待外部事件: " + active.getStatus());
            return current;
        }
        return current;
    }

    /**
     * 根据 Suspension 是否携带表单 Schema，选择结构化表单或纯文本恢复协议。
     */
    private static AgentResumeCommand readUserInput(BufferedReader reader, AgentTurn turn,
                                                    AgentSuspension suspension)
        throws IOException {
        Map<String, Object> schema = mapValue(suspension.getMetadata().get("schema"));
        String callId = suspension.getCorrelationId();
        if (!schema.isEmpty() && callId != null && !callId.trim().isEmpty()) {
            Map<String, Object> values = readForm(reader, turn, suspension, schema);
            return values == null ? null
                : AgentResumeCommand.userInput(callId, values)
                    .withMetadata("submittedBy", "console-user")
                    .withMetadata("source", "console-form");
        }

        System.out.println("\n[需要补充信息] " + suspension.getMessage());
        while (true) {
            System.out.print("补充 > ");
            String value = reader.readLine();
            if (value == null || isExit(value)) return null;
            if (!value.trim().isEmpty()) {
                return AgentResumeCommand.userInput(value)
                    .withMetadata("source", "console");
            }
            System.out.println("补充信息不能为空，请重新输入，或输入 /exit 退出。");
        }
    }

    /**
     * 使用 JSON Schema 的 properties、required 和 enum 渲染简单控制台表单。
     */
    private static Map<String, Object> readForm(BufferedReader reader, AgentTurn turn,
                                                AgentSuspension suspension,
                                                Map<String, Object> schema)
        throws IOException {
        Map<String, Object> properties = mapValue(schema.get("properties"));
        if (properties.isEmpty()) {
            throw new IllegalStateException("表单 Schema 缺少 properties");
        }
        List<?> required = schema.get("required") instanceof List
            ? (List<?>) schema.get("required") : Collections.emptyList();
        System.out.println("\n-------------------- 表单输入 --------------------");
        System.out.println("Turn ID : " + turn.getId());
        System.out.println("Form    : " + suspension.getMetadata().get("formKey"));
        System.out.println("标题    : " + textValue(schema.get("title"), suspension.getMessage()));
        System.out.println("说明    : 输入 /exit 可退出；带 * 的字段为必填项。");

        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String fieldName = entry.getKey();
            Map<String, Object> field = mapValue(entry.getValue());
            boolean fieldRequired = required.contains(fieldName);
            while (true) {
                printFieldPrompt(fieldName, field, fieldRequired);
                String input = reader.readLine();
                if (input == null || isExit(input)) return null;
                input = input.trim();
                if (input.isEmpty()) {
                    if (!fieldRequired) break;
                    System.out.println("该字段不能为空，请重新输入。");
                    continue;
                }
                List<?> allowed = field.get("enum") instanceof List
                    ? (List<?>) field.get("enum") : Collections.emptyList();
                if (!allowed.isEmpty() && !containsText(allowed, input)) {
                    System.out.println("请输入允许值之一: " + allowed);
                    continue;
                }
                try {
                    values.put(fieldName, parseFieldValue(input, field.get("type")));
                    break;
                } catch (IllegalArgumentException error) {
                    System.out.println(error.getMessage());
                }
            }
        }
        System.out.println("[表单提交] " + values);
        return values;
    }

    private static void printFieldPrompt(String fieldName, Map<String, Object> field,
                                         boolean required) {
        String title = textValue(field.get("title"), fieldName);
        Object description = field.get("description");
        Object allowed = field.get("enum");
        if (description != null) {
            System.out.println("  提示: " + description);
        }
        System.out.print(title + (required ? " *" : "")
            + (allowed instanceof List ? " " + allowed : "") + " > ");
    }

    private static Object parseFieldValue(String input, Object type) {
        String valueType = type == null ? "string" : String.valueOf(type);
        try {
            if ("integer".equals(valueType)) return Long.valueOf(input);
            if ("number".equals(valueType)) return Double.valueOf(input);
            if ("boolean".equals(valueType)) {
                if ("true".equalsIgnoreCase(input) || "是".equals(input)) return true;
                if ("false".equalsIgnoreCase(input) || "否".equals(input)) return false;
                throw new IllegalArgumentException("请输入 true/false 或 是/否。");
            }
            return input;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("请输入有效的 " + valueType + " 数值。");
        }
    }

    private static boolean containsText(List<?> values, String input) {
        for (Object value : values) {
            if (input.equals(String.valueOf(value))) return true;
        }
        return false;
    }

    /**
     * 返回当前阻塞 Turn。
     */
    private static AgentTurn activeBlockedTurn(AgentRunner runner, AgentTurn root) {
        return root;
    }

    /**
     * 展示审批上下文并把人的选择转换为结构化恢复命令。
     */
    private static AgentResumeCommand readApprovalDecision(BufferedReader reader, AgentTurn turn)
        throws IOException {
        AgentSuspension suspension = requireSuspension(turn);
        String callId = suspension.getCorrelationId();
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalStateException("审批等待状态缺少 toolCall correlationId");
        }
        ToolCall pending = findPendingCall(turn, suspension.getCorrelationId());
        System.out.println("\n-------------------- 人工审批 --------------------");
        System.out.println("Turn ID   : " + turn.getId());
        System.out.println("工具     : " + (pending == null ? "未知" : pending.getName()));
        System.out.println("参数     : " + (pending == null ? "{}" : pending.getArguments()));
        System.out.println("说明     : " + suspension.getMessage());
        System.out.println("风险信息 : " + suspension.getMetadata());
        while (true) {
            System.out.print("是否批准执行？[y/n] > ");
            String answer = reader.readLine();
            if (answer == null || isExit(answer)) return null;
            if (isApproved(answer)) {
                return AgentResumeCommand.approveTool(callId)
                    .withMetadata("approverId", "console-user")
                    .withMetadata("approvalChannel", "terminal");
            }
            if (isRejected(answer)) {
                return AgentResumeCommand.rejectTool(callId, "控制台用户拒绝执行")
                    .withMetadata("approverId", "console-user")
                    .withMetadata("approvalChannel", "terminal");
            }
            System.out.println("请输入 y/yes/同意 或 n/no/拒绝，也可以输入 /exit 退出。");
        }
    }

    /**
     * 当前时间工具没有副作用，因此审批策略会允许 Runner 立即执行。
     */
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

    /**
     * 定义工具运行时请求的故障详情表单。Schema 不发送给模型，Runner 会将它保存到暂停快照。
     */
    private static AgentFormDefinition createTicketDetailsForm() {
        Map<String, Object> affectedSystem = new LinkedHashMap<>();
        affectedSystem.put("type", "string");
        affectedSystem.put("title", "受影响系统");
        affectedSystem.put("description", "例如统一登录系统、管理后台或移动端");

        Map<String, Object> impactScope = new LinkedHashMap<>();
        impactScope.put("type", "string");
        impactScope.put("title", "影响范围");
        impactScope.put("enum", Arrays.asList(
            "ONE_USER", "PARTIAL_USERS", "ALL_USERS"));

        Map<String, Object> errorMessage = new LinkedHashMap<>();
        errorMessage.put("type", "string");
        errorMessage.put("title", "错误提示");
        errorMessage.put("description", "选填，填写页面上看到的错误信息");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("affectedSystem", affectedSystem);
        properties.put("impactScope", impactScope);
        properties.put("errorMessage", errorMessage);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", "补充登录故障信息");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("affectedSystem", "impactScope"));

        return AgentFormDefinition.builder("support_ticket_details")
            .description("准备故障工单时缺少受影响系统或影响范围")
            .schema(schema)
            .build();
    }

    /**
     * 定义由模型通过 request_user_input 主动选择的会议信息表单。
     */
    private static AgentFormDefinition createMeetingRequestForm() {
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("type", "string");
        subject.put("title", "会议主题");

        Map<String, Object> preferredTime = new LinkedHashMap<>();
        preferredTime.put("type", "string");
        preferredTime.put("title", "期望时间");
        preferredTime.put("description", "例如明天下午 3 点或 2026-08-10 15:00");

        Map<String, Object> participantCount = new LinkedHashMap<>();
        participantCount.put("type", "integer");
        participantCount.put("title", "参会人数");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subject", subject);
        properties.put("preferredTime", preferredTime);
        properties.put("participantCount", participantCount);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", "填写会议安排");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList(
            "subject", "preferredTime", "participantCount"));

        return AgentFormDefinition.builder("meeting_room_booking")
            .description("预定会议室时收集会议主题、时间和参会人数")
            .schema(schema)
            .build();
    }

    /** 模型读取表单提交结果后可以选择调用的真实业务工具。 */
    private static Tool createMeetingReservationTool() {
        return Tool.builder("reserve_meeting_room", "根据完整会议资料预定会议室")
            .addParameter(Parameter.builder().name("subject").type("string")
                .description("会议主题").required(true).build())
            .addParameter(Parameter.builder().name("preferredTime").type("string")
                .description("会议时间").required(true).build())
            .addParameter(Parameter.builder().name("participantCount").type("integer")
                .description("参会人数").required(true).build())
            .function(arguments -> {
                String roomId = "ROOM-SH-101";
                System.out.println("\n[工具已执行] 预定会议室 " + roomId + "，参数=" + arguments);
                return "会议室预定成功，roomId=" + roomId;
            })
            .build();
    }

    /**
     * 工单准备工具没有副作用；首次执行请求表单，恢复后返回可供模型创建工单的完整资料。
     */
    private static Tool createTicketPreparationTool(AgentFormDefinition formDefinition) {
        return Tool.builder("prepare_support_ticket", "整理创建支持工单所需的完整资料，不会写入业务系统")
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
                .description("根据当前对话整理的初步工单详情")
                .required(true)
                .build())
            .metadata("sideEffect", false)
            .metadata("category", "preparation")
            .function(arguments -> {
                AgentToolContext context = AgentToolContext.current();
                if (context == null) {
                    throw new IllegalStateException(
                        "prepare_support_ticket requires AgentToolContext");
                }
                Map<String, Object> submitted = context.getSubmittedFormData();
                if (submitted.isEmpty()) {
                    throw new AgentFormRequiredException(formDefinition);
                }
                Map<String, Object> prepared = new LinkedHashMap<>(arguments);
                prepared.putAll(submitted);
                prepared.put("idempotencyKey", context.getIdempotencyKey());
                System.out.println("\n[表单已提交] 工单资料=" + prepared);
                return prepared;
            })
            .build();
    }

    /**
     * 创建工单工具模拟真实写操作，只有审批通过后函数体才会执行。
     */
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
            .addParameter(Parameter.builder()
                .name("affectedSystem")
                .type("string")
                .description("表单中确认的受影响系统")
                .required(true)
                .build())
            .addParameter(Parameter.builder()
                .name("impactScope")
                .type("string")
                .description("表单中确认的影响范围")
                .required(true)
                .build())
            .addParameter(Parameter.builder()
                .name("errorMessage")
                .type("string")
                .description("用户看到的错误提示；没有时可以省略")
                .required(false)
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

    /**
     * 配置 Agent 的模型指令、规划边界、工具、审批规则和执行预算。
     */
    private static Agent createAgent(ChatModel chatModel, Tool currentTime,
                                     AgentFormDefinition meetingRequestForm,
                                     Tool reserveMeetingRoom,
                                     Tool prepareTicket, Tool createTicket) {
        return Agent.builder("console-assistant")
            .id("console-assistant")
            .version("1")
            .description("查询真实时间、整理支持信息并创建需要审批的工单")
            .instructions(
                "你是一个支持持续对话的中文助手。请结合完整会话历史理解代词和省略信息。"
                    + "普通问候或知识问题直接回答。用户询问当前日期或时间时，必须调用 get_current_time，"
                    + "不能依靠训练数据猜测。用户要求预定会议室时，必须调用 "
                    + "request_user_input 并选择 meeting_room_booking；收到表单提交结果后，必须调用 "
                    + "reserve_meeting_room 完成预定，不要只回复已收到表单。"
                    + "用户明确要求创建、提交或登记支持工单时，必须先调用 "
                    + "prepare_support_ticket 整理标题、优先级和描述；该工具补齐资料并返回后，再调用 "
                    + "create_support_ticket，不能跳过准备工具，也不能猜测表单字段。"
                    + "工具返回后必须根据真实结果回答，绝不能虚构工具已经执行。")
            .chatModel(chatModel)
            .tool(currentTime)
            .tool(AgentUserInputTool.builder().form(meetingRequestForm).build())
            .tool(reserveMeetingRoom)
            .tool(prepareTicket)
            .tool(createTicket)
            .maxAttachedMessages(40)
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
                    .maxDurationMillis(1200_000)
                    .build())
                .build())
            .build();
    }

    /**
     * 创建真实的 OpenAI-compatible ChatModel。
     */
    private static ChatModel createChatModel(DemoConfiguration configuration) {
        return OpenAIChatConfig.builder()
            .apiKey(configuration.apiKey)
            .endpoint(configuration.endpoint)
            .requestPath(configuration.requestPath)
            .model(configuration.model)
            .supportTool(true)
            .observabilityEnabled(false)
            .logEnabled(false)
            .supportThinking(true)
            .preserveThinkingEnable(true)
            .logEnabled(false)
            .buildModel();
    }

    /**
     * 只打印对学习执行过程有帮助的实时事件，避免控制台被所有 Snapshot 事件淹没。
     */
    private static void printEvent(AgentEvent event) {
        AgentEventType type = event.getType();
        if (type == AgentEventType.MODEL_STARTED) {
            // 一个 Turn 可能包含多次模型调用；工具执行后的最终回答必须重新判断是否已有增量。
            STREAMED_TURNS.remove(event.getTurnId());
        }
        if (type == AgentEventType.MODEL_TEXT_DELTA) {
            STREAMED_TURNS.add(event.getTurnId());
            Object content = event.getData().get("content");
            if (content != null) {
                System.out.print(content);
                System.out.flush();
            }
            return;
        }
        if (type == AgentEventType.MODEL_REASONING_DELTA) {
            return;
        }
//        if (type == AgentEventType.MODEL_STARTED
//            || type == AgentEventType.MODEL_COMPLETED
//            || type == AgentEventType.TOOL_STARTED
//            || type == AgentEventType.TOOL_COMPLETED
//            || type == AgentEventType.TOOL_APPROVAL_REQUESTED
//            || type == AgentEventType.TURN_SUSPENDED
//            || type == AgentEventType.TURN_RESUMED) {
            System.out.println("[事件] " + type + " turn=" + event.getTurnId() +" data:" + event.getData());
//                );
//        }
    }

    private static ToolCall findPendingCall(AgentTurn turn, String callId) {
        if (callId == null) return null;
        for (ToolCall call : turn.getPendingToolCalls()) {
            if (call != null && callId.equals(call.getId())) {
                return call;
            }
        }
        return null;
    }

    private static AgentSuspension requireSuspension(AgentTurn turn) {
        AgentSuspension suspension = turn.getSuspension();
        if (suspension == null) {
            throw new IllegalStateException("阻塞 Turn 缺少 Suspension: " + turn.getId());
        }
        return suspension;
    }

    private static void printTurnResult(AgentTurn turn) {
        if (turn.getStatus() == AgentTurnStatus.COMPLETED) {
            // 流式增量已经实时输出；只有服务端没有产生任何增量时才回退打印完整结果。
            if (STREAMED_TURNS.remove(turn.getId())) {
                System.out.println("\n[助手输出完成]");
            } else {
                System.out.println("\n助手 > " + turn.getFinalOutput());
            }
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
        List<Message> messages = memory.getMessages(0, HISTORY_DISPLAY_LIMIT);
        System.out.println("\n当前会话最近 " + messages.size() + " 条消息"
            + "（最多展示 " + HISTORY_DISPLAY_LIMIT + " 条）：");
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String details = "";
            if (message instanceof AgentFormMessage) {
                AgentFormMessage form = (AgentFormMessage) message;
                details = " formKey=" + form.getFormKey() + " status=" + form.getStatus();
            }
            System.out.println("  " + (i + 1) + ". " + message.getClass().getSimpleName()
                + " modelVisible=" + message.isModelVisible()
                + details + "  " + abbreviate(message.getTextContent(), 120));
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
        System.out.println("storage      : in-memory（进程退出后清空）");
        printHelp();
    }

    private static void printHelp() {
        System.out.println("\n建议依次尝试：");
        System.out.println("  1. 你好，我叫小明。             （普通持续对话）");
        System.out.println("  2. 你还记得我叫什么吗？         （读取业务 ChatMemory）");
        System.out.println("  3. 上海现在几点？               （自动调用只读工具）");
        System.out.println("  4. 请帮我收集会议安排信息。（模型主动请求表单）");
        System.out.println("  5. 帮我创建一个高优先级登录故障工单。（工具请求表单 + 人工审批）");
        System.out.println("命令：/history 查看最近的时间线消息，/help 查看帮助，/exit 退出。");
    }

    private static boolean isApproved(String value) {
        String answer = value == null ? "" : value.trim();
        return "y".equalsIgnoreCase(answer)
            || "yes".equalsIgnoreCase(answer)
            || "是".equals(answer)
            || "同意".equals(answer)
            || "批准".equals(answer);
    }

    private static boolean isRejected(String value) {
        String answer = value == null ? "" : value.trim();
        return "n".equalsIgnoreCase(answer)
            || "no".equalsIgnoreCase(answer)
            || "否".equals(answer)
            || "不同意".equals(answer)
            || "拒绝".equals(answer)
            || "不批准".equals(answer);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) value)
            : Collections.emptyMap();
    }

    private static String textValue(Object value, String defaultValue) {
        return value == null || String.valueOf(value).trim().isEmpty()
            ? defaultValue : String.valueOf(value);
    }

    /**
     * 模型连接参数只从环境变量读取，避免 API Key 进入源码或 Git。
     */
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
