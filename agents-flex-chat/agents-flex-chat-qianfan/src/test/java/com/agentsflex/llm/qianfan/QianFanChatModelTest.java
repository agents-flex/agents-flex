package com.agentsflex.llm.qianfan;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.store.VectorData;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

public class QianFanChatModelTest {
    QianFanChatConfig config = new QianFanChatConfig();

    @Before
    public void setUp() {
        config.setApiKey("***");
        config.setDebug(true);
    }

    @Test(expected = ModelException.class)
    public void testChat() {
        ChatModel chatModel = new QianFanChatModel(config);
        String response = chatModel.chat("请问你叫什么名字");

        System.out.println(response);
    }

    @Test()
    public void testChatStream() throws InterruptedException {
        ChatModel chatModel = new QianFanChatModel(config);
        chatModel.chatStream("who are your", (context, response) -> System.out.print(response.getMessage().getContent()));
        Thread.sleep(2000);
    }


    @Test()
    public void testFunctionChat() {
        ChatModel chatModel = new QianFanChatModel(config);
        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        System.out.println(response.callFunctions());
    }

    @Test()
    public void testEmb() {
        ChatModel chatModel = new QianFanChatModel(config);
        Document document = new Document();
        document.setContent("你好");
        VectorData embeddings = chatModel.embed(document, EmbeddingOptions.DEFAULT);
        System.out.println(Arrays.toString(embeddings.getVector()));
    }
}
