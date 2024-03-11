/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.llm.openai;

import com.agentsflex.document.Document;
import com.agentsflex.message.MessageStatus;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.FunctionMessageParser;
import com.agentsflex.parser.impl.BaseAiMessageParser;
import com.agentsflex.parser.impl.BaseFunctionMessageParser;
import com.agentsflex.prompt.DefaultPromptFormat;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.prompt.PromptFormat;
import com.agentsflex.util.Maps;
import com.alibaba.fastjson.JSON;

public class OpenAiLLmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    public static AiMessageParser getAiMessageParser() {
        BaseAiMessageParser aiMessageParser = new BaseAiMessageParser();
        aiMessageParser.setContentPath("$.choices[0].delta.content");
        aiMessageParser.setIndexPath("$.choices[0].index");
        aiMessageParser.setStatusPath("$.choices[0].finish_reason");
        aiMessageParser.setStatusParser(content -> parseMessageStatus((String) content));
        return aiMessageParser;
    }


    public static FunctionMessageParser getFunctionMessageParser() {
        BaseFunctionMessageParser functionMessageParser = new BaseFunctionMessageParser();
        functionMessageParser.setFunctionNamePath("$.choices[0].message.tool_calls[0].function.name");
        functionMessageParser.setFunctionArgsPath("$.choices[0].message.tool_calls[0].function.arguments");
        functionMessageParser.setFunctionArgsParser(JSON::parseObject);
        return functionMessageParser;
    }


    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


    public static String promptToEmbeddingsPayload(Document text) {
        // https://platform.openai.com/docs/api-reference/making-requests
        String payload = "{\n" +
            "  \"input\": \"" + text.getContent() + "\",\n" +
            "  \"model\": \"text-embedding-ada-002\",\n" +
            "  \"encoding_format\": \"float\"\n" +
            "}";

        return payload;
    }


    public static String promptToPayload(Prompt prompt, OpenAiLlmConfig config) {
        Maps.Builder builder = Maps.of("model", config.getModel())
            .put("messages", promptFormat.toMessagesJsonKey(prompt))
            .putIfNotEmpty("tools", promptFormat.toFunctionsJsonKey(prompt))
            .putIfContainsKey("tools", "tool_choice", "auto")
            .putIfNotContainsKey("tools", "temperature", 0.7);

        return JSON.toJSONString(builder.build());
    }


}
