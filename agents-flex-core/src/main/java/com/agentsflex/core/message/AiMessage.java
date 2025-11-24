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

import com.agentsflex.core.util.StringUtil;

import java.util.*;

public class AiMessage extends AbstractTextMessage<AiMessage> {

    private Integer index;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer localPromptTokens;
    private Integer localCompletionTokens;
    private Integer localTotalTokens;
    private String reasoningContent;
    private List<ToolCall> toolCalls;

    private String fullContent;
    private String fullReasoningContent;

    /**
     * LLM 响应结束的原因（如 "stop", "length", "tool_calls" 等），
     * 符合 OpenAI 等主流 API 的 finish_reason 语义。
     */
    private String finishReason;

    // 同 reasoningContent，只是某些框架会返回这个字段，而不是 finishReason
    private String stopReason;

    private Boolean finished;

    public AiMessage() {
        super();
    }

    public AiMessage(String content) {
        this.fullContent = content;
    }

    public void merge(AiMessage delta) {
        if (delta.content != null) {
            if (this.content == null) this.content = "";
            this.content += delta.content;
            this.fullContent = this.content;
        }

        if (delta.reasoningContent != null) {
            if (this.reasoningContent == null) this.reasoningContent = "";
            this.reasoningContent += delta.reasoningContent;
            this.fullReasoningContent = this.reasoningContent;
        }

        if (delta.toolCalls != null && !delta.toolCalls.isEmpty()) {
            if (this.toolCalls == null) this.toolCalls = new ArrayList<>();
            mergeToolCalls(delta.toolCalls);
        }
        if (delta.index != null) this.index = delta.index;
        if (delta.promptTokens != null) this.promptTokens = delta.promptTokens;
        if (delta.completionTokens != null) this.completionTokens = delta.completionTokens;
        if (delta.totalTokens != null) this.totalTokens = delta.totalTokens;
        if (delta.localPromptTokens != null) this.localPromptTokens = delta.localPromptTokens;
        if (delta.localCompletionTokens != null) this.localCompletionTokens = delta.localCompletionTokens;
        if (delta.localTotalTokens != null) this.localTotalTokens = delta.localTotalTokens;
        if (delta.finishReason != null) this.finishReason = delta.finishReason;
        if (delta.stopReason != null) this.stopReason = delta.stopReason;
    }

    private void mergeToolCalls(List<ToolCall> deltaCalls) {
        if (deltaCalls == null || deltaCalls.isEmpty()) return;

        if (this.toolCalls == null || this.toolCalls.isEmpty()) {
            this.toolCalls = new ArrayList<>(deltaCalls);
            return;
        }

        ToolCall lastCall = this.toolCalls.get(this.toolCalls.size() - 1);

        // 正常情况下 delta 部分只有 1 条
        ToolCall deltaCall = deltaCalls.get(0);
        if (lastCall.getId() != null && deltaCall.getId() != null ||
            (lastCall.getName() != null && deltaCall.getName() != null)) {
            this.toolCalls.add(deltaCall);
        } else {
            mergeSingleCall(lastCall, deltaCall);
        }
    }

    private void mergeSingleCall(ToolCall existing, ToolCall delta) {
        if (delta.getArgsString() != null) {
            if (existing.getArgsString() == null) {
                existing.setArgsString("");
            }
            existing.setArgsString(existing.getArgsString() + delta.getArgsString());
        }
        if (delta.getId() != null) {
            existing.setId(delta.getId());
        }
        if (delta.getName() != null) {
            existing.setName(delta.getName());
        }
    }

    // ===== Getters & Setters (保持原有不变) =====
    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
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

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    @Override
    public String getTextContent() {
        return fullContent;
    }

    /**
     * 创建并返回当前对象的副本。
     *
     * @return 一个新的、内容相同但内存独立的对象
     */
    @Override
    public AiMessage copy() {
        AiMessage copy = new AiMessage();
        // 基本字段
        copy.content = this.content;
        copy.fullContent = this.fullContent;
        copy.reasoningContent = this.reasoningContent;
        copy.fullReasoningContent = this.fullReasoningContent;
        copy.finishReason = this.finishReason;
        copy.stopReason = this.stopReason;
        copy.finished = this.finished;

        // Token 字段
        copy.index = this.index;
        copy.promptTokens = this.promptTokens;
        copy.completionTokens = this.completionTokens;
        copy.totalTokens = this.totalTokens;
        copy.localPromptTokens = this.localPromptTokens;
        copy.localCompletionTokens = this.localCompletionTokens;
        copy.localTotalTokens = this.localTotalTokens;

        // ToolCalls: 深拷贝 List 和每个 ToolCall
        if (this.toolCalls != null) {
            copy.toolCalls = new ArrayList<>();
            for (ToolCall tc : this.toolCalls) {
                if (tc != null) {
                    copy.toolCalls.add(tc.copy());
                } else {
                    copy.toolCalls.add(null);
                }
            }
        }

        // Metadata
        if (this.metadataMap != null) {
            copy.metadataMap = new HashMap<>(this.metadataMap);
        }

        return copy;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getFullReasoningContent() {
        return fullReasoningContent;
    }

    public void setFullReasoningContent(String fullReasoningContent) {
        this.fullReasoningContent = fullReasoningContent;
    }

    public int getEffectiveTotalTokens() {
        if (this.totalTokens != null) return this.totalTokens;
        if (this.promptTokens != null && this.completionTokens != null) {
            return this.promptTokens + this.completionTokens;
        }
        if (this.localTotalTokens != null) return this.localTotalTokens;
        if (this.localPromptTokens != null && this.localCompletionTokens != null) {
            return this.localPromptTokens + this.localCompletionTokens;
        }
        return 0;
    }

    public Boolean getFinished() {
        return finished;
    }

    public void setFinished(Boolean finished) {
        this.finished = finished;
    }

    public boolean isLastMessage() {
        return (finished != null && finished)
            || StringUtil.hasText(this.finishReason)
            || StringUtil.hasText(this.stopReason);
    }

    @Override
    public String toString() {
        return "AiMessage{" +
            "index=" + index +
            ", promptTokens=" + promptTokens +
            ", completionTokens=" + completionTokens +
            ", totalTokens=" + totalTokens +
            ", localPromptTokens=" + localPromptTokens +
            ", localCompletionTokens=" + localCompletionTokens +
            ", localTotalTokens=" + localTotalTokens +
            ", reasoningContent='" + reasoningContent + '\'' +
            ", calls=" + toolCalls +
            ", fullContent='" + fullContent + '\'' +
            ", fullReasoningContent='" + fullReasoningContent + '\'' +
            ", finishReason='" + finishReason + '\'' +
            ", stopReason='" + stopReason + '\'' +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
