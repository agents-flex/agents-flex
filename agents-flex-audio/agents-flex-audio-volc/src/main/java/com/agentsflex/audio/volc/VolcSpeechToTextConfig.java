package com.agentsflex.audio.volc;

public class VolcSpeechToTextConfig extends BaseVolcConfig {
    private static final String DEFAULT_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash";
    private String url = DEFAULT_URL;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
