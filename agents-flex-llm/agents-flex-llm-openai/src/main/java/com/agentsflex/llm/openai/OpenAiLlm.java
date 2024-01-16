package com.agentsflex.llm.openai;

import com.agentsflex.client.BaseLlmClientListener;
import com.agentsflex.client.LlmClient;
import com.agentsflex.client.LlmClientListener;
import com.agentsflex.client.impl.SseClient;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.vector.VectorData;

import java.util.HashMap;
import java.util.Map;

public class OpenAiLlm extends BaseLlm<OpenAiLlmConfig> {

    public OpenAiLlm(OpenAiLlmConfig config) {
        super(config);
    }


    @Override
    public LlmClient chat(Prompt prompt, ChatListener listener) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAiLLmUtil.promptToPayload(prompt, config);

        LlmClientListener clientListener = new BaseLlmClientListener(this, listener, prompt, OpenAiLLmUtil::parseAiMessage);
        llmClient.start("https://api.openai.com/v1/chat/completions", headers, payload, clientListener);
        return llmClient;
    }


    @Override
    public VectorData embeddings(Prompt prompt) {
        return null;
    }
}
