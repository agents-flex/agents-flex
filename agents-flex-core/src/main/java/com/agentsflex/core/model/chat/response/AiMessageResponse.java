/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.model.chat.response;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.agentsflex.core.model.chat.tool.ToolExecutor;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.MessageUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;

import java.util.*;

public class AiMessageResponse extends AbstractBaseMessageResponse<AiMessage> {

    private final ChatContext context;
    private final String rawText;
    private final AiMessage message;

    public AiMessageResponse(ChatContext context, String rawText, AiMessage message) {
        this.context = context;
        this.rawText = rawText;
        this.message = message;
    }


    public ChatContext getContext() {
        return context;
    }

    public String getRawText() {
        return rawText;
    }

    @Override
    public AiMessage getMessage() {
        return message;
    }

    public boolean hasToolCalls() {
        if (this.message == null) {
            return false;
        }
        List<ToolCall> toolCalls = message.getToolCalls();
        return toolCalls != null && !toolCalls.isEmpty();
    }


    public List<ToolExecutor> getToolExecutors(ToolInterceptor... interceptors) {
        if (this.message == null) {
            return Collections.emptyList();
        }

        List<ToolCall> calls = message.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return Collections.emptyList();
        }

        UserMessage userMessage = MessageUtil.findLastUserMessage(getContext().getPrompt().getMessages());
        Map<String, Tool> funcMap = userMessage.getToolsMap();

        if (funcMap == null || funcMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolExecutor> toolExecutors = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            Tool tool = funcMap.get(call.getName());
            if (tool != null) {
                ToolExecutor executor = new ToolExecutor(tool, call);
                if (interceptors != null && interceptors.length > 0) {
                    executor.addInterceptors(Arrays.asList(interceptors));
                }
                toolExecutors.add(executor);
            }
        }
        return toolExecutors;
    }


    public List<Object> executeToolCallsAndGetResults(ToolInterceptor... interceptors) {
        List<ToolExecutor> toolExecutors = getToolExecutors(interceptors);

        for (ToolExecutor toolExecutor : toolExecutors) {
            toolExecutor.addInterceptors(Arrays.asList(interceptors));
        }

        List<Object> results = new ArrayList<>();
        for (ToolExecutor toolExecutor : toolExecutors) {
            results.add(toolExecutor.execute());
        }
        return results;
    }


    public List<ToolMessage> executeToolCallsAndGetToolMessages(ToolInterceptor... interceptors) {
        List<ToolExecutor> toolExecutors = getToolExecutors(interceptors);

        if (CollectionUtil.noItems(toolExecutors)) {
            return Collections.emptyList();
        }

        List<ToolMessage> toolMessages = new ArrayList<>(toolExecutors.size());
        for (ToolExecutor toolExecutor : toolExecutors) {
            ToolMessage toolMessage = new ToolMessage();
            String callId = toolExecutor.getToolCall().getId();
            if (StringUtil.hasText(callId)) {
                toolMessage.setToolCallId(callId);
            } else {
                toolMessage.setToolCallId(toolExecutor.getToolCall().getName());
            }
            Object result = toolExecutor.execute();
            if (result instanceof CharSequence || result instanceof Number) {
                toolMessage.setContent(result.toString());
            } else {
                toolMessage.setContent(JSON.toJSONString(result));
            }
            toolMessages.add(toolMessage);
        }
        return toolMessages;
    }


    public static AiMessageResponse error(ChatContext context, String rawText, String errorMessage) {
        AiMessageResponse errorResp = new AiMessageResponse(context, rawText, null);
        errorResp.setError(true);
        errorResp.setErrorMessage(errorMessage);
        return errorResp;
    }


    @Override
    public String toString() {
        return "AiMessageResponse{" +
            "context=" + context +
            ", rawText='" + rawText + '\'' +
            ", message=" + message +
            ", error=" + error +
            ", errorMessage='" + errorMessage + '\'' +
            ", errorType='" + errorType + '\'' +
            ", errorCode='" + errorCode + '\'' +
            '}';
    }
}
