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
package com.agentsflex.llm.moonshot;

import java.util.Optional;

import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.Maps;

public class MoonshotLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    public static AiMessageParser getAiMessageParser(Boolean isStream) {
        return DefaultAiMessageParser.getChatGPTMessageParser(isStream);
    }


    /**
     * 将给定的Prompt转换为特定的payload格式，用于与语言模型进行交互。
     *
     * @param prompt      需要转换为 payload 的 Prompt 对象，包含了对话的具体内容。
     * @param config      用于配置 Moonshot LLM 行为的配置对象，例如指定使用的模型。
     * @param isStream    指示 payload 是否应该以流的形式进行处理。
     * @param chatOptions 包含了对话选项的配置，如温度和最大令牌数等。
     * @return 返回一个字符串形式的payload，供进一步的处理或发送给语言模型。
     */
    public static String promptToPayload(Prompt prompt, MoonshotLlmConfig config, Boolean isStream, ChatOptions chatOptions) {
        // 构建payload的根结构，包括模型信息、流式处理标志、对话选项和格式化后的prompt消息。
        return Maps.of("model", Optional.ofNullable(chatOptions.getModel()).orElse(config.getModel()))
            .set("stream", isStream)
            .set("temperature", chatOptions.getTemperature())
            .set("max_tokens", chatOptions.getMaxTokens())
            .set("messages", promptFormat.toMessagesJsonObject(prompt.toMessages()))
            .toJSON();
    }
}
