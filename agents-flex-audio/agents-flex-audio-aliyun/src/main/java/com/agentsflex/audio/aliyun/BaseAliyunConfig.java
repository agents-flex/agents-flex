package com.agentsflex.audio.aliyun;

import com.alibaba.nls.client.AccessToken;

import java.io.IOException;

public class BaseAliyunConfig {

    private String accessKeyId;
    private String accessKeySecret;
    private String appKey;

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String createToken() {
        AccessToken accessToken = new AccessToken(accessKeyId, accessKeySecret);
        try {
            accessToken.apply();
            return accessToken.getToken();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
