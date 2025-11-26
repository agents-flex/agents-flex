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
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.JSONArrayParser;
import com.agentsflex.core.util.JSONUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class DefaultAiMessageParser implements AiMessageParser<JSONObject> {

    private JSONPath contentPath;
    private JSONPath deltaContentPath;
    private JSONPath reasoningContentPath;
    private JSONPath deltaReasoningContentPath;
    private JSONPath indexPath;
    private JSONPath totalTokensPath;
    private JSONPath promptTokensPath;
    private JSONPath completionTokensPath;
    private JSONPath finishReasonPath;
    private JSONPath stopReasonPath;

    private JSONPath toolCallsJsonPath;
    private JSONPath deltaToolCallsJsonPath;

    private JSONArrayParser<List<ToolCall>> callsParser;

    public JSONPath getContentPath() {
        return contentPath;
    }

    public void setContentPath(JSONPath contentPath) {
        this.contentPath = contentPath;
    }

    public JSONPath getDeltaContentPath() {
        return deltaContentPath;
    }

    public void setDeltaContentPath(JSONPath deltaContentPath) {
        this.deltaContentPath = deltaContentPath;
    }

    public JSONPath getReasoningContentPath() {
        return reasoningContentPath;
    }

    public void setReasoningContentPath(JSONPath reasoningContentPath) {
        this.reasoningContentPath = reasoningContentPath;
    }

    public JSONPath getDeltaReasoningContentPath() {
        return deltaReasoningContentPath;
    }

    public void setDeltaReasoningContentPath(JSONPath deltaReasoningContentPath) {
        this.deltaReasoningContentPath = deltaReasoningContentPath;
    }

    public JSONPath getIndexPath() {
        return indexPath;
    }

    public void setIndexPath(JSONPath indexPath) {
        this.indexPath = indexPath;
    }

    public JSONPath getTotalTokensPath() {
        return totalTokensPath;
    }

    public void setTotalTokensPath(JSONPath totalTokensPath) {
        this.totalTokensPath = totalTokensPath;
    }

    public JSONPath getPromptTokensPath() {
        return promptTokensPath;
    }

    public void setPromptTokensPath(JSONPath promptTokensPath) {
        this.promptTokensPath = promptTokensPath;
    }

    public JSONPath getCompletionTokensPath() {
        return completionTokensPath;
    }

    public void setCompletionTokensPath(JSONPath completionTokensPath) {
        this.completionTokensPath = completionTokensPath;
    }

    public JSONPath getFinishReasonPath() {
        return finishReasonPath;
    }

    public void setFinishReasonPath(JSONPath finishReasonPath) {
        this.finishReasonPath = finishReasonPath;
    }

    public JSONPath getStopReasonPath() {
        return stopReasonPath;
    }

    public void setStopReasonPath(JSONPath stopReasonPath) {
        this.stopReasonPath = stopReasonPath;
    }

    public JSONPath getToolCallsJsonPath() {
        return toolCallsJsonPath;
    }

    public void setToolCallsJsonPath(JSONPath toolCallsJsonPath) {
        this.toolCallsJsonPath = toolCallsJsonPath;
    }

    public JSONPath getDeltaToolCallsJsonPath() {
        return deltaToolCallsJsonPath;
    }

    public void setDeltaToolCallsJsonPath(JSONPath deltaToolCallsJsonPath) {
        this.deltaToolCallsJsonPath = deltaToolCallsJsonPath;
    }

    public JSONArrayParser<List<ToolCall>> getCallsParser() {
        return callsParser;
    }

    public void setCallsParser(JSONArrayParser<List<ToolCall>> callsParser) {
        this.callsParser = callsParser;
    }

    @Override
    public AiMessage parse(JSONObject rootJson, ChatContext context) {
        AiMessage aiMessage = new AiMessage();

        JSONArray toolCallsJsonArray = null;
        if (context.getOptions().isStreaming()) {
            if (this.deltaContentPath != null) {
                aiMessage.setContent((String) this.deltaContentPath.eval(rootJson));
            }
            if (this.deltaReasoningContentPath != null) {
                aiMessage.setReasoningContent((String) this.deltaReasoningContentPath.eval(rootJson));
            }
            if (this.deltaToolCallsJsonPath != null) {
                toolCallsJsonArray = (JSONArray) this.deltaToolCallsJsonPath.eval(rootJson);
            }
        } else {
            if (this.contentPath != null) {
                aiMessage.setContent((String) this.contentPath.eval(rootJson));
            }

            if (this.reasoningContentPath != null) {
                aiMessage.setReasoningContent((String) this.reasoningContentPath.eval(rootJson));
            }
            if (this.toolCallsJsonPath != null) {
                toolCallsJsonArray = (JSONArray) this.toolCallsJsonPath.eval(rootJson);
            }
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

        if (this.finishReasonPath != null) {
            aiMessage.setFinishReason((String) this.finishReasonPath.eval(rootJson));
        }

        if (this.stopReasonPath != null) {
            aiMessage.setStopReason((String) this.stopReasonPath.eval(rootJson));
        }

        if (this.totalTokensPath != null) {
            aiMessage.setTotalTokens((Integer) this.totalTokensPath.eval(rootJson));
        }
        //some LLMs like Ollama not response the total tokens
        else if (aiMessage.getPromptTokens() != null && aiMessage.getCompletionTokens() != null) {
            aiMessage.setTotalTokens(aiMessage.getPromptTokens() + aiMessage.getCompletionTokens());
        }

        if (toolCallsJsonArray != null && this.callsParser != null) {
            aiMessage.setToolCalls(this.callsParser.parse(toolCallsJsonArray));
        }

        return aiMessage;
    }


    public static DefaultAiMessageParser getOpenAIMessageParser() {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        aiMessageParser.setContentPath(JSONUtil.getJsonPath("$.choices[0].message.content"));
        aiMessageParser.setDeltaContentPath(JSONUtil.getJsonPath("$.choices[0].delta.content"));

        aiMessageParser.setReasoningContentPath(JSONUtil.getJsonPath("$.choices[0].message.reasoning_content"));
        aiMessageParser.setDeltaReasoningContentPath(JSONUtil.getJsonPath("$.choices[0].delta.reasoning_content"));

        aiMessageParser.setIndexPath(JSONUtil.getJsonPath("$.choices[0].index"));
        aiMessageParser.setTotalTokensPath(JSONUtil.getJsonPath("$.usage.total_tokens"));
        aiMessageParser.setPromptTokensPath(JSONUtil.getJsonPath("$.usage.prompt_tokens"));
        aiMessageParser.setCompletionTokensPath(JSONUtil.getJsonPath("$.usage.completion_tokens"));
        aiMessageParser.setFinishReasonPath(JSONUtil.getJsonPath("$.choices[0].finish_reason"));
        aiMessageParser.setStopReasonPath(JSONUtil.getJsonPath("$.choices[0].stop_reason"));

        aiMessageParser.setToolCallsJsonPath(JSONUtil.getJsonPath("$.choices[0].message.tool_calls"));
        aiMessageParser.setDeltaToolCallsJsonPath(JSONUtil.getJsonPath("$.choices[0].delta.tool_calls"));

        aiMessageParser.setCallsParser(toolCalls -> {
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Collections.emptyList();
            }
            List<ToolCall> toolInfos = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject jsonObject = toolCalls.getJSONObject(i);
                JSONObject functionObject = jsonObject.getJSONObject("function");
                if (functionObject != null) {
                    ToolCall toolCall = new ToolCall();
                    toolCall.setId(jsonObject.getString("id"));
                    toolCall.setName(functionObject.getString("name"));
                    Object arguments = functionObject.get("arguments");
                    if (arguments instanceof Map) {
                        toolCall.setArguments(JSON.toJSONString(arguments));
                    } else if (arguments instanceof String) {
                        toolCall.setArguments((String) arguments);
                    }
                    toolInfos.add(toolCall);
                }
            }
            return toolInfos;
        });

        return aiMessageParser;
    }
}
