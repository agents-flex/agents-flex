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
package com.agentsflex.core.llm.client;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class BaseLlmClientListener implements LlmClientListener {

    private final StreamResponseListener streamResponseListener;
    private final Prompt prompt;
    private final AiMessageParser messageParser;
    private final StringBuilder fullReasoningContent = new StringBuilder();
    private final StringBuilder fullMessage = new StringBuilder();
    private AiMessage lastAiMessage;
    private final ChatContext context;
    private final List<FunctionCallRecord> functionCallRecords = new ArrayList<>(0);
    private FunctionCallRecord functionCallRecord;

    public BaseLlmClientListener(Llm llm
        , LlmClient client
        , StreamResponseListener streamResponseListener
        , Prompt prompt
        , AiMessageParser messageParser) {

        this.streamResponseListener = streamResponseListener;
        this.prompt = prompt;
        this.messageParser = messageParser;
        this.context = new ChatContext(llm, client);
    }


    @Override
    public void onStart(LlmClient client) {
        streamResponseListener.onStart(context);
    }

    @Override
    public void onMessage(LlmClient client, String response) {
        if (StringUtil.noText(response) || "[DONE]".equalsIgnoreCase(response.trim())) {
            //兼容在某些情况下，llm 没有出现 finish_reason: "tool_calls" 的响应
            if (!this.functionCallRecords.isEmpty()) {
                invokeOnMessageForFunctionCall(response);
            }
            return;
        }

        try {
            JSONObject jsonObject = JSON.parseObject(response);
            lastAiMessage = messageParser.parse(jsonObject);
            String reasoningContent = lastAiMessage.getReasoningContent();
            String content = lastAiMessage.getContent();

            // 第一个和最后一个content都为null
            if (Objects.nonNull(content)) {
                fullMessage.append(content);
            }
            if (Objects.nonNull(reasoningContent)) {
                fullReasoningContent.append(reasoningContent);
            }

            lastAiMessage.setFullReasoningContent(fullReasoningContent.toString());
            lastAiMessage.setFullContent(fullMessage.toString());

            String functionName = (String) JSONPath.eval(jsonObject, "$.choices[0].delta.tool_calls[0].function.name");
            if (StringUtil.hasText(functionName)) {
                functionCallRecord = new FunctionCallRecord();
                functionCallRecord.name = functionName;
                functionCallRecord.id = (String) JSONPath.eval(jsonObject, "$.choices[0].delta.tool_calls[0].id");

                // 第一次都没有 arguments
                // currentFunctionCallJSON.arguments += (String) JSONPath.eval(jsonObject, "$.choices[0].delta.tool_calls[0].function.arguments");

                functionCallRecords.add(functionCallRecord);
                streamResponseListener.onMatchedFunction(functionName, context);
            } else if (functionCallRecord != null) {
                String arguments = (String) JSONPath.eval(jsonObject, "$.choices[0].delta.tool_calls[0].function.arguments");
                if (arguments != null) {
                    functionCallRecord.arguments += arguments;
                } else {
                    String finishReason = (String) JSONPath.eval(jsonObject, "$.choices[0].finish_reason");
                    if ("tool_calls".equals(finishReason)) {
                        functionCallRecord = null;
                        invokeOnMessageForFunctionCall(response);
                    }
                }
            } else {
                AiMessageResponse aiMessageResponse = new AiMessageResponse(prompt, response, lastAiMessage);
                streamResponseListener.onMessage(context, aiMessageResponse);
            }
        } catch (Exception err) {
            streamResponseListener.onFailure(context, err);
        }
    }

    private void invokeOnMessageForFunctionCall(String response) {
        List<FunctionCall> calls = new ArrayList<>(functionCallRecords.size());
        for (FunctionCallRecord record : functionCallRecords) {
            calls.add(record.toFunctionCall());
        }
        lastAiMessage.setCalls(calls);
        AiMessageResponse aiMessageResponse = new AiMessageResponse(prompt, response, lastAiMessage);

        try {
            streamResponseListener.onMessage(context, aiMessageResponse);
        } finally {
            functionCallRecords.clear();
        }
    }

    @Override
    public void onStop(LlmClient client) {
        if (lastAiMessage != null) {
            if (this.prompt instanceof HistoriesPrompt) {
                ((HistoriesPrompt) this.prompt).addMessage(lastAiMessage);
            }
        }
        context.addLastAiMessage(lastAiMessage);
        streamResponseListener.onStop(context);
    }

    @Override
    public void onFailure(LlmClient client, Throwable throwable) {
        streamResponseListener.onFailure(context, throwable);
    }

    static class FunctionCallRecord {
        String id;
        String name;
        String arguments = "";

        public FunctionCall toFunctionCall() {
            FunctionCall functionCall = new FunctionCall();
            functionCall.setId(id);
            functionCall.setName(name);
            functionCall.setArgs(JSON.parseObject(arguments));
            return functionCall;
        }
    }

}
