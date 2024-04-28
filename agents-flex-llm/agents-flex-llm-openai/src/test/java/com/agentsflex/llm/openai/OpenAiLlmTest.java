package com.agentsflex.llm.openai;

import com.agentsflex.llm.ChatContext;
import com.agentsflex.llm.Llm;
import com.agentsflex.llm.StreamResponseListener;
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.llm.response.FunctionMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.FunctionPrompt;
import org.junit.Test;

public class OpenAiLlmTest {

    @Test
    public void testChat() {
        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        Llm llm = new OpenAiLlm(config);
        String response = llm.chat("请问你叫什么名字");

        System.out.println(response);
    }

    @Test
    public void testChat01() {
        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-alQ9N********");
        config.setEndpoint("https://api.moonshot.cn");
        config.setModel("moonshot-v1-8k");
//        config.setDebug(true);

        Llm llm = new OpenAiLlm(config);
//        String response = llm.chat("请问你叫什么名字");
        llm.chatStream("你叫什么名字", new StreamResponseListener<AiMessageResponse, AiMessage>() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                System.out.println(response.getMessage().getContent());
            }
        });

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }



    @Test
    public void testFunctionCalling() throws InterruptedException {
        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        OpenAiLlm llm = new OpenAiLlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        FunctionMessageResponse response = llm.chat(prompt);

        Object result = response.invoke();

        System.out.println(result);
        // "Today it will be dull and overcast in 北京"
    }
}
