package com.agentsflex.core.model.client;

import java.util.Map;

public class ChatRequestInfo {
    private String url;
    private Map<String, String> headers;
    private String body; // JSON 字符串

    public ChatRequestInfo(String url, Map<String, String> headers, String body) {
        this.url = url;
        this.headers = headers;
        this.body = body;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public void addHeader(String key, String value) {
        if (this.headers == null) {
            this.headers = new java.util.HashMap<>();
        }
        this.headers.put(key, value);
    }

    public void addHeaders(Map<String, String> headers) {
        if (this.headers == null) {
            this.headers = new java.util.HashMap<>();
        }
        this.headers.putAll(headers);
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
