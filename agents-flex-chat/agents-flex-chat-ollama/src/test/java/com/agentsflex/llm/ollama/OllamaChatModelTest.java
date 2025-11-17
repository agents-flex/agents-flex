package com.agentsflex.llm.ollama;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.core.prompt.ToolPrompt;
import com.agentsflex.core.store.VectorData;
import org.junit.Test;

public class OllamaChatModelTest {

    @Test(expected = ModelException.class)
    public void testChat() {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
        config.setDebug(true);

        ChatModel chatModel = new OllamaChatModel(config);
        String chat = chatModel.chat("who are your");
        System.out.println(chat);
    }


    @Test
    public void testChatStream() throws InterruptedException {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
        config.setDebug(true);

        ChatModel chatModel = new OllamaChatModel(config);
        chatModel.chatStream("who are your", (context, response) -> System.out.println(response.getMessage().getContent()));

        Thread.sleep(2000);
    }


    @Test
    public void testEmbedding() {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3.1");
        config.setDebug(true);

        ChatModel chatModel = new OllamaChatModel(config);
        VectorData vectorData = chatModel.embed(Document.of("hello world"));
        System.out.println(vectorData);
    }


    @Test
    public void testFunctionCall1() throws InterruptedException {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3.1");
        config.setDebug(true);

        ChatModel chatModel = new OllamaChatModel(config);

        FunctionPrompt prompt = new FunctionPrompt("What's the weather like in Beijing?", WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        System.out.println(response.callFunctions());
    }


    @Test
    public void testFunctionCall2() throws InterruptedException {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3.1");
        config.setDebug(true);

        ChatModel chatModel = new OllamaChatModel(config);

        FunctionPrompt prompt = new FunctionPrompt("What's the weather like in Beijing?", WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        if (response.isFunctionCall()){
            AiMessageResponse response1 = chatModel.chat(ToolPrompt.of(response));
            System.out.println(response1.getMessage().getContent());
        }
    }


    @Test
    public void testVisionModel() {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llava");
        config.setDebug(true);

        ChatModel chatModel = new OllamaChatModel(config);

        ImagePrompt imagePrompt = new ImagePrompt("What's in the picture?", "https://agentsflex.com/assets/images/logo.png");

        AiMessageResponse response = chatModel.chat(imagePrompt);
        AiMessage message = response == null ? null : response.getMessage();
        System.out.println(message);
    }

}
