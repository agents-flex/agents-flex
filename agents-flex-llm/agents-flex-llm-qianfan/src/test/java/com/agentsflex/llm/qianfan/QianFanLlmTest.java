package com.agentsflex.llm.qianfan;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.exception.LlmException;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.store.VectorData;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

public class QianFanLlmTest {
    QianFanLlmConfig config = new QianFanLlmConfig();

    @Before
    public void setUp() {
        config.setApiKey("***");
        config.setDebug(true);
    }

    @Test(expected = LlmException.class)
    public void testChat() {
        Llm llm = new QianFanLlm(config);
        String response = llm.chat("请问你叫什么名字");

        System.out.println(response);
    }

    @Test()
    public void testChatStream() throws InterruptedException {
        Llm llm = new QianFanLlm(config);
        llm.chatStream("who are your", (context, response) -> System.out.print(response.getMessage().getContent()));
        Thread.sleep(2000);
    }


    @Test()
    public void testFunctionChat() {
        Llm llm = new QianFanLlm(config);
        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
    }

    @Test()
    public void testEmb() {
        Llm llm = new QianFanLlm(config);
        Document document = new Document();
        document.setContent("你好");
        VectorData embeddings = llm.embed(document, EmbeddingOptions.DEFAULT);
        System.out.println(Arrays.toString(embeddings.getVector()));
    }
}
