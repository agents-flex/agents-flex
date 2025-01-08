package com.agentsflex.llm.qwen.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.llm.qwen.QwenLLM;
import com.agentsflex.llm.qwen.QwenLlmConfig;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.prompt.FunctionPrompt;
import org.junit.Test;

public class QwenTest {

    public static void main(String[] args) throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();

        //https://bailian.console.aliyun.com/?apiKey=1#/api-key
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-plus");

        Llm llm = new QwenLLM(config);
        llm.chatStream("请写一个小兔子战胜大灰狼的故事", (context, response) -> {
            AiMessage message = response.getMessage();
            System.out.println(">>>> " + message.getContent());
        });

        Thread.sleep(10000);
    }


    @Test
    public void testFunctionCalling() throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        Llm llm = new QwenLLM(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
        // "Today it will be dull and overcast in 北京"
    }


    @Test
    public void testEmbedding() throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        Llm llm = new QwenLLM(config);
        VectorData vectorData = llm.embed(Document.of("test"));


        System.out.println(vectorData);
    }
}
