package com.agentsflex.llm.spark;

import com.agentsflex.client.BaseLlmClientListener;
import com.agentsflex.client.LlmClient;
import com.agentsflex.client.impl.WebSocketClient;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.EmbeddingsListener;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.MessageStatus;
import com.agentsflex.prompt.HistoriesPrompt;
import com.agentsflex.prompt.Prompt;

public class SparkLlm extends BaseLlm<SparkLlmConfig> {

    public SparkLlm(SparkLlmConfig config) {
        super(config);
    }

    @Override
    public LlmClient chat(Prompt prompt, ChatListener listener) {
        LlmClient llmClient = new WebSocketClient();
        String url = SparkLlmUtil.createURL(config);

        String payload = SparkLlmUtil.promptToPayload(prompt, config);

        StringBuilder fullMessage = new StringBuilder();

        llmClient.start(url, null, payload, new BaseLlmClientListener(this, listener) {
            @Override
            public void onMessage(LlmClient client, String response) {
                AiMessage aiMessage = SparkLlmUtil.parseAiMessage(response);

                fullMessage.append(aiMessage.getContent());
                aiMessage.setFullContent(fullMessage.toString());

                listener.onMessage(SparkLlm.this, aiMessage);

                if (aiMessage.getStatus() == MessageStatus.END
                    && prompt instanceof HistoriesPrompt) {
                    ((HistoriesPrompt) prompt).addMessage(aiMessage);
                }
            }
        });

        return llmClient;
    }


    @Override
    public LlmClient embeddings(Prompt prompt, EmbeddingsListener listener) {
        return null;
    }
}
