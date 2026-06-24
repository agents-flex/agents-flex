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
package com.agentsflex.audio.volc;


import com.agentsflex.core.util.StringUtil;

public class VolcTextToSpeechConfig extends BaseVolcConfig {

    //seed-tts-2.0:豆包语音合成大模型2.0，支持使用豆包语音合成模型2.0音色
    private static final String RESOURCE_SEED_TTS_2_0 = "seed-tts-2.0";

    //seed-icl-2.0:豆包声音复刻大模型2.0，支持使用声音复刻接口克隆的音色，具体音色详见控制台>音色库
    private static final String RESOURCE_SEED_ICL_2_0 = "seed-icl-2.0";

    private String resourceId = RESOURCE_SEED_TTS_2_0;

    private String httpUrl = "https://openspeech.bytedance.com/api/v3/tts/unidirectional";
    private String webSocketUrl = "wss://openspeech.bytedance.com/api/v3/tts/bidirection";

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        if (StringUtil.noText(resourceId)) {
            throw new IllegalArgumentException("resourceId is empty.");
        }
        this.resourceId = resourceId;
    }

    public String getHttpUrl() {
        return httpUrl;
    }

    public void setHttpUrl(String httpUrl) {
        this.httpUrl = httpUrl;
    }

    public String getWebSocketUrl() {
        return webSocketUrl;
    }

    public void setWebSocketUrl(String webSocketUrl) {
        this.webSocketUrl = webSocketUrl;
    }


}
