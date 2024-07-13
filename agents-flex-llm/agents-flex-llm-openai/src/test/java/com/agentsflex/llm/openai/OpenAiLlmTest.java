package com.agentsflex.llm.openai;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.llm.response.FunctionMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.ImagePrompt;
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
        llm.chatStream("你叫什么名字", new StreamResponseListener<AiMessageResponse>() {
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
    public void testChatOllama() {
        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
//        config.setDebug(true);

        Llm llm = new OpenAiLlm(config);
        llm.chatStream("who are you", new StreamResponseListener<AiMessageResponse>() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                System.out.println(response.getMessage().getContent());
            }
        });

        try {
            Thread.sleep(20000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    @Test
    public void testChatWithImage() {
        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-5gqOclb****0");
        config.setModel("gpt-4-turbo");

        Llm llm = new OpenAiLlm(config);
        ImagePrompt prompt = new ImagePrompt("What's in this image?");
        prompt.setImageUrl("https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg");


//        llm.chatStream(prompt, (StreamResponseListener<AiMessageResponse, AiMessage>)
//            (context, response) -> System.out.println(response.getMessage().getContent())
//        );

        AiMessageResponse response = llm.chat(prompt);
        System.out.println(response);

        try {
            Thread.sleep(12000);
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

        Object result = response.getFunctionResult();

        System.out.println(result);
        // "Today it will be dull and overcast in 北京"
    }
}
