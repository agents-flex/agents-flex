package com.agentsflex.llm.chatglm.test;

import com.agentsflex.llm.Llm;
import com.agentsflex.llm.chatglm.ChatglmLlm;
import com.agentsflex.llm.chatglm.ChatglmLlmConfig;

public class ChatGlmTest {

    public static void main(String[] args) {
        ChatglmLlmConfig config = new ChatglmLlmConfig();
        config.setApiKey("f26*****");

        Llm llm = new ChatglmLlm(config);
        String result = llm.chat("你叫什么名字");
        System.out.println(result);
    }
}
