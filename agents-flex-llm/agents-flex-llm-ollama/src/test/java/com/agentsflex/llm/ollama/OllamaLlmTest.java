package com.agentsflex.llm.ollama;

import com.agentsflex.core.llm.Llm;
import org.junit.Test;

public class OllamaLlmTest {

    @Test
    public void test01() {
        OllamaLlmConfig config = new OllamaLlmConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
        config.setDebug(true);

        Llm llm = new OllamaLlm(config);
        String chat = llm.chat("who are your");
        System.out.println(chat);
    }


    @Test
    public void testChatStream() throws InterruptedException {
        OllamaLlmConfig config = new OllamaLlmConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
        config.setDebug(true);

        Llm llm = new OllamaLlm(config);
        llm.chatStream("who are your", (context, response) -> System.out.println(response.getMessage().getContent()));

        Thread.sleep(20000);
    }
}
