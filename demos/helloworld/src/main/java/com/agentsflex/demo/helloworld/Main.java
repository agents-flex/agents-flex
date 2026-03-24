package com.agentsflex.demo.helloworld;

import com.agentsflex.model.chat.openai.OpenAIChatConfig;
import com.agentsflex.model.chat.openai.OpenAIChatModel;

public class Main {

    public static void main(String[] args) {

        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .endpoint("https://ai.gitee.com")
            .requestPath("/v1/chat/completions")
            .apiKey("P****QL7D12")
            .model("Qwen3-32B")
            .buildModel();

        String output = chatModel.chat("如何才能更幽默?");
        System.out.println(output);
    }
}
