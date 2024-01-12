package com.agentsflex.client;

import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.Llm;

public abstract class BaseLlmClientListener implements LlmClientListener{

    private final Llm llm;
    private final ChatListener chatListener;

    public BaseLlmClientListener(Llm llm,ChatListener chatListener) {
        this.llm = llm;
        this.chatListener = chatListener;
    }

    @Override
    public void onStart(LlmClient client) {
        chatListener.onStart(llm);
    }

    @Override
    public void onStop(LlmClient client) {
        chatListener.onStop(llm);
    }

    @Override
    public void onFailure(LlmClient client, Throwable throwable) {
        chatListener.onFailure(llm,throwable);
    }
}
