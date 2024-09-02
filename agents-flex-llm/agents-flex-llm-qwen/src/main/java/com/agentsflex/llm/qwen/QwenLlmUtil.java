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
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.FunctionMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.parser.impl.DefaultFunctionMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.List;

public class QwenLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    public static AiMessageParser getAiMessageParser() {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        aiMessageParser.setContentPath("$.output.choices[0].message.content");
        aiMessageParser.setStatusPath("$.output.choices[0].finish_reason");
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        aiMessageParser.setStatusParser(content -> parseMessageStatus((String) content));
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        aiMessageParser.setPromptTokensPath("$.usage.input_tokens");
        aiMessageParser.setCompletionTokensPath("$.usage.output_tokens");
        return aiMessageParser;
    }


    public static FunctionMessageParser getFunctionMessageParser() {
        DefaultFunctionMessageParser functionMessageParser = new DefaultFunctionMessageParser();
        functionMessageParser.setFunctionNamePath("$.output.choices[0].message.tool_calls[0].function.name");
        functionMessageParser.setFunctionArgsPath("$.output.choices[0].message.tool_calls[0].function.arguments");
        functionMessageParser.setFunctionArgsParser(JSON::parseObject);
        return functionMessageParser;
    }


    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


    public static String promptToPayload(Prompt<?> prompt, QwenLlmConfig config, ChatOptions options) {
        // https://help.aliyun.com/zh/dashscope/developer-reference/api-details?spm=a2c4g.11186623.0.0.1ff6fa70jCgGRc#b8ebf6b25eul6
        Maps.Builder root = Maps.of("model", config.getModel())
            .put("input", Maps.of("messages", promptFormat.toMessagesJsonObject(prompt)))
            .put("parameters", Maps.of("result_format", "message")
                .putIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(prompt))
                .putIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
                .putIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
                .putIfNotNull("top_p", options.getTopP())
                .putIfNotNull("top_k", options.getTopK())
                .putIfNotEmpty("stop", options.getStop())
            );


        return JSON.toJSONString(root.build());
    }

    public static String promptToEnabledPayload(Document text, EmbeddingOptions options, QwenLlmConfig config) {
        // https://help.aliyun.com/zh/model-studio/developer-reference/text-embedding-synchronous-api?spm=a2c4g.11186623.0.nextDoc.100230b7arAV4X#e6bf7ae0fedrb

        List<String> list=new ArrayList<>();
        list.add(text.getContent());
        Maps.Builder root = Maps.of("model", config.getModel())
            .put("input", Maps.of("texts",list));
        return JSON.toJSONString(root.build());
    }

    public static String createEmbedURL(QwenLlmConfig config) {
        return "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    }
}
