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
