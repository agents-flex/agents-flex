package com.agentsflex.core.model.chat;

import com.agentsflex.core.prompt.Prompt;

import java.util.Map;

public class ChatContext {

    ChatConfig config;
    ChatOptions options;
    Prompt prompt;

    // 传输层请求信息（适用于远程模型）
    String requestUrl;
    Map<String, String> requestHeaders;
    String requestBody;

    // ===== Getters =====

    public ChatConfig getConfig() {
        return config;
    }

    public ChatOptions getOptions() {
        return options;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    /**
     * 获取请求目标地址。
     *
     * @return 地址字符串，协议相关
     */
    public String getRequestUrl() {
        return requestUrl;
    }

    /**
     * 获取传输层元数据。
     *
     * @return 元数据映射，协议相关
     */
    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }

    /**
     * 获取序列化后的请求体。
     *
     * @return 请求体字符串（通常为 JSON）
     */
    public String getRequestBody() {
        return requestBody;
    }

    // ===== Setters (允许拦截器修改) =====

    public void setRequestUrl(String requestUrl) {
        this.requestUrl = requestUrl;
    }

    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }
}
