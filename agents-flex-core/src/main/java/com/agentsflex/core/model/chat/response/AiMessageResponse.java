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
package com.agentsflex.core.model.chat.response;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.functions.Function;
import com.agentsflex.core.model.chat.functions.FunctionInterceptor;
import com.agentsflex.core.model.chat.functions.FunctionInvoker;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.MessageUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;

import java.util.*;

public class AiMessageResponse extends AbstractBaseMessageResponse<AiMessage> {

    private final ChatContext context;
    private final String response;
    private final AiMessage message;

    public AiMessageResponse(ChatContext context, String response, AiMessage message) {
        this.context = context;
        this.response = response;
        this.message = message;
    }


    public ChatContext getContext() {
        return context;
    }

    public String getResponse() {
        return response;
    }

    @Override
    public AiMessage getMessage() {
        return message;
    }

    public boolean isFunctionCall() {
        if (this.message == null) {
            return false;
        }
        List<FunctionCall> functionCalls = message.getFunctionCalls();
        return functionCalls != null && !functionCalls.isEmpty();
    }


    public List<FunctionInvoker> getFunctionInvokers(FunctionInterceptor... interceptors) {
        if (this.message == null) {
            return Collections.emptyList();
        }

        List<FunctionCall> calls = message.getFunctionCalls();
        if (calls == null || calls.isEmpty()) {
            return Collections.emptyList();
        }

        UserMessage userMessage = MessageUtil.findLastUserMessage(getContext().getPrompt().getMessages());
        Map<String, Function> funcMap = userMessage.getFunctionMap();

        if (funcMap == null || funcMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<FunctionInvoker> functionInvokers = new ArrayList<>(calls.size());
        for (FunctionCall call : calls) {
            Function function = funcMap.get(call.getName());
            if (function != null) {
                FunctionInvoker invoker = new FunctionInvoker(function, call);
                if (interceptors != null && interceptors.length > 0) {
                    invoker.addInterceptors(Arrays.asList(interceptors));
                }
                functionInvokers.add(invoker);
            }
        }
        return functionInvokers;
    }


    public List<Object> getFunctionResults(FunctionInterceptor... interceptors) {
        List<FunctionInvoker> functionInvokers = getFunctionInvokers(interceptors);

        for (FunctionInvoker functionInvoker : functionInvokers) {
            functionInvoker.addInterceptors(Arrays.asList(interceptors));
        }

        List<Object> results = new ArrayList<>();
        for (FunctionInvoker functionInvoker : functionInvokers) {
            results.add(functionInvoker.invoke());
        }
        return results;
    }


    public List<ToolMessage> getToolMessages(FunctionInterceptor... interceptors) {
        List<FunctionInvoker> functionInvokers = getFunctionInvokers(interceptors);

        if (CollectionUtil.noItems(functionInvokers)) {
            return Collections.emptyList();
        }

        List<ToolMessage> toolMessages = new ArrayList<>(functionInvokers.size());
        for (FunctionInvoker functionInvoker : functionInvokers) {
            ToolMessage toolMessage = new ToolMessage();
            String callId = functionInvoker.getFunctionCall().getId();
            if (StringUtil.hasText(callId)) {
                toolMessage.setToolCallId(callId);
            } else {
                toolMessage.setToolCallId(functionInvoker.getFunctionCall().getName());
            }
            Object result = functionInvoker.invoke();
            if (result instanceof CharSequence || result instanceof Number) {
                toolMessage.setContent(result.toString());
            } else {
                toolMessage.setContent(JSON.toJSONString(result));
            }
            toolMessages.add(toolMessage);
        }
        return toolMessages;
    }


    public static AiMessageResponse error(ChatContext context, String response, String errorMessage) {
        AiMessageResponse errorResp = new AiMessageResponse(context, response, null);
        errorResp.setError(true);
        errorResp.setErrorMessage(errorMessage);
        return errorResp;
    }


    @Override
    public String toString() {
        return "AiMessageResponse{" +
            "context=" + context +
            ", response='" + response + '\'' +
            ", message=" + message +
            ", error=" + error +
            ", errorMessage='" + errorMessage + '\'' +
            ", errorType='" + errorType + '\'' +
            ", errorCode='" + errorCode + '\'' +
            '}';
    }
}
