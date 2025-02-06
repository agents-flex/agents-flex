package com.agentsflex.llm.moonshot.test;

import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.llm.moonshot.MoonshotLLM;
import com.agentsflex.llm.moonshot.MoonshotLlmConfig;

public class MoonshotTest {

    public static void main(String[] args) throws InterruptedException {
        MoonshotLlmConfig config = new MoonshotLlmConfig();
        ChatOptions chatOptions = new ChatOptions();
        chatOptions.setTemperature(0.3f);
        chatOptions.setMaxTokens(4096);
        config.setApiKey("sk-*****");
        config.setModel("moonshot-v1-8k");
        Llm llm = new MoonshotLLM(config);
        String res = llm.chat("你叫什么名字",chatOptions);
        System.out.println(res);
        llm.chatStream("你叫什么名字", (context, response) -> {
            AiMessage message = response.getMessage();
            System.out.println(message);
        },chatOptions);

    }
}
