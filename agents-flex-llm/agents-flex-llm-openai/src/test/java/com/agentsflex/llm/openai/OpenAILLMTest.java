package com.agentsflex.llm.openai;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.exception.LlmException;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.ImagePrompt;
import org.junit.Test;

public class OpenAILLMTest {

    @Test(expected = LlmException.class)
    public void testChat() {
        OpenAILLMConfig config = new OpenAILLMConfig();
        config.setApiKey("sk-rts5NF6n*******");

        Llm llm = new OpenAILLM(config);
        String response = llm.chat("请问你叫什么名字");

        System.out.println(response);
    }

    @Test()
    public void testChat01() {
        OpenAILLMConfig config = new OpenAILLMConfig();
        config.setApiKey("sk-alQ9N********");
        config.setEndpoint("https://api.moonshot.cn");
        config.setModel("moonshot-v1-8k");
//        config.setDebug(true);

        Llm llm = new OpenAILLM(config);
        llm.chatStream("你叫什么名字", new StreamResponseListener() {
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
        OpenAILLMConfig config = new OpenAILLMConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
//        config.setDebug(true);

        Llm llm = new OpenAILLM(config);
        llm.chatStream("who are you", new StreamResponseListener() {
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


    @Test()
    public void testChatWithImage() {
        OpenAILLMConfig config = new OpenAILLMConfig();
        config.setApiKey("sk-5gqOcl*****");
        config.setModel("gpt-4-turbo");


        //APIKey: sk-5gqOclbt0OpCHRe49fCfAe7194624d27A32a8aB25a9e2c30 ---- 建议选择GPT-4相关版本使用 ---- API域名输入：https://api.mctools.online ---- 参考商品详情页下载对应客户端配置教程使用
        Llm llm = new OpenAILLM(config);
        ImagePrompt prompt = new ImagePrompt("What's in this image?");
        prompt.setImageUrl("https://upload.wikimedia.org/wikipedia/commons/thumb/d/dd/Gfp-wisconsin-madison-the-nature-boardwalk.jpg/2560px-Gfp-wisconsin-madison-the-nature-boardwalk.jpg");


        AiMessageResponse response = llm.chat(prompt);
        System.out.println(response);

        try {
            Thread.sleep(12000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    @Test()
    public void testFunctionCalling() throws InterruptedException {
        OpenAILLMConfig config = new OpenAILLMConfig();
        config.setApiKey("sk-rts5NF6n*******");

        OpenAILLM llm = new OpenAILLM(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
        // "Today it will be dull and overcast in 北京"
    }
}
