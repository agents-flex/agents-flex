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

import com.agentsflex.core.util.StringUtil;

import java.util.*;

public class AiMessage extends AbstractTextMessage<AiMessage> {

    /** 模型服务返回的响应唯一标识。 */
    private String id;
    /** 响应对象类型，如 {@code chat.completion} 或 {@code chat.completion.chunk}。 */
    private String object;
    /** 响应创建时间，通常为 Unix 秒级时间戳。 */
    private Long created;
    /** 模型服务实际使用的模型名称。 */
    private String model;
    /** 模型服务实际采用的服务层级，如 {@code default}。 */
    private String serviceTier;
    /** 模型后端配置的指纹，用于识别可能影响结果的后端变更。 */
    private String systemFingerprint;
    /** 消息角色，通常为 {@code assistant}。 */
    private String role;
    /** 模型拒绝回答时返回的拒绝说明。 */
    private String refusal;
    /** 模型返回的引用、标注等附加信息。 */
    private List<Object> annotations;
    /** 输出 Token 的对数概率信息，具体结构由模型服务决定。 */
    private Map<String, Object> logprobs;

    /** 当前候选结果在 {@code choices} 数组中的索引。 */
    private Integer index;
    /** 模型服务统计的输入 Token 数。 */
    private Integer promptTokens;
    /** 模型服务统计的输出 Token 数。 */
    private Integer completionTokens;
    /** 模型服务统计的 Token 总数。 */
    private Integer totalTokens;
    /** 输入 Token 的明细，如缓存 Token 数和音频 Token 数。 */
    private Map<String, Object> promptTokensDetails;
    /** 输出 Token 的明细，如推理 Token 数和预测 Token 数。 */
    private Map<String, Object> completionTokensDetails;
    /** 本地 Token 计数器估算的输入 Token 数。 */
    private Integer localPromptTokens;
    /** 本地 Token 计数器估算的输出 Token 数。 */
    private Integer localCompletionTokens;
    /** 本地 Token 计数器估算的 Token 总数。 */
    private Integer localTotalTokens;
    /** 当前响应或流式分片中的推理内容。 */
    private String reasoningContent;
    /** 模型请求调用的工具列表。 */
    private List<ToolCall> toolCalls;

    /** 完整的模型回复正文；流式响应中保存已累计的正文。 */
    private String fullContent;
    /** 完整的推理内容；流式响应中保存已累计的推理内容。 */
    private String fullReasoningContent;

    /**
     * LLM 响应结束的原因（如 "stop", "length", "tool_calls" 等），
     * 符合 OpenAI 等主流 API 的 finish_reason 语义。
     */
    private String finishReason;

    /**
     * 某些模型服务使用的停止原因字段，语义与 {@link #finishReason} 相同。
     */
    private String stopReason;

    /** 是否已经接收并组装完全部响应内容。 */
    private Boolean finished;

    public AiMessage() {
        super();
    }

    public AiMessage(String content) {
        this.content = content;
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
        if (delta.id != null) this.id = delta.id;
        if (delta.object != null) this.object = delta.object;
        if (delta.created != null) this.created = delta.created;
        if (delta.model != null) this.model = delta.model;
        if (delta.serviceTier != null) this.serviceTier = delta.serviceTier;
        if (delta.systemFingerprint != null) this.systemFingerprint = delta.systemFingerprint;
        if (delta.role != null) this.role = delta.role;
        if (delta.refusal != null) this.refusal = delta.refusal;
        if (delta.annotations != null) this.annotations = copyList(delta.annotations);
        if (delta.logprobs != null) this.logprobs = mergeMaps(this.logprobs, delta.logprobs);
        if (delta.index != null) this.index = delta.index;
        if (delta.promptTokens != null) this.promptTokens = delta.promptTokens;
        if (delta.completionTokens != null) this.completionTokens = delta.completionTokens;
        if (delta.totalTokens != null) this.totalTokens = delta.totalTokens;
        if (delta.promptTokensDetails != null) {
            this.promptTokensDetails = copyMap(delta.promptTokensDetails);
        }
        if (delta.completionTokensDetails != null) {
            this.completionTokensDetails = copyMap(delta.completionTokensDetails);
        }
        if (delta.localPromptTokens != null) this.localPromptTokens = delta.localPromptTokens;
        if (delta.localCompletionTokens != null) this.localCompletionTokens = delta.localCompletionTokens;
        if (delta.localTotalTokens != null) this.localTotalTokens = delta.localTotalTokens;
        if (delta.finishReason != null) this.finishReason = delta.finishReason;
        if (delta.stopReason != null) this.stopReason = delta.stopReason;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public Long getCreated() {
        return created;
    }

    public void setCreated(Long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getServiceTier() {
        return serviceTier;
    }

    public void setServiceTier(String serviceTier) {
        this.serviceTier = serviceTier;
    }

    public String getSystemFingerprint() {
        return systemFingerprint;
    }

    public void setSystemFingerprint(String systemFingerprint) {
        this.systemFingerprint = systemFingerprint;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getRefusal() {
        return refusal;
    }

    public void setRefusal(String refusal) {
        this.refusal = refusal;
    }

    public List<Object> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<?> annotations) {
        this.annotations = annotations == null ? null : copyList(annotations);
    }

    public Map<String, Object> getLogprobs() {
        return logprobs;
    }

    public void setLogprobs(Map<String, ?> logprobs) {
        this.logprobs = logprobs == null ? null : copyMap(logprobs);
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

        // 新增
        if (isNewCall(deltaCall, lastCall)) {
            this.toolCalls.add(deltaCall);
        }
        // 合并
        else {
            mergeSingleCall(lastCall, deltaCall);
        }
    }

    private boolean isNewCall(ToolCall deltaCall, ToolCall lastCall) {
        if (StringUtil.noText(deltaCall.getId()) && StringUtil.noText(deltaCall.getName())) {
            return false;
        }

        if (StringUtil.hasText(deltaCall.getId())) {
            return !deltaCall.getId().equals(lastCall.getId());
        }

        if (StringUtil.hasText(deltaCall.getName())) {
            return !deltaCall.getName().equals(lastCall.getName());
        }

        return false;
    }

    private void mergeSingleCall(ToolCall existing, ToolCall delta) {
        if (delta.getArguments() != null) {
            if (existing.getArguments() == null) {
                existing.setArguments("");
            }
            existing.setArguments(existing.getArguments() + delta.getArguments());
        }
        if (StringUtil.hasText(delta.getId())) {
            existing.setId(delta.getId());
        }
        if (StringUtil.hasText(delta.getName())) {
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

    public Map<String, Object> getPromptTokensDetails() {
        return promptTokensDetails;
    }

    public void setPromptTokensDetails(Map<String, ?> promptTokensDetails) {
        this.promptTokensDetails = promptTokensDetails == null ? null : copyMap(promptTokensDetails);
    }

    public Map<String, Object> getCompletionTokensDetails() {
        return completionTokensDetails;
    }

    public void setCompletionTokensDetails(Map<String, ?> completionTokensDetails) {
        this.completionTokensDetails = completionTokensDetails == null ? null : copyMap(completionTokensDetails);
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

        // Model response metadata
        copy.id = this.id;
        copy.object = this.object;
        copy.created = this.created;
        copy.model = this.model;
        copy.serviceTier = this.serviceTier;
        copy.systemFingerprint = this.systemFingerprint;
        copy.role = this.role;
        copy.refusal = this.refusal;
        copy.annotations = copyList(this.annotations);
        copy.logprobs = copyMap(this.logprobs);

        // Token 字段
        copy.index = this.index;
        copy.promptTokens = this.promptTokens;
        copy.completionTokens = this.completionTokens;
        copy.totalTokens = this.totalTokens;
        copy.promptTokensDetails = copyMap(this.promptTokensDetails);
        copy.completionTokensDetails = copyMap(this.completionTokensDetails);
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

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map) {
            return copyMap((Map<String, ?>) value);
        }
        if (value instanceof List) {
            return copyList((List<?>) value);
        }
        return value;
    }

    private static Map<String, Object> copyMap(Map<String, ?> source) {
        if (source == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    private static List<Object> copyList(List<?> source) {
        if (source == null) return null;
        List<Object> copy = new ArrayList<>(source.size());
        for (Object value : source) {
            copy.add(copyValue(value));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeMaps(Map<String, Object> current, Map<String, Object> delta) {
        if (current == null) return copyMap(delta);
        Map<String, Object> merged = copyMap(current);
        for (Map.Entry<String, Object> entry : delta.entrySet()) {
            Object oldValue = merged.get(entry.getKey());
            Object newValue = entry.getValue();
            if (oldValue instanceof List && newValue instanceof List) {
                List<Object> values = copyList((List<?>) oldValue);
                values.addAll(copyList((List<?>) newValue));
                merged.put(entry.getKey(), values);
            } else if (oldValue instanceof Map && newValue instanceof Map) {
                merged.put(entry.getKey(), mergeMaps((Map<String, Object>) oldValue,
                    (Map<String, Object>) newValue));
            } else {
                merged.put(entry.getKey(), copyValue(newValue));
            }
        }
        return merged;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
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


    /**
     * 判断当前对象是否为最终的 delta 对象。
     *
     * @return true 表示当前对象为最终的 delta 对象，否则为 false
     */
    public boolean isFinalDelta() {
        return (finished != null && finished);
    }

    public boolean hasFinishOrStopReason() {
        return StringUtil.hasText(this.finishReason)
            || StringUtil.hasText(this.stopReason);
    }


    @Override
    public String toString() {
        return "AiMessage{" +
            "id='" + id + '\'' +
            ", object='" + object + '\'' +
            ", created=" + created +
            ", model='" + model + '\'' +
            ", serviceTier='" + serviceTier + '\'' +
            ", systemFingerprint='" + systemFingerprint + '\'' +
            ", role='" + role + '\'' +
            ", refusal='" + refusal + '\'' +
            ", annotations=" + annotations +
            ", logprobs=" + logprobs +
            ", index=" + index +
            ", promptTokens=" + promptTokens +
            ", completionTokens=" + completionTokens +
            ", totalTokens=" + totalTokens +
            ", promptTokensDetails=" + promptTokensDetails +
            ", completionTokensDetails=" + completionTokensDetails +
            ", localPromptTokens=" + localPromptTokens +
            ", localCompletionTokens=" + localCompletionTokens +
            ", localTotalTokens=" + localTotalTokens +
            ", reasoningContent='" + reasoningContent + '\'' +
            ", toolCalls=" + toolCalls +
            ", fullContent='" + fullContent + '\'' +
            ", fullReasoningContent='" + fullReasoningContent + '\'' +
            ", finishReason='" + finishReason + '\'' +
            ", stopReason='" + stopReason + '\'' +
            ", finished=" + finished +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
