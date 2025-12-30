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
package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.config.BaseModelConfig;

public class ChatConfig extends BaseModelConfig {

    protected Boolean supportImage;
    protected Boolean supportImageBase64Only; // 某些模型仅支持 base64 格式图片，比如 Ollama 部署的模型，或者某些本地化模型
    protected Boolean supportAudio;
    protected Boolean supportVideo;
    protected Boolean supportTool;
    protected Boolean supportToolMessage;
    protected Boolean supportThinking;

    protected boolean observabilityEnabled = true; // 默认开启
    protected boolean thinkingEnabled = false; // 默认关闭
    protected boolean logEnabled = true;


    protected boolean retryEnabled = true; // 默认开启错误重试
    protected int retryCount = 3;
    protected int retryInitialDelayMs = 1000;


    public boolean isLogEnabled() {
        return logEnabled;
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
        return supportImage == null || supportImage;
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
        return supportAudio == null || supportAudio;
    }

    public Boolean getSupportVideo() {
        return supportVideo;
    }

    public void setSupportVideo(Boolean supportVideo) {
        this.supportVideo = supportVideo;
    }

    public boolean isSupportVideo() {
        return supportVideo == null || supportVideo;
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

    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
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
}
