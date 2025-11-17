package com.agentsflex.llm.chatglm.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.llm.chatglm.ChatglmChatModel;
import com.agentsflex.llm.chatglm.ChatglmChatConfig;
import org.junit.Test;

import java.util.Arrays;

public class ChatGlmTest {

    public static void main(String[] args) {
        ChatglmChatConfig config = new ChatglmChatConfig();
        config.setApiKey("**.***********************");

        ChatModel chatModel = new ChatglmChatModel(config);
        chatModel.chatStream("你叫什么名字", (context, response) -> System.out.println(response.getMessage().getContent()));
    }


    @Test
    public void testEmbedding() {
        ChatglmChatConfig config = new ChatglmChatConfig();
        config.setApiKey("**.***********************");

        ChatModel chatModel = new ChatglmChatModel(config);
        Document document = new Document();
        document.setContent("你好");
        VectorData embeddings = chatModel.embed(document, EmbeddingOptions.DEFAULT);
        System.out.println(Arrays.toString(embeddings.getVector()));
    }


    @Test
    public void testFunctionCalling() {
        ChatglmChatConfig config = new ChatglmChatConfig();
        config.setApiKey("**.***********************");

        ChatModel chatModel = new ChatglmChatModel(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        System.out.println(response.callFunctions());
    }

}
