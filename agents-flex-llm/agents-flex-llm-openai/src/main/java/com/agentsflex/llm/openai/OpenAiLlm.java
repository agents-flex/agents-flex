package com.agentsflex.llm.openai;

import com.agentsflex.client.BaseLlmClientListener;
import com.agentsflex.client.LlmClient;
import com.agentsflex.client.impl.SseClient;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.EmbeddingsListener;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.Prompt;

import java.util.HashMap;
import java.util.Map;

public class OpenAiLlm extends BaseLlm<OpenAiConfig> {

    public OpenAiLlm(OpenAiConfig config) {
        super(config);
    }


    @Override
    public LlmClient chat(Prompt prompt, ChatListener listener) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAiLLmUtil.promptToPayload(prompt, config);


        llmClient.start("https://api.openai.com/v1/chat/completions", headers, payload, new BaseLlmClientListener(this, listener) {
            @Override
            public void onMessage(LlmClient client, String response) {
                listener.onMessage(OpenAiLlm.this, new AiMessage());
            }
        });

        return llmClient;
    }


    @Override
    public LlmClient embeddings(Prompt prompt, EmbeddingsListener listener) {
        return null;
    }
}
