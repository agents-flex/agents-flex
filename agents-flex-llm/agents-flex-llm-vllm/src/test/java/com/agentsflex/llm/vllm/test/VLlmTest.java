package com.agentsflex.llm.vllm.test;

import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.llm.vllm.VLlmLlm;
import com.agentsflex.llm.vllm.VLlmLlmConfig;
import org.junit.Test;

public class VLlmTest {

    public static void main(String[] args) throws InterruptedException {
        VLlmLlmConfig config = new VLlmLlmConfig();

        //https://docs.vllm.ai/en/latest/api/inference_params.html
        config.setApiKey("*****************");

        Llm llm = new VLlmLlm(config);
        String result = llm.chat("你好",new ChatOptions());
        System.out.println(result);

//        llm.chatStream("请写一个小兔子战胜大灰狼的故事", (context, response) -> {
//            AiMessage message = response.getMessage();
//            System.out.println(">>>> " + message.getContent());
//        });

        Thread.sleep(10000);
    }


    @Test
    public void testFunctionCalling() throws InterruptedException {
        VLlmLlmConfig config = new VLlmLlmConfig();
        config.setApiKey("*****************");

        Llm llm = new VLlmLlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
        // "Today it will be dull and overcast in 北京"
    }


}
