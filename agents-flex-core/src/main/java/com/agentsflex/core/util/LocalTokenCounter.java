/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
import com.agentsflex.core.message.ToolCall;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.List;

/**
 * 静态工具类：更精确的本地 token 统计工具，模拟 OpenAI ChatCompletion 格式。
 * 支持 function calls，按消息 role/name/内容序列化计数。
 */
public class LocalTokenCounter {

    // 静态 Encoder，线程安全
    private static Encoding ENCODING =
        Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public static void init(Encoding encoding) {
        ENCODING = encoding;
    }

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

        int promptTokens = countPromptTokens(messages);
        int completionTokens = countCompletionTokens(aiMessage);

        aiMessage.setLocalPromptTokens(promptTokens);
        aiMessage.setLocalCompletionTokens(completionTokens);
        aiMessage.setLocalTotalTokens(promptTokens + completionTokens);
    }

    /**
     * 计算 prompt token（对话历史）
     * 按 OpenAI ChatCompletion 格式，每条消息 role+content+name 固定 token
     */
    public static int countPromptTokens(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;

        int total = 0;
        for (Message msg : messages) {
            total += countMessageTokens(msg);
        }
        // 结尾通常多一个 token，模拟 OpenAI 格式
        total += 2;
        return total;
    }

    /**
     * 计算单条消息 token
     */
    private static int countMessageTokens(Message msg) {
        int count = 0;

        // role token
        count += 1;

        // content token
        Object content = msg.getTextContent();
        if (content != null) {
            count += ENCODING.countTokens(content.toString());
        }

        return count;
    }

    /**
     * 计算 AiMessage completion token
     * 包含 fullContent / reasoningContent / functionCall
     */
    public static int countCompletionTokens(AiMessage aiMsg) {
        if (aiMsg == null) return 0;

        int count = 0;

        // 生成的文本
        if (aiMsg.getFullContent() != null) {
            count += ENCODING.countTokens(aiMsg.getFullContent());
        }

        // 推理内容
        if (aiMsg.getFullReasoningContent() != null) {
            count += ENCODING.countTokens(aiMsg.getFullReasoningContent());
        } else if (aiMsg.getReasoningContent() != null) {
            count += ENCODING.countTokens(aiMsg.getReasoningContent());
        }

        // function call（按 JSON 序列化计算）
        List<ToolCall> toolCalls = aiMsg.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            for (ToolCall toolCall : toolCalls) {
                String serialized = toolCall.toJsonString();
                count += ENCODING.countTokens(serialized);
            }
        }

        // completion 固定尾部 token
        count += 2;
        return count;
    }

}
