package com.agentsflex.client;

public interface LlmClientListener {

    void onStart(LlmClient client);

    void onMessage(LlmClient client,String response);

    void onStop(LlmClient client);

    void onFailure(LlmClient client, Throwable throwable);

}
