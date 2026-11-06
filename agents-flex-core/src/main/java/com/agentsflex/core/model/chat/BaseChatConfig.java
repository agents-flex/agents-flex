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
package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.config.BaseModelConfig;

import java.util.Collections;
import java.util.List;

public class BaseChatConfig extends BaseModelConfig {

    protected Boolean supportImage;
    protected Boolean supportImageBase64Only; // 某些模型仅支持 base64 格式图片，比如 Ollama 部署的模型，或者某些本地化模型
    protected Boolean supportAudio;
    protected Boolean supportVideo;
    protected Boolean supportFile;
    protected Boolean supportTool;
    protected Boolean supportToolMessage;
    protected Boolean supportThinking;

    // 支持的工具列表，比如网络搜索等，比如 https://help.aliyun.com/zh/model-studio/web-search
    protected List<String> supportProviderTools;

    // 在调用工具的时候，是否需要推理结果作为 reasoning_content 传给大模型， 比如 Deepseek
    // 参考文档： https://api-docs.deepseek.com/zh-cn/guides/thinking_mode#%E5%B7%A5%E5%85%B7%E8%B0%83%E7%94%A8
    protected Boolean preserveThinkingEnable;

    protected boolean observabilityEnabled = true; // 默认开启
    protected Boolean thinkingEnabled; // null：使用服务端默认行为
    protected String thinkingProtocol = "none"; // "deepseek" "qwen" "ollama" "none"
    protected ThinkingProtocol thinkingProtocolHandler;
    protected boolean logEnabled = true;

    /**
     * 模型输入与输出合计的上下文容量；0 表示未知，不用于本地 Token 估算。
     */
    protected long contextWindowTokens;
    /**
     * 模型允许的最大输出 Token；0 表示未知。
     */
    protected long maxOutputTokens;


    protected boolean retryEnabled = true; // 默认开启错误重试
    protected int retryCount = 3;
    protected int retryInitialDelayMs = 1000;


    public boolean isLogEnabled() {
        return logEnabled;
    }

    /**
     * @return 模型总上下文容量；0 表示未知
     */
    public long getContextWindowTokens() {
        return contextWindowTokens;
    }

    /**
     * 设置模型总上下文容量。
     *
     * @param contextWindowTokens 输入与输出合计容量；0 表示未知
     */
    public void setContextWindowTokens(long contextWindowTokens) {
        if (contextWindowTokens < 0) {
            throw new IllegalArgumentException("contextWindowTokens must not be negative");
        }
        this.contextWindowTokens = contextWindowTokens;
    }

    /**
     * @return 模型最大输出 Token；0 表示未知
     */
    public long getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * 设置模型最大输出 Token。该值可作为计算可用输入容量时的默认预留量。
     *
     * @param maxOutputTokens 最大输出 Token；0 表示未知
     */
    public void setMaxOutputTokens(long maxOutputTokens) {
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must not be negative");
        }
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * 将配置转换为上下文管理使用的不可变模型能力对象。
     *
     * <p>模型标识优先使用 {@code provider:model}，未配置 provider 时退化为 model。</p>
     *
     * @return 当前配置对应的稳定模型能力
     */
    public ChatModelCapabilities toCapabilities() {
        String modelKey = null;
        if (provider != null && model != null) modelKey = provider + ":" + model;
        else if (model != null) modelKey = model;
        return new ChatModelCapabilities(modelKey, contextWindowTokens, maxOutputTokens);
    }

    public void setLogEnabled(boolean logEnabled) {
        this.logEnabled = logEnabled;
    }


    public Boolean getSupportImage() {
        return supportImage;
    }

    public void setSupportImage(Boolean supportImage) {
        this.supportImage = supportImage;
    }

    public boolean isSupportImage() {
        return supportImage != null && supportImage;
    }

    public Boolean getSupportImageBase64Only() {
        return supportImageBase64Only;
    }

    public void setSupportImageBase64Only(Boolean supportImageBase64Only) {
        this.supportImageBase64Only = supportImageBase64Only;
    }

    public boolean isSupportImageBase64Only() {
        return supportImageBase64Only != null && supportImageBase64Only;
    }

    public Boolean getSupportAudio() {
        return supportAudio;
    }

    public void setSupportAudio(Boolean supportAudio) {
        this.supportAudio = supportAudio;
    }

    public boolean isSupportAudio() {
        return supportAudio != null && supportAudio;
    }

    public Boolean getSupportVideo() {
        return supportVideo;
    }

    public void setSupportVideo(Boolean supportVideo) {
        this.supportVideo = supportVideo;
    }

    public boolean isSupportVideo() {
        return supportVideo != null && supportVideo;
    }

    public Boolean getSupportFile() {
        return supportFile;
    }

    public void setSupportFile(Boolean supportFile) {
        this.supportFile = supportFile;
    }

    public boolean isSupportFile() {
        return supportFile != null && supportFile;
    }

    public void setSupportTool(Boolean supportTool) {
        this.supportTool = supportTool;
    }

    public Boolean getSupportTool() {
        return supportTool;
    }

    public boolean isSupportTool() {
        return supportTool == null || supportTool;
    }

    public Boolean getSupportToolMessage() {
        return supportToolMessage;
    }

    public void setSupportToolMessage(Boolean supportToolMessage) {
        this.supportToolMessage = supportToolMessage;
    }

    public boolean isSupportToolMessage() {
        return supportToolMessage == null || supportToolMessage;
    }

    public Boolean getSupportThinking() {
        return supportThinking;
    }

    public void setSupportThinking(Boolean supportThinking) {
        this.supportThinking = supportThinking;
    }

    public boolean isSupportThinking() {
        return supportThinking == null || supportThinking;
    }

    public List<String> getSupportProviderTools() {
        return supportProviderTools;
    }

    public void setSupportProviderTools(List<String> supportProviderTools) {
        this.supportProviderTools = supportProviderTools == null
            ? null : new java.util.ArrayList<>(supportProviderTools);
    }

    public void addSupportProviderTools(String... tools) {
        if (supportProviderTools == null) {
            supportProviderTools = new java.util.ArrayList<>();
        }
        Collections.addAll(supportProviderTools, tools);
    }

    public boolean isSupportProviderTools(String tool) {
        return supportProviderTools != null && supportProviderTools.contains(tool);
    }

    public Boolean getPreserveThinkingEnable() {
        return preserveThinkingEnable;
    }

    public void setPreserveThinkingEnable(Boolean preserveThinkingEnable) {
        this.preserveThinkingEnable = preserveThinkingEnable;
    }

    /**
     * 是否需要推理结果作为 reasoning_content 传给大模型
     * 比如 Deepseek 在工具调用的时候，需要推理结果作为 reasoning_content 传给大模型。
     * 此方法只返回显式配置；请求有效策略请使用 shouldPreserveThinking(options)。
     *
     * @return 默认值为 false
     */
    public boolean isPreserveThinkingEnable() {
        return preserveThinkingEnable != null && preserveThinkingEnable;
    }

    public boolean isThinkingEnabled() {
        return Boolean.TRUE.equals(thinkingEnabled);
    }

    public Boolean getThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(Boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    /**
     * 按字段继承：Options 显式值 > Config 默认值 > 服务端默认。
     */
    public Boolean resolveThinkingEnabled(ChatOptions options) {
        return options != null && options.getThinkingEnabled() != null
            ? options.getThinkingEnabled() : thinkingEnabled;
    }

    public ThinkingProtocol getThinkingProtocolHandler() {
        return thinkingProtocolHandler != null ? thinkingProtocolHandler : ThinkingProtocols.resolve(thinkingProtocol);
    }

    /**
     * 自定义网关协议无需替换整个请求 Builder。
     */
    public void setThinkingProtocolHandler(ThinkingProtocol handler) {
        this.thinkingProtocolHandler = handler;
    }

    public boolean shouldPreserveThinking(ChatOptions options) {
        if (preserveThinkingEnable != null) return preserveThinkingEnable;
        return isSupportThinking()
            && getThinkingProtocolHandler().preserveReasoningContent(resolveThinkingEnabled(options));
    }

    public String getThinkingProtocol() {
        return thinkingProtocol;
    }

    public void setThinkingProtocol(String thinkingProtocol) {
        this.thinkingProtocol = thinkingProtocol;
    }

    public boolean isObservabilityEnabled() {
        return observabilityEnabled;
    }

    public void setObservabilityEnabled(boolean observabilityEnabled) {
        this.observabilityEnabled = observabilityEnabled;
    }

    public boolean isRetryEnabled() {
        return retryEnabled;
    }

    public void setRetryEnabled(boolean retryEnabled) {
        this.retryEnabled = retryEnabled;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getRetryInitialDelayMs() {
        return retryInitialDelayMs;
    }

    public void setRetryInitialDelayMs(int retryInitialDelayMs) {
        this.retryInitialDelayMs = retryInitialDelayMs;
    }


    @Override
    public String toString() {
        return "ChatConfig{" +
            "supportImage=" + supportImage +
            ", supportImageBase64Only=" + supportImageBase64Only +
            ", supportAudio=" + supportAudio +
            ", supportVideo=" + supportVideo +
            ", supportTool=" + supportTool +
            ", supportToolMessage=" + supportToolMessage +
            ", supportThinking=" + supportThinking +
            ", supportProviderTools=" + supportProviderTools +
            ", preserveThinkingEnable=" + preserveThinkingEnable +
            ", observabilityEnabled=" + observabilityEnabled +
            ", thinkingEnabled=" + thinkingEnabled +
            ", thinkingProtocol='" + thinkingProtocol + '\'' +
            ", logEnabled=" + logEnabled +
            ", contextWindowTokens=" + contextWindowTokens +
            ", maxOutputTokens=" + maxOutputTokens +
            ", retryEnabled=" + retryEnabled +
            ", retryCount=" + retryCount +
            ", retryInitialDelayMs=" + retryInitialDelayMs +
            ", provider='" + provider + '\'' +
            ", endpoint='" + endpoint + '\'' +
            ", requestPath='" + requestPath + '\'' +
            ", model='" + model + '\'' +
            ", apiKey='" + apiKey + '\'' +
            ", customProperties=" + customProperties +
            '}';
    }
}
