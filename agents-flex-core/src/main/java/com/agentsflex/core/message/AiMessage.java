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
package com.agentsflex.core.message;


import java.util.List;

public class AiMessage extends AbstractTextMessage {

    private Integer index;
    private MessageStatus status;

    // API 返回的 token 信息（可选）
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    // 本地计算的 token 信息（新增）
    private Integer localPromptTokens;      // 对话历史（system + user + previous ai）的 token 数
    private Integer localCompletionTokens;  // 当前 AI 回复内容的 token 数
    private Integer localTotalTokens;       // 通常 = localPromptTokens + localCompletionTokens

    private String fullContent;
    private String reasoningContent;
    private List<FunctionCall> calls;
    private String fullReasoningContent;

    public AiMessage() {
        super();
    }

    public AiMessage(String content) {
        this.fullContent = content;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Integer getLocalPromptTokens() {
        return localPromptTokens;
    }

    public void setLocalPromptTokens(Integer localPromptTokens) {
        this.localPromptTokens = localPromptTokens;
    }

    public Integer getLocalCompletionTokens() {
        return localCompletionTokens;
    }

    public void setLocalCompletionTokens(Integer localCompletionTokens) {
        this.localCompletionTokens = localCompletionTokens;
    }

    public Integer getLocalTotalTokens() {
        return localTotalTokens;
    }

    public void setLocalTotalTokens(Integer localTotalTokens) {
        this.localTotalTokens = localTotalTokens;
    }

    public String getFullContent() {
        return fullContent;
    }

    public void setFullContent(String fullContent) {
        this.fullContent = fullContent;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    @Override
    public Object getMessageContent() {
        return getFullContent();
    }

    public List<FunctionCall> getCalls() {
        return calls;
    }

    public void setCalls(List<FunctionCall> calls) {
        this.calls = calls;
    }

    public String getFullReasoningContent() {
        return fullReasoningContent;
    }

    public void setFullReasoningContent(String fullReasoningContent) {
        this.fullReasoningContent = fullReasoningContent;
    }

    /**
     * 获取有效的总 token 数
     *
     * @return 有限返回模型计算的 token 数；否则返回本地计算的 token 数
     */
    public int getEffectiveTotalTokens() {
        if (this.totalTokens != null) {
            return this.totalTokens;
        }

        if (this.promptTokens != null && this.completionTokens != null) {
            return this.promptTokens + this.completionTokens;
        }

        if (this.localTotalTokens != null) {
            return this.localTotalTokens;
        }

        if (this.localPromptTokens != null && this.localCompletionTokens != null) {
            return this.localPromptTokens + this.localCompletionTokens;
        }

        return 0;
    }

    @Override
    public String toString() {
        return "AiMessage{" +
            "index=" + index +
            ", status=" + status +
            ", promptTokens=" + promptTokens +
            ", completionTokens=" + completionTokens +
            ", totalTokens=" + totalTokens +
            ", localPromptTokens=" + localPromptTokens +
            ", localCompletionTokens=" + localCompletionTokens +
            ", localTotalTokens=" + localTotalTokens +
            ", fullContent='" + fullContent + '\'' +
            ", reasoningContent='" + reasoningContent + '\'' +
            ", calls=" + calls +
            ", fullReasoningContent='" + fullReasoningContent + '\'' +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
