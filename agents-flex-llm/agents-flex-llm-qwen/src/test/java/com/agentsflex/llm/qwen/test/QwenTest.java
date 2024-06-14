package com.agentsflex.llm.qwen.test;

import com.agentsflex.llm.Llm;
import com.agentsflex.llm.qwen.QwenLlm;
import com.agentsflex.llm.qwen.QwenLlmConfig;
import com.agentsflex.llm.response.FunctionMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.FunctionPrompt;
import org.junit.Test;

public class QwenTest {

    public static void main(String[] args) throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        Llm llm = new QwenLlm(config);
        llm.chatStream("请写一个小兔子战胜大灰狼的故事", (context, response) -> {
            AiMessage message = response.getMessage();
            System.out.println(">>>> " + message.getContent());
        });

//        String chat = llm.chat("你叫什么名字？");
//        System.out.println(chat);

        Thread.sleep(10000);
    }


    @Test
    public void testFunctionCalling() throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        Llm llm = new QwenLlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        FunctionMessageResponse response = llm.chat(prompt);

        Object result = response.getFunctionResult();

        System.out.println(result);
        // "Today it will be dull and overcast in 北京"
    }
}
