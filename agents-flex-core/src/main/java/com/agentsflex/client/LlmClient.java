package com.agentsflex.client;

import java.util.Map;

public interface LlmClient {

    void start(String url, Map<String, String> headers, String payload, LlmClientListener listener);

    void stop();
}
