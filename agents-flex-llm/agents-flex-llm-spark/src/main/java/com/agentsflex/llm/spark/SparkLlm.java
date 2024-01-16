package com.agentsflex.llm.spark;

import com.agentsflex.client.BaseLlmClientListener;
import com.agentsflex.client.LlmClient;
import com.agentsflex.client.LlmClientListener;
import com.agentsflex.client.impl.WebSocketClient;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.vector.VectorData;

public class SparkLlm extends BaseLlm<SparkLlmConfig> {

    public SparkLlm(SparkLlmConfig config) {
        super(config);
    }

    @Override
    public LlmClient chat(Prompt prompt, ChatListener listener) {
        LlmClient llmClient = new WebSocketClient();
        String url = SparkLlmUtil.createURL(config);

        String payload = SparkLlmUtil.promptToPayload(prompt, config);

        LlmClientListener clientListener = new BaseLlmClientListener(this, listener, prompt, SparkLlmUtil::parseAiMessage);
        llmClient.start(url, null, payload, clientListener);

        return llmClient;
    }


    @Override
    public VectorData embeddings(Prompt prompt) {
        return null;
    }
}
