/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.agent.react;

import com.agentsflex.core.agent.IAgent;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.*;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ReActAgent 是一个通用的 ReAct 模式 Agent，支持 Reasoning + Action 的交互方式。
 */
public class ReActAgent implements IAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private static final String DEFAULT_PROMPT_TEMPLATE =
        "你是一个 ReAct Agent，结合 Reasoning（推理）和 Action（行动）来解决问题。\n" +
            "但在处理用户问题时，请首先判断：\n" +
            "1. 如果问题可以通过你的常识或已有知识直接回答 → 请忽略 ReAct 框架，直接输出自然语言回答。\n" +
            "2. 如果问题需要调用特定工具才能解决（如查询、计算、获取外部信息等）→ 请严格按照 ReAct 格式响应。\n" +
            "\n" +
            "如果你选择使用 ReAct 模式，请遵循以下格式：\n" +
            "Thought: 描述你对当前问题的理解，包括已知信息和缺失信息，说明你下一步将采取什么行动及其原因。\n" +
            "Action: 从下方列出的工具中选择一个合适的工具，仅输出工具名称，不得虚构。\n" +
            "Action Input: 使用标准 JSON 格式提供该工具所需的参数，确保字段名与工具描述一致。\n" +
            "\n" +
            "在 ReAct 模式下，如果你已获得足够信息可以直接回答用户，请输出：\n" +
            "Final Answer: [你的回答]\n" +
            "\n" +
            "如果你发现用户的问题缺少关键信息（例如时间、地点、具体目标、主体信息等），且无法通过工具获取，\n" +
            "请主动向用户提问，格式如下：\n" +
            "Request: [你希望用户澄清的问题]" +
            "\n" +
            "注意事项：\n" +
            "1. 每次只能选择一个工具并执行一个动作。\n" +
            "2. 在未收到工具执行结果前，不要自行假设其输出。\n" +
            "3. 不得编造工具或参数，所有工具均列于下方。\n" +
            "4. 输出顺序必须为：Thought → Action → Action Input。\n" +
            "\n" +
            "### 可用工具列表：\n" +
            "{tools}\n" +
            "\n" +
            "### 用户问题如下：\n" +
            "{user_input}";

    private static final int DEFAULT_MAX_ITERATIONS = 20;

    private final ChatModel chatModel;
    private final List<Tool> tools;
    private final ReActAgentState state;

    private ReActStepParser reActStepParser = ReActStepParser.DEFAULT; // 默认解析器
    private final HistoriesPrompt historiesPrompt;
    private ChatOptions chatOptions;
    private ReActMessageBuilder messageBuilder = new ReActMessageBuilder();

    // 监听器集合
    private final List<ReActAgentListener> listeners = new ArrayList<>();

    // 拦截器集合
    private final List<ToolInterceptor> interceptors = new ArrayList<>();


    public ReActAgent(ChatModel chatModel, List<Tool> tools, String userQuery) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.state = new ReActAgentState();
        this.state.userQuery = userQuery;
        this.state.promptTemplate = DEFAULT_PROMPT_TEMPLATE;
        this.state.maxIterations = DEFAULT_MAX_ITERATIONS;
        this.historiesPrompt = new HistoriesPrompt();
    }

    public ReActAgent(ChatModel chatModel, List<Tool> tools, String userQuery, HistoriesPrompt historiesPrompt) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.state = new ReActAgentState();
        this.state.userQuery = userQuery;
        this.state.promptTemplate = DEFAULT_PROMPT_TEMPLATE;
        this.state.maxIterations = DEFAULT_MAX_ITERATIONS;
        this.historiesPrompt = historiesPrompt;
    }

    public ReActAgent(ChatModel chatModel, List<Tool> tools, ReActAgentState state) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.state = state;
        this.historiesPrompt = new HistoriesPrompt();
        if (state.messageHistory != null) {
            this.historiesPrompt.addMessages(state.messageHistory);
        }
    }

    /**
     * 注册监听器
     */
    public void addListener(ReActAgentListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除监听器
     */
    public void removeListener(ReActAgentListener listener) {
        listeners.remove(listener);
    }

    public void addInterceptor(ToolInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    public void addInterceptors(List<ToolInterceptor> interceptors) {
        this.interceptors.addAll(interceptors);
    }

    public void removeInterceptor(ToolInterceptor interceptor) {
        interceptors.remove(interceptor);
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public ReActStepParser getReActStepParser() {
        return reActStepParser;
    }

    public void setReActStepParser(ReActStepParser reActStepParser) {
        this.reActStepParser = reActStepParser;
    }

    public List<ReActAgentListener> getListeners() {
        return listeners;
    }

    public boolean isStreamable() {
        return this.state.streamable;
    }

    public void setStreamable(boolean streamable) {
        this.state.streamable = streamable;
    }

    public HistoriesPrompt getHistoriesPrompt() {
        return historiesPrompt;
    }

    public ReActMessageBuilder getMessageBuilder() {
        return messageBuilder;
    }

    public void setMessageBuilder(ReActMessageBuilder messageBuilder) {
        this.messageBuilder = messageBuilder;
    }

    public ChatOptions getChatOptions() {
        return chatOptions;
    }

    public void setChatOptions(ChatOptions chatOptions) {
        this.chatOptions = chatOptions;
    }

    public ReActAgentState getState() {
        state.messageHistory = historiesPrompt.getMessages();
        return state;
    }

    /**
     * 运行 ReAct Agent 流程
     */
    @Override
    public void execute() {
        try {
            List<Message> messageHistory = state.getMessageHistory();
            if (messageHistory == null || messageHistory.isEmpty()) {
                String toolsDescription = buildToolsDescription(tools);
                String prompt = state.promptTemplate
                    .replace("{tools}", toolsDescription)
                    .replace("{user_input}", state.userQuery);

                Message message = messageBuilder.buildStartMessage(prompt, tools, state.userQuery);
                historiesPrompt.addMessage(message);
            }
            if (this.isStreamable()) {
                startNextReActStepStream();
            } else {
                startNextReactStepNormal();
            }
        } catch (Exception e) {
            log.error("运行 ReAct Agent 出错：" + e);
            notifyOnError(e);
        }
    }

    private void startNextReactStepNormal() {
        while (state.iterationCount < state.maxIterations) {

            state.iterationCount++;

            AiMessageResponse response = chatModel.chat(historiesPrompt, chatOptions);
            notifyOnChatResponse(response);

            String content = response.getMessage().getContent();
            AiMessage message = new AiMessage(content);

            // 请求用户输入
            if (isRequestUserInput(content)) {
                String question = extractRequestQuestion(content);
                message.addMetadata("type", "reActRequest");
                historiesPrompt.addMessage(message);
                notifyOnRequestUserInput(question); // 新增监听器回调
                break; // 暂停执行，等待用户回复
            }
            //  ReAct 动作
            else if (isReActAction(content)) {
                message.addMetadata("type", "reActAction");
                historiesPrompt.addMessage(message);
                if (!processReActSteps(content)) {
                    break;
                }
            }

            // 最终答案
            else if (isFinalAnswer(content)) {
                String flag = reActStepParser.getFinalAnswerFlag();
                String answer = content.substring(content.indexOf(flag) + flag.length());
                message.addMetadata("type", "reActFinalAnswer");
                historiesPrompt.addMessage(message);
                notifyOnFinalAnswer(answer);
                break;
            }
            //  不是 Action
            else {
                historiesPrompt.addMessage(message);
                notifyOnNonActionResponse(response);
                break;
            }
        }

        // 显式通知达到最大迭代
        if (state.iterationCount >= state.maxIterations) {
            notifyOnMaxIterationsReached();
        }
    }


    private void startNextReActStepStream() {
        if (state.iterationCount >= state.maxIterations) {
            notifyOnMaxIterationsReached();
            return;
        }

        state.iterationCount++;

        chatModel.chatStream(historiesPrompt, new StreamResponseListener() {

            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                notifyOnChatResponseStream(context, response);
            }

            @Override
            public void onStop(StreamContext context) {
                AiMessage lastAiMessage = context.getAiMessage();
                if (lastAiMessage == null) {
                    notifyOnError(new RuntimeException("没有收到任何回复"));
                    return;
                }

                String content = lastAiMessage.getFullContent();
                if (StringUtil.noText(content)) {
                    notifyOnError(new RuntimeException("没有收到任何回复"));
                    return;
                }

                AiMessage message = new AiMessage(content);

                // 请求用户输入
                if (isRequestUserInput(content)) {
                    String question = extractRequestQuestion(content);
                    message.addMetadata("type", "reActRequest");
                    historiesPrompt.addMessage(message);
                    notifyOnRequestUserInput(question); // 新增监听器回调
                }

                //  ReAct 动作
                else if (isReActAction(content)) {
                    message.addMetadata("type", "reActAction");
                    historiesPrompt.addMessage(message);
                    if (processReActSteps(content)) {
                        // 递归继续执行下一个 ReAct 步骤
                        startNextReActStepStream();
                    }
                }

                // 最终答案
                else if (isFinalAnswer(content)) {
                    message.addMetadata("type", "reActFinalAnswer");
                    historiesPrompt.addMessage(message);
                    String flag = reActStepParser.getFinalAnswerFlag();
                    String answer = content.substring(content.indexOf(flag) + flag.length());
                    notifyOnFinalAnswer(answer);
                } else {
                    historiesPrompt.addMessage(message);
                    //  不是 Action
                    notifyOnNonActionResponseStream(context);
                }
            }

            @Override
            public void onFailure(StreamContext context, Throwable throwable) {
                notifyOnError((Exception) throwable);
            }
        }, chatOptions);
    }


    private boolean isFinalAnswer(String content) {
        return reActStepParser.isFinalAnswer(content);
    }

    private boolean isReActAction(String content) {
        return reActStepParser.isReActAction(content);
    }

    private boolean isRequestUserInput(String content) {
        return reActStepParser.isRequest(content);
    }

    private String extractRequestQuestion(String content) {
        return reActStepParser.extractRequestQuestion(content);
    }

    // ========== 内部辅助方法 ==========

    private String buildToolsDescription(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append(" - ").append(tool.getName()).append("(");
            Parameter[] parameters = tool.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                sb.append(param.getName()).append(": ").append(param.getType());
                if (i < parameters.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append("): ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }


    private boolean processReActSteps(String content) {
        List<ReActStep> reActSteps = reActStepParser.parse(content);
        if (reActSteps.isEmpty()) {
            notifyOnStepParseError(content);
            return false;
        }

        for (ReActStep step : reActSteps) {
            boolean stepExecuted = false;
            for (Tool tool : tools) {
                if (tool.getName().equals(step.getAction())) {
                    try {
                        notifyOnActionStart(step);

                        Object result = null;
                        try {
                            ToolCall toolCall = new ToolCall();
                            toolCall.setId("react_call_" + state.iterationCount + "_" + System.currentTimeMillis());
                            toolCall.setName(step.getAction());
                            toolCall.setArgsString(step.getActionInput());

                            ToolExecutor executor = new ToolExecutor(tool, toolCall, interceptors);

                            // 方便子Agent 获取当前的 ReActAgent
                            executor.addInterceptor((context, chain) -> {
                                context.setAttribute(ReActAgentTool.PARENT_AGENT_KEY, ReActAgent.this);
                                return chain.proceed(context);
                            });

                            result = executor.execute();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            notifyOnActionEnd(step, result);
                        }

                        Message message = messageBuilder.buildObservationMessage(step, result);
                        historiesPrompt.addMessage(message);
                        stepExecuted = true;
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                        notifyOnActionInvokeError(e);

                        if (!state.continueOnActionInvokeError) {
                            return false;
                        }

                        Message message = messageBuilder.buildActionErrorMessage(step, e);
                        historiesPrompt.addMessage(message);
                        return true;
                    }
                    break;
                }
            }

            if (!stepExecuted) {
                notifyOnActionNotMatched(step, tools);
                return false;
            }
        }


        return true;
    }


    // ========== 通知监听器的方法 ==========
    private void notifyOnChatResponse(AiMessageResponse response) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onChatResponse(response);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnNonActionResponse(AiMessageResponse response) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onNonActionResponse(response);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnNonActionResponseStream(StreamContext context) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onNonActionResponseStream(context);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnChatResponseStream(StreamContext context, AiMessageResponse response) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onChatResponseStream(context, response);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnFinalAnswer(String finalAnswer) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onFinalAnswer(finalAnswer);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnRequestUserInput(String question) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onRequestUserInput(question);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnActionStart(ReActStep reActStep) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onActionStart(reActStep);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnActionEnd(ReActStep reActStep, Object result) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onActionEnd(reActStep, result);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnMaxIterationsReached() {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onMaxIterationsReached();
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnStepParseError(String content) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onStepParseError(content);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnActionNotMatched(ReActStep step, List<Tool> tools) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onActionNotMatched(step, tools);
            } catch (Exception e) {
                log.error(e.toString(), e);
            }
        }
    }

    private void notifyOnActionInvokeError(Exception e) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onActionInvokeError(e);
            } catch (Exception e1) {
                log.error(e.toString(), e1);
            }
        }
    }

    private void notifyOnError(Exception e) {
        for (ReActAgentListener listener : listeners) {
            try {
                listener.onError(e);
            } catch (Exception e1) {
                log.error(e.toString(), e1);
            }
        }
    }
}
