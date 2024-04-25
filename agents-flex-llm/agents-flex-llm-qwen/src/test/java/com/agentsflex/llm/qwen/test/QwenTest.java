package com.agentsflex.llm.qwen.test;

import com.agentsflex.llm.Llm;
import com.agentsflex.llm.qwen.QwenLlm;
import com.agentsflex.llm.qwen.QwenLlmConfig;

public class QwenTest {

    public static void main(String[] args) throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        Llm llm = new QwenLlm(config);
//        llm.chatStream("请写一个小兔子战胜大灰狼的故事", (context, response) -> {
//            AiMessage message = response.getMessage();
//            System.out.println(">>>> " + message.getContent());
//        });

        String chat = llm.chat("你叫什么名字？");
        System.out.println(chat);

        Thread.sleep(10000);
    }
}
