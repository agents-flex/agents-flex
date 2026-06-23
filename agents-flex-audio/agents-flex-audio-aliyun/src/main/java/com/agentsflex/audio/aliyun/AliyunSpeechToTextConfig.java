package com.agentsflex.audio.aliyun;

public class AliyunSpeechToTextConfig extends BaseAliyunConfig {

    private static final String DEFAULT_ENDPOINT = "https://nls-gateway-cn-shanghai.aliyuncs.com";

    private String endpoint = DEFAULT_ENDPOINT;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
}
