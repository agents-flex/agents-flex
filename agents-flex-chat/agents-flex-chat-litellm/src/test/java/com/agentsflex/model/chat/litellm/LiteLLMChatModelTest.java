package com.agentsflex.model.chat.litellm;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

public class LiteLLMChatModelTest {

    public static ChatModel getLLM() {
        LiteLLMConfig config = new LiteLLMConfig();
        config.setEndpoint("http://localhost:4000");
        config.setApiKey("sk-litellm-master-key");
        config.setModel("gpt-4o");
        config.setLogEnabled(true);
        return new LiteLLMChatModel(config);
    }

    public static void main(String[] args) {
        ChatModel chatModel = getLLM();
        SimplePrompt prompt = new SimplePrompt("What is the capital of France?");
        AiMessageResponse response = chatModel.chat(prompt);
        System.out.println(response);
    }
}
