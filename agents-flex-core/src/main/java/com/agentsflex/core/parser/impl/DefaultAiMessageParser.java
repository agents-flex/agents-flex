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
import com.agentsflex.core.util.JSONUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class DefaultAiMessageParser implements AiMessageParser {

    private JSONPath contentPath;
    private JSONPath reasoningContentPath;
    private JSONPath indexPath;
    private JSONPath totalTokensPath;
    private JSONPath promptTokensPath;
    private JSONPath completionTokensPath;
    private JSONObjectParser<MessageStatus> statusParser;
    private JSONObjectParser<List<FunctionCall>> callsParser;

    public JSONPath getContentPath() {
        return contentPath;
    }

    public void setContentPath(String contentPath) {
        this.contentPath = JSONUtil.getJsonPath(contentPath);
    }

    public JSONPath getReasoningContentPath() {
        return reasoningContentPath;
    }

    public void setReasoningContentPath(String reasoningContentPath) {
        this.reasoningContentPath = JSONUtil.getJsonPath(reasoningContentPath);
    }

    public JSONPath getIndexPath() {
        return indexPath;
    }

    public void setIndexPath(String indexPath) {
        this.indexPath = JSONUtil.getJsonPath(indexPath);
    }

    public JSONPath getTotalTokensPath() {
        return totalTokensPath;
    }

    public void setTotalTokensPath(String totalTokensPath) {
        this.totalTokensPath = JSONUtil.getJsonPath(totalTokensPath);
    }

    public JSONPath getPromptTokensPath() {
        return promptTokensPath;
    }

    public void setPromptTokensPath(String promptTokensPath) {
        this.promptTokensPath = JSONUtil.getJsonPath(promptTokensPath);
    }

    public JSONPath getCompletionTokensPath() {
        return completionTokensPath;
    }

    public void setCompletionTokensPath(String completionTokensPath) {
        this.completionTokensPath = JSONUtil.getJsonPath(completionTokensPath);
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
    public AiMessage parse(JSONObject rootJson) {
        AiMessage aiMessage = new AiMessage();

        if (this.contentPath != null) {
            aiMessage.setContent((String) this.contentPath.eval(rootJson));
        }

        if (this.reasoningContentPath != null) {
            aiMessage.setReasoningContent((String) this.reasoningContentPath.eval(rootJson));
        }

        if (this.indexPath != null) {
            aiMessage.setIndex((Integer) this.indexPath.eval(rootJson));
        }


        if (this.promptTokensPath != null) {
            aiMessage.setPromptTokens((Integer) this.promptTokensPath.eval(rootJson));
        }

        if (this.completionTokensPath != null) {
            aiMessage.setCompletionTokens((Integer) this.completionTokensPath.eval(rootJson));
        }

        if (this.totalTokensPath != null) {
            aiMessage.setTotalTokens((Integer) this.totalTokensPath.eval(rootJson));
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


    public static DefaultAiMessageParser getOpenAIMessageParser(boolean isStream) {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        if (isStream) {
            aiMessageParser.setContentPath("$.choices[0].delta.content");
            aiMessageParser.setReasoningContentPath("$.choices[0].delta.reasoning_content");
        } else {
            aiMessageParser.setContentPath("$.choices[0].message.content");
            aiMessageParser.setReasoningContentPath("$.choices[0].message.reasoning_content");
        }

        aiMessageParser.setIndexPath("$.choices[0].index");
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        aiMessageParser.setPromptTokensPath("$.usage.prompt_tokens");
        aiMessageParser.setCompletionTokensPath("$.usage.completion_tokens");

        aiMessageParser.setStatusParser(content -> {
            Object finishReason = JSONUtil.getJsonPath("$.choices[0].finish_reason").eval(content);
            if (finishReason != null) {
                return MessageStatus.END;
            }
            return MessageStatus.MIDDLE;
        });

        aiMessageParser.setCallsParser(content -> {
            JSONArray toolCalls = (JSONArray) JSONUtil.getJsonPath("$.choices[0].message.tool_calls").eval(content);
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
                        functionCall.setArgs(JSON.parseObject((String) arguments, Map.class));
                    }
                    functionCalls.add(functionCall);
                }
            }
            return functionCalls;
        });

        return aiMessageParser;
    }
}
