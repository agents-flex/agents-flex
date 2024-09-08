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
package com.agentsflex.llm.coze;

import com.agentsflex.core.message.Message;
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

import java.util.Map;

/**
 * @author yulsh
 */
public class CozeLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat() {
        @Override
        protected void buildMessageContent(Message message, Map<String, Object> map) {
            map.put("content_type", "text");
            super.buildMessageContent(message, map);
        }
    };

    public static AiMessageParser getAiMessageParser() {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        aiMessageParser.setContentPath("$.content");
        aiMessageParser.setStatusPath("$.done");
        aiMessageParser.setStatusParser(content -> {
            if (content != null && (boolean) content) {
                return MessageStatus.END;
            }
            return MessageStatus.MIDDLE;
        });

        aiMessageParser.setTotalTokensPath("$.usage.token_count");
        aiMessageParser.setCompletionTokensPath("$.usage.output_count");
        aiMessageParser.setPromptTokensPath("$.usage.input_count");
        return aiMessageParser;
    }

    public static FunctionMessageParser getFunctionMessageParser() {
        DefaultFunctionMessageParser functionMessageParser = new DefaultFunctionMessageParser();
        // TODO: function params
        functionMessageParser.setFunctionArgsParser(JSON::parseObject);
        return functionMessageParser;
    }

    public static String promptToPayload(Prompt<?> prompt, String botId, String userId, Map<String, String> customVariables, boolean stream) {
        return Maps.of()
            .put("bot_id", botId)
            .put("user_id", userId)
            .put("auto_save_history", true)
            .put("additional_messages", promptFormat.toMessagesJsonObject(prompt))
            .put("stream", stream)
            .putIf(customVariables != null, "custom_variables", customVariables)
            .toJSON();
    }

}
