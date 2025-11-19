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
import com.agentsflex.core.message.MessageFormat;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.message.OpenAIMessageFormat;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson2.JSONPath;

import java.util.List;
import java.util.Map;

/**
 * @author yulsh
 */
public class CozeLlmUtil {

    private static final MessageFormat MESSAGE_FORMAT = new OpenAIMessageFormat() {
        @Override
        protected void buildMessageContent(Message message, Map<String, Object> map) {
            map.put("content_type", "text");
            super.buildMessageContent(message, map);
        }
    };

    public static AiMessageParser getAiMessageParser() {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        aiMessageParser.setContentPath("$.content");
        aiMessageParser.setTotalTokensPath("$.usage.token_count");
        aiMessageParser.setCompletionTokensPath("$.usage.output_count");
        aiMessageParser.setPromptTokensPath("$.usage.input_count");

        aiMessageParser.setStatusParser(content -> {
            Boolean done = (Boolean) JSONPath.eval(content, "$.done");
            if (done != null && done) {
                return MessageStatus.END;
            }
            return MessageStatus.MIDDLE;
        });
        return aiMessageParser;
    }


    public static String promptToPayload(Prompt prompt, String botId, String userId, Map<String, String> customVariables, boolean stream) {
        List<Message> messages = prompt.getMessages();
        return Maps.of()
            .set("bot_id", botId)
            .set("user_id", userId)
            .set("auto_save_history", true)
            .set("additional_messages", MESSAGE_FORMAT.toMessagesJsonObject(messages))
            .set("stream", stream)
            .setIf(customVariables != null, "custom_variables", customVariables)
            .toJSON();
    }

}
