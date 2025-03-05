package com.agentsflex.llm.tencent.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.llm.tencent.ChatglmLlm;
import com.agentsflex.llm.tencent.ChatglmLlmConfig;
import org.junit.Test;

import java.util.Arrays;

public class ChatGlmTest {

    public static void main(String[] args) {
        ChatglmLlmConfig config = new ChatglmLlmConfig();
        config.setApiKey("**.***********************");

        Llm llm = new ChatglmLlm(config);
        llm.chatStream("你叫什么名字", (context, response) -> System.out.println(response.getMessage().getContent()));
    }


    @Test
    public void testEmbedding() {
        ChatglmLlmConfig config = new ChatglmLlmConfig();
        config.setApiKey("**.***********************");

        Llm llm = new ChatglmLlm(config);
        Document document = new Document();
        document.setContent("你好");
        VectorData embeddings = llm.embed(document, EmbeddingOptions.DEFAULT);
        System.out.println(Arrays.toString(embeddings.getVector()));
    }


    @Test
    public void testFunctionCalling() {
        ChatglmLlmConfig config = new ChatglmLlmConfig();
        config.setApiKey("**.***********************");

        Llm llm = new ChatglmLlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
    }

}
