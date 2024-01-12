package com.agentsflex.client.impl;

import com.agentsflex.client.LlmClient;
import com.agentsflex.client.LlmClientListener;

import java.util.Map;

public class HttpClient implements LlmClient {

    @Override
    public void start(String url, Map<String, String> headers, String payload, LlmClientListener listener) {

    }

    @Override
    public void stop() {

    }
}
