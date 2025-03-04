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
package com.agentsflex.core.parser.impl;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.JSONObjectParser;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class DefaultAiMessageParser implements AiMessageParser {

    private String contentPath;
    private String indexPath;
    private String totalTokensPath;
    private String promptTokensPath;
    private String completionTokensPath;
    private JSONObjectParser<MessageStatus> statusParser;
    private JSONObjectParser<List<FunctionCall>> callsParser;

    public String getContentPath() {
        return contentPath;
    }

    public void setContentPath(String contentPath) {
        this.contentPath = contentPath;
    }

    public String getIndexPath() {
        return indexPath;
    }

    public void setIndexPath(String indexPath) {
        this.indexPath = indexPath;
    }

    public String getTotalTokensPath() {
        return totalTokensPath;
    }

    public void setTotalTokensPath(String totalTokensPath) {
        this.totalTokensPath = totalTokensPath;
    }

    public String getPromptTokensPath() {
        return promptTokensPath;
    }

    public void setPromptTokensPath(String promptTokensPath) {
        this.promptTokensPath = promptTokensPath;
    }

    public String getCompletionTokensPath() {
        return completionTokensPath;
    }

    public void setCompletionTokensPath(String completionTokensPath) {
        this.completionTokensPath = completionTokensPath;
    }

    public JSONObjectParser<MessageStatus> getStatusParser() {
        return statusParser;
    }

    public void setStatusParser(JSONObjectParser<MessageStatus> statusParser) {
        this.statusParser = statusParser;
    }

    public JSONObjectParser<List<FunctionCall>> getCallsParser() {
        return callsParser;
    }

    public void setCallsParser(JSONObjectParser<List<FunctionCall>> callsParser) {
        this.callsParser = callsParser;
    }

    @Override
    public AiMessage  parse(JSONObject rootJson) {
        AiMessage aiMessage = new AiMessage();

        if (StringUtil.hasText(this.contentPath)) {
            aiMessage.setContent((String) JSONPath.eval(rootJson, this.contentPath));
        }

        if (StringUtil.hasText(this.indexPath)) {
            aiMessage.setIndex((Integer) JSONPath.eval(rootJson, this.indexPath));
        }


        if (StringUtil.hasText(promptTokensPath)) {
            aiMessage.setPromptTokens((Integer) JSONPath.eval(rootJson, this.promptTokensPath));
        }

        if (StringUtil.hasText(completionTokensPath)) {
            aiMessage.setCompletionTokens((Integer) JSONPath.eval(rootJson, this.completionTokensPath));
        }

        if (StringUtil.hasText(this.totalTokensPath)) {
            aiMessage.setTotalTokens((Integer) JSONPath.eval(rootJson, this.totalTokensPath));
        }
        //some LLMs like Ollama not response the total tokens
        else if (aiMessage.getPromptTokens() != null && aiMessage.getCompletionTokens() != null) {
            aiMessage.setTotalTokens(aiMessage.getPromptTokens() + aiMessage.getCompletionTokens());
        }

        if (this.statusParser != null) {
            aiMessage.setStatus(this.statusParser.parse(rootJson));
        }

        if (callsParser != null) {
            aiMessage.setCalls(callsParser.parse(rootJson));
        }

        return aiMessage;
    }


    public static DefaultAiMessageParser getChatGPTMessageParser(boolean isStream) {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        if (isStream) {
            aiMessageParser.setContentPath("$.choices[0].delta.content");
        } else {
            aiMessageParser.setContentPath("$.choices[0].message.content");
        }

        aiMessageParser.setIndexPath("$.choices[0].index");
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        aiMessageParser.setPromptTokensPath("$.usage.prompt_tokens");
        aiMessageParser.setCompletionTokensPath("$.usage.completion_tokens");

        aiMessageParser.setStatusParser(content -> {
            Object finishReason = JSONPath.eval(content, "$.choices[0].finish_reason");
            if (finishReason != null) {
                return MessageStatus.END;
            }
            return MessageStatus.MIDDLE;
        });

        aiMessageParser.setCallsParser(content -> {
            JSONArray toolCalls = (JSONArray) JSONPath.eval(content, "$.choices[0].message.tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Collections.emptyList();
            }
            List<FunctionCall> functionCalls = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject jsonObject = toolCalls.getJSONObject(i);
                JSONObject functionObject = jsonObject.getJSONObject("function");
                if (functionObject != null) {
                    FunctionCall functionCall = new FunctionCall();
                    functionCall.setId(jsonObject.getString("id"));
                    functionCall.setName(functionObject.getString("name"));
                    Object arguments = functionObject.get("arguments");
                    if (arguments instanceof Map) {
                        //noinspection unchecked
                        functionCall.setArgs((Map<String, Object>) arguments);
                    } else if (arguments instanceof String) {
                        //noinspection unchecked
                        functionCall.setArgs(JSON.parseObject(arguments.toString(), Map.class));
                    }
                    functionCalls.add(functionCall);
                }
            }
            return functionCalls;
        });

        return aiMessageParser;
    }
}
