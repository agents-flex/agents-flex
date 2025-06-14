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
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String fullContent;
    private String reasoningContent;
    // functionName: <argName: argValue>
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

    @Override
    public String toString() {
        return "AiMessage{" +
            "index=" + index +
            ", status=" + status +
            ", promptTokens=" + promptTokens +
            ", completionTokens=" + completionTokens +
            ", totalTokens=" + totalTokens +
            ", fullContent='" + fullContent + '\'' +
            ", calls=" + calls +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
