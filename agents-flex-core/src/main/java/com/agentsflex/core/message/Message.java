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

import java.util.HashMap;
import java.util.UUID;

/**
 * 表示对话时间线中的通用消息。
 *
 * <p>消息通常用于与大语言模型交互，也可以通过 {@link #setModelVisible(boolean)} 标记为只供页面
 * 展示。消息内容可以是纯文本或多模态内容（例如文本、图像、音频和文件）。</p>
 *
 * <p>该类继承自 {@link Metadata}，允许附加任意元数据（如来源、时间戳、追踪ID等）。
 *
 * @see #getTextContent()
 */
public abstract class Message extends Metadata {

    /**
     * 对话存储中用于幂等追加和更新的稳定消息 ID；复制消息时保持不变。
     */
    private String messageId = UUID.randomUUID().toString();
    /**
     * 是否允许把该消息发送给模型；页面控制消息应设置为 false。
     */
    private boolean modelVisible = true;
    /**
     * 对话存储执行受控更新时使用的乐观版本；普通追加消息保持为 0。
     */
    private long version;

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public boolean isModelVisible() {
        return modelVisible;
    }

    public void setModelVisible(boolean modelVisible) {
        this.modelVisible = modelVisible;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    /**
     * 复制所有 Message 子类共享的存储属性和元数据。
     */
    protected <T extends Message> T copyMessageStateTo(T target) {
        target.setMessageId(messageId);
        target.setModelVisible(modelVisible);
        target.setVersion(version);
        if (metadataMap != null) {
            target.metadataMap = new HashMap<>(metadataMap);
        }
        return target;
    }

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
