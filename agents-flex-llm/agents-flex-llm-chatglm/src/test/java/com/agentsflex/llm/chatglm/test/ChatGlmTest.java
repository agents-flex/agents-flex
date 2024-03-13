package com.agentsflex.llm.chatglm.test;

import com.agentsflex.document.Document;
import com.agentsflex.llm.Llm;
import com.agentsflex.llm.chatglm.ChatglmLlm;
import com.agentsflex.llm.chatglm.ChatglmLlmConfig;
import com.agentsflex.store.VectorData;
import org.junit.Test;

import java.util.Arrays;

public class ChatGlmTest {

    public static void main(String[] args) {
        ChatglmLlmConfig config = new ChatglmLlmConfig();
        config.setApiKey("**.**");

        Llm llm = new ChatglmLlm(config);
        llm.chatAsync("你叫什么名字", (context, response) -> System.out.println(response.getMessage().getContent()));
    }


    @Test
    public void testEmbedding() {
        ChatglmLlmConfig config = new ChatglmLlmConfig();
        config.setApiKey("**.**");

        Llm llm = new ChatglmLlm(config);
        Document document = new Document();
        document.setContent("你好");
        VectorData embeddings = llm.embeddings(document);
        System.out.println(Arrays.toString(embeddings.getVector()));
    }

}
