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
package com.agentsflex.llm.qwen;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class QwenLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    public static AiMessageParser getAiMessageParser() {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        aiMessageParser.setContentPath("$.output.choices[0].message.content");
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        aiMessageParser.setPromptTokensPath("$.usage.input_tokens");
        aiMessageParser.setCompletionTokensPath("$.usage.output_tokens");

        aiMessageParser.setStatusParser(content -> {
            Object finishReason = JSONPath.eval(content, "$.output.choices[0].finish_reason");
            if (finishReason != null) {
                return MessageStatus.END;
            }
            return MessageStatus.MIDDLE;
        });

        aiMessageParser.setCallsParser(content -> {
            JSONArray toolCalls = (JSONArray) JSONPath.eval(content, "$.output.choices[0].message.tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Collections.emptyList();
            }
            List<FunctionCall> functionCalls = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject jsonObject = toolCalls.getJSONObject(i);
                JSONObject functionObject = jsonObject.getJSONObject("function");
                if (functionObject != null) {
                    FunctionCall functionCall = new FunctionCall();
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


    public static String promptToPayload(Prompt prompt, QwenLlmConfig config, ChatOptions options) {
        // https://help.aliyun.com/zh/dashscope/developer-reference/api-details?spm=a2c4g.11186623.0.0.1ff6fa70jCgGRc#b8ebf6b25eul6

        List<Message> messages = prompt.toMessages();
        return Maps.of("model", config.getModel())
            .put("input", Maps.of("messages", promptFormat.toMessagesJsonObject(messages)))
            .put("parameters", Maps.of("result_format", "message")
                .putIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(messages.get(messages.size() - 1)))
                .putIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
                .putIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
                .putIfNotNull("top_p", options.getTopP())
                .putIfNotNull("top_k", options.getTopK())
                .putIfNotEmpty("stop", options.getStop())
            ).toJSON();
    }

    public static String promptToEnabledPayload(Document text, EmbeddingOptions options, QwenLlmConfig config) {
        // https://help.aliyun.com/zh/model-studio/developer-reference/text-embedding-synchronous-api?spm=a2c4g.11186623.0.nextDoc.100230b7arAV4X#e6bf7ae0fedrb
        List<String> list = new ArrayList<>();
        list.add(text.getContent());
        return Maps.of("model", config.getModel())
            .put("input", Maps.of("texts", list))
            .toJSON();
    }

    public static String createEmbedURL(QwenLlmConfig config) {
        return "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    }
}
