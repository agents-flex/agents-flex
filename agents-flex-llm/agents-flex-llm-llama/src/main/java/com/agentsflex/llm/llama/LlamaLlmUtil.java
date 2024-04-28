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
package com.agentsflex.llm.llama;

import com.agentsflex.message.MessageStatus;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.impl.BaseAiMessageParser;
import com.agentsflex.prompt.DefaultPromptFormat;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.prompt.PromptFormat;
import com.agentsflex.util.Maps;

public class LlamaLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();


    public static AiMessageParser getAiMessageParser() {
        BaseAiMessageParser aiMessageParser = new BaseAiMessageParser() ;
        aiMessageParser.setContentPath("$.choices[0].message.content");
        aiMessageParser.setIndexPath("$.choices[0].index");
        aiMessageParser.setStatusPath("$.choices[0].finish_reason");
        aiMessageParser.setStatusParser(content -> parseMessageStatus((String) content));
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        return aiMessageParser;
    }



    public static String promptToPayload(Prompt prompt, LlamaLlmConfig config, boolean stream) {
        return Maps.of("model", config.getModel())
            .put("messages", promptFormat.toMessagesJsonObject(prompt))
            .toJSON();

    }


    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


}
