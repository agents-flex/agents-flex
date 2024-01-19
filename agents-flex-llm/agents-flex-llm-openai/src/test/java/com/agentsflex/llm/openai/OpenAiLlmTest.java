package com.agentsflex.llm.openai;

import com.agentsflex.functions.Functions;
import com.agentsflex.llm.Llm;
import com.agentsflex.prompt.SimplePrompt;
import org.junit.Test;

public class OpenAiLlmTest {

    @Test
    public  void testChat() throws InterruptedException {


        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        Llm llm = new OpenAiLlm(config);

        llm.chat(new SimplePrompt("请写一个小兔子战胜大灰狼的故事"), (instance, message) -> {
            System.out.println("--->" + message.getContent());
        });

        Thread.sleep(10000);

    }
    @Test
    public  void testFunctionCalling() throws InterruptedException {


        OpenAiLlmConfig config = new OpenAiLlmConfig();
        config.setApiKey("sk-rts5NF6n*******");

        OpenAiLlm llm = new OpenAiLlm(config);

        Functions<String> functions = Functions.from(WeatherUtil.class, String.class);
        String result = llm.call(new SimplePrompt("今天的天气怎么样"), functions);

        System.out.println(result);

        Thread.sleep(10000);

    }
}
