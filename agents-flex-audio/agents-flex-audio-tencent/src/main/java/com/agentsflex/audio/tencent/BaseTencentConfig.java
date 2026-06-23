package com.agentsflex.audio.tencent;


import com.tencent.core.ws.Credential;

public class BaseTencentConfig {

    private String secretId;
    private String secretKey;
    private String appId;

    public String getSecretId() {
        return secretId;
    }

    public void setSecretId(String secretId) {
        this.secretId = secretId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public Credential toCredential() {
        return new Credential(appId, secretId, secretKey);
    }

}
