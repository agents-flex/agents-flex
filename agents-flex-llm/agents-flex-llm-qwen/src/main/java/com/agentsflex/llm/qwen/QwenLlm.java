package com.agentsflex.llm.qwen;

import com.agentsflex.client.BaseLlmClientListener;
import com.agentsflex.client.LlmClient;
import com.agentsflex.client.LlmClientListener;
import com.agentsflex.client.impl.SseClient;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.vector.VectorData;

import java.util.HashMap;
import java.util.Map;

public class QwenLlm extends BaseLlm<QwenLlmConfig> {

    public QwenLlm(QwenLlmConfig config) {
        super(config);
    }


    @Override
    public LlmClient chat(Prompt prompt, ChatListener listener) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = QwenLlmUtil.promptToPayload(prompt, config);

        LlmClientListener clientListener = new BaseLlmClientListener(this, listener, prompt, new BaseLlmClientListener.MessageParser() {
            int prevMessageLength = 0;

            @Override
            public AiMessage parseMessage(String response) {
                AiMessage aiMessage = QwenLlmUtil.parseAiMessage(response, prevMessageLength);
                prevMessageLength += aiMessage.getContent().length();
                return aiMessage;
            }
        });
        llmClient.start("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation", headers, payload, clientListener);

        return llmClient;
    }


    @Override
    public VectorData embeddings(Prompt prompt) {
        return null;
    }
}
