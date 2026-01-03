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
package com.agentsflex.core.message;

import com.agentsflex.core.util.Metadata;

/**
 * 表示一个通用的消息（Message），通常用于与大语言模型（如 OpenAI）进行交互。
 * 消息内容可以是纯文本，也可以是多模态内容（例如：文本 + 图像等）。
 *
 * <p>该类继承自 {@link Metadata}，允许附加任意元数据（如来源、时间戳、追踪ID等）。
 *
 * @see #getTextContent()
 */
public abstract class Message extends Metadata {

    /**
     * 提取消息中的纯文本部分。
     *
     * <p>无论原始内容是纯文本还是多模态结构（如文本+图像），本方法应返回其中所有文本内容的合理合并结果。
     * 例如，在 OpenAI 多模态消息中，应遍历所有 {@code content} 元素，提取类型为 {@code text} 的部分并拼接。
     *
     * <p>返回的字符串应不包含非文本元素（如图像、音频等），且应保持原始文本的语义顺序（如适用）。
     * 若消息中无文本内容，则返回空字符串（{@code ""}），而非 {@code null}。
     *
     * <p>该方法主要用于日志记录、监控、文本分析等仅需文本语义的场景。
     *
     * @return 消息中提取出的纯文本内容。
     */
    public abstract String getTextContent();
}
