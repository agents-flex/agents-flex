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
package com.agentsflex.core.util;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.List;
import java.util.stream.Collectors;

public class LocalTokenCounter {

    private static final Encoding ENCODING =
        Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    /**
     * 基于完整对话历史，为最后一条 AiMessage 计算并设置本地 token 字段。
     *
     * @param messages  完整的对话消息列表（按顺序，包含 system/user/ai）
     * @param aiMessage 要设置 token 的 AiMessage（应为 messages 中的最后一条 assistant 消息）
     */
    public static void computeAndSetLocalTokens(List<Message> messages, AiMessage aiMessage) {
        if (messages == null || messages.isEmpty() || aiMessage == null) {
            return;
        }

        int promptTokenCount = countMessagesTokens(messages);
        int completionTokenCount = countAiMessageCompletionTokens(aiMessage);

        aiMessage.setLocalPromptTokens(promptTokenCount);
        aiMessage.setLocalCompletionTokens(completionTokenCount);
        aiMessage.setLocalTotalTokens(promptTokenCount + completionTokenCount);
    }

    /**
     * 计算 AI 消息的 completion 部分 token（模型生成内容）
     */
    public static int countAiMessageCompletionTokens(AiMessage aiMsg) {
        StringBuilder sb = new StringBuilder();

        if (aiMsg.getFullContent() != null) {
            sb.append(aiMsg.getFullContent());
        }
        if (aiMsg.getFullReasoningContent() != null) {
            sb.append(aiMsg.getFullReasoningContent());
        } else if (aiMsg.getReasoningContent() != null) {
            sb.append(aiMsg.getReasoningContent());
        }

        // TODO: 如需支持 function calls，需按 OpenAI 格式序列化后追加
        // 例如：{"name":"xxx","arguments":"{\"key\":\"value\"}"}

        String text = sb.toString();
        return text.isEmpty() ? 0 : ENCODING.countTokens(text);
    }

    /**
     * 计算一组消息（prompt 上下文）的总 token 数
     */
    public static int countMessagesTokens(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        // 简化处理：直接拼接所有 content
        // 注意：更精确的做法应按 OpenAI 的 message format 模拟（含 role、name 等）
        String fullPromptText = messages.stream()
            .map(msg -> {
                Object content = msg.getTextContent();
                return content != null ? content.toString() : "";
            })
            .collect(Collectors.joining("\n"));

        return fullPromptText.isEmpty() ? 0 : ENCODING.countTokens(fullPromptText);
    }
}
