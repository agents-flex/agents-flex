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
package com.agentsflex.core.react;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.functions.Function;
import com.agentsflex.core.llm.functions.Parameter;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ReActAgent 是一个通用的 ReAct 模式 Agent，支持 Reasoning + Action 的交互方式。
 */
public class ReActAgent {

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

    // 默认最大迭代次数
    private static final int DEFAULT_MAX_ITERATIONS = 5;

    private final Llm llm;
    private final List<Function> functions;
    private final String userQuery;

    private boolean streamable = false;
    private int maxIterations = DEFAULT_MAX_ITERATIONS;
    private String promptTemplate = DEFAULT_PROMPT_TEMPLATE;
    private ReActStepParser reActStepParser = ReActStepParser.DEFAULT; // 默认解析器
    private final HistoriesPrompt historiesPrompt;

    // 监听器集合
    private final List<ReActAgentListener> listeners = new ArrayList<>();

    private int iterationCount = 0;

    public ReActAgent(Llm llm, List<Function> functions, String userQuery) {
        this.llm = llm;
        this.functions = functions;
        this.userQuery = userQuery;
        this.historiesPrompt = new HistoriesPrompt();
    }

    public ReActAgent(Llm llm, List<Function> functions, String userQuery, HistoriesPrompt historiesPrompt) {
        this.llm = llm;
        this.functions = functions;
        this.userQuery = userQuery;
        this.historiesPrompt = historiesPrompt;
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

    public Llm getLlm() {
        return llm;
    }

    public List<Function> getFunctions() {
        return functions;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public String getPromptTemplate() {
        return promptTemplate;
    }

    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
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
        return streamable;
    }

    public void setStreamable(boolean streamable) {
        this.streamable = streamable;
    }

    public HistoriesPrompt getHistoriesPrompt() {
        return historiesPrompt;
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public void setIterationCount(int iterationCount) {
        this.iterationCount = iterationCount;
    }

    /**
     * 运行 ReAct Agent 流程
     */
    public void run() {
        try {
            String toolsDescription = buildToolsDescription(functions);
            String prompt = promptTemplate
                .replace("{tools}", toolsDescription)
                .replace("{user_input}", userQuery);

            HumanMessage message = new HumanMessage(prompt);
            message.addMetadata("tools", functions);
            message.addMetadata("user_input", userQuery);
            message.addMetadata("type", "reActWrapper");

            historiesPrompt.addMessage(message);

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
        for (int i = 0; i < maxIterations; i++) {
            AiMessageResponse response = llm.chat(historiesPrompt);
            String content = response.getMessage().getContent();
            historiesPrompt.addMessage(new AiMessage(content));

            notifyOnChatResponse(response);

            if (isReActAction(content)) {
                if (!processReActSteps(content)) {
                    break;
                }
            } else if (isFinalAnswer(content)) {
                String flag = reActStepParser.getFinalAnswerFlag();
                String answer = content.substring(content.indexOf(flag) + flag.length());
                notifyOnFinalAnswer(answer);
                break;
            } else {
                //  不是Action
                notifyOnNonActionResponse(response);
                break;
            }
        }
    }

    private void startNextReActStepStream() {
        if (iterationCount >= maxIterations) {
            notifyOnMaxIterationsReached();
            return;
        }

        iterationCount++;

        llm.chatStream(historiesPrompt, new StreamResponseListener() {

            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                notifyOnChatResponseStream(context, response);
            }

            @Override
            public void onStop(ChatContext context) {
                AiMessage lastAiMessage = context.getLastAiMessage();
                if (lastAiMessage == null) {
                    notifyOnError(new RuntimeException("没有收到任何回复"));
                    return;
                }

                String content = lastAiMessage.getFullContent();
                if (StringUtil.noText(content)) {
                    notifyOnError(new RuntimeException("没有收到任何回复"));
                    return;
                }

                // Stream 模式下，消息会自动被添加到  historiesPrompt 中，无需手动添加
                // AiMessage aiMessage = new AiMessage(content);
                // historiesPrompt.addMessage(aiMessage);

                if (isReActAction(content)) {
                    if (processReActSteps(content)) {
                        // 递归继续执行下一个 ReAct 步骤
                        startNextReActStepStream();
                    }
                } else if (isFinalAnswer(content)) {
                    String flag = reActStepParser.getFinalAnswerFlag();
                    String answer = content.substring(content.indexOf(flag) + flag.length());
                    notifyOnFinalAnswer(answer);
                } else {
                    //  不是 Action
                    notifyOnNonActionResponseStream(context);
                }
            }

            @Override
            public void onFailure(ChatContext context, Throwable throwable) {
                notifyOnError((Exception) throwable);
            }
        });
    }


    private boolean isFinalAnswer(String content) {
        return reActStepParser.isFinalAnswer(content);
    }

    private boolean isReActAction(String content) {
        return reActStepParser.isReActAction(content);
    }

    // ========== 内部辅助方法 ==========

    private String buildToolsDescription(List<Function> functions) {
        StringBuilder sb = new StringBuilder();
        for (Function function : functions) {
            sb.append(" - ").append(function.getName()).append("(");
            Parameter[] parameters = function.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                sb.append(param.getName()).append(": ").append(param.getType());
                if (i < parameters.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append("): ").append(function.getDescription()).append("\n");
        }
        return sb.toString();
    }


    private String buildObservationString(ReActStep step, Object result) {
        return "Action：" + step.getAction() + "\n" +
            "Action Input：" + step.getActionInput() + "\n" +
            "Action Result：" + result + "\n";
    }


    private boolean processReActSteps(String content) {
        List<ReActStep> reActSteps = reActStepParser.parse(content);
        if (reActSteps.isEmpty()) {
            notifyOnStepParseError(content);
            return false;
        }

        for (ReActStep step : reActSteps) {
            boolean stepExecuted = false;
            for (Function function : functions) {
                if (function.getName().equals(step.getAction())) {
                    try {
                        notifyOnActionStart(step);
                        Map<String, Object> parameters = StringUtil.hasText(step.getActionInput())
                            ? JSON.parseObject(step.getActionInput()) : Collections.emptyMap();
                        Object result = function.invoke(parameters);
                        notifyOnActionEnd(step, result);

                        String observation = buildObservationString(step, result);
                        HumanMessage humanMessage = new HumanMessage(observation + "\n请继续推理下一步。");
                        humanMessage.addMetadata("type", "reActObservation");

                        historiesPrompt.addMessage(humanMessage);
                        stepExecuted = true;
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                        notifyOnActionError(e);
                    }
                    break;
                }
            }

            if (!stepExecuted) {
                notifyOnActionNotMatched(step, functions);
                return false;
            }
        }


        return true;
    }


    // ========== 通知监听器的方法 ==========
    private void notifyOnChatResponse(AiMessageResponse response) {
        for (ReActAgentListener l : listeners) {
            l.onChatResponse(response);
        }
    }

    private void notifyOnNonActionResponse(AiMessageResponse response) {
        for (ReActAgentListener l : listeners) {
            l.onNonActionResponse(response);
        }
    }

    private void notifyOnNonActionResponseStream(ChatContext context) {
        for (ReActAgentListener l : listeners) {
            l.onNonActionResponseStream(context);
        }
    }

    private void notifyOnChatResponseStream(ChatContext context, AiMessageResponse response) {
        for (ReActAgentListener l : listeners) {
            l.onChatResponseStream(context, response);
        }
    }

    private void notifyOnFinalAnswer(String finalAnswer) {
        for (ReActAgentListener l : listeners) {
            l.onFinalAnswer(finalAnswer);
        }
    }

    private void notifyOnActionStart(ReActStep reActStep) {
        for (ReActAgentListener l : listeners) {
            l.onActionStart(reActStep);
        }
    }

    private void notifyOnActionEnd(ReActStep reActStep, Object result) {
        for (ReActAgentListener l : listeners) {
            l.onActionEnd(reActStep, result);
        }
    }

    private void notifyOnMaxIterationsReached() {
        for (ReActAgentListener l : listeners) {
            l.onMaxIterationsReached();
        }
    }

    private void notifyOnStepParseError(String content) {
        for (ReActAgentListener l : listeners) {
            l.onStepParseError(content);
        }
    }

    private void notifyOnActionNotMatched(ReActStep step, List<Function> functions) {
        for (ReActAgentListener l : listeners) {
            l.onActionNotMatched(step, functions);
        }
    }


    private void notifyOnActionError(Exception e) {
        for (ReActAgentListener l : listeners) {
            l.onActionError(e);
        }
    }

    private void notifyOnError(Exception error) {
        for (ReActAgentListener l : listeners) {
            l.onError(error);
        }
    }
}
