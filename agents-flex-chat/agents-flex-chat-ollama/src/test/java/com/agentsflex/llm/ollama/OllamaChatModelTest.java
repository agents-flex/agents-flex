package com.agentsflex.llm.ollama;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.Test;

public class OllamaChatModelTest {

    @Test(expected = ModelException.class)
    public void testChat() {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
        config.setLogEnabled(true);

        ChatModel chatModel = new OllamaChatModel(config);
        String chat = chatModel.chat("who are your");
        System.out.println(chat);
    }


    @Test
    public void testChatStream() throws InterruptedException {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3");
        config.setLogEnabled(true);

        ChatModel chatModel = new OllamaChatModel(config);
        chatModel.chatStream("who are your", (context, response) -> System.out.println(response.getMessage().getContent()));

        Thread.sleep(2000);
    }


    @Test
    public void testFunctionCall1() throws InterruptedException {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3.1");
        config.setLogEnabled(true);

        ChatModel chatModel = new OllamaChatModel(config);

        SimplePrompt prompt = new SimplePrompt("What's the weather like in Beijing?");
        prompt.addToolsFromClass(WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        System.out.println(response.getToolResults());
    }


    @Test
    public void testFunctionCall2() throws InterruptedException {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llama3.1");
        config.setLogEnabled(true);

        ChatModel chatModel = new OllamaChatModel(config);

        SimplePrompt prompt = new SimplePrompt("What's the weather like in Beijing?");
        prompt.addToolsFromClass(WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        if (response.isTool()) {
            prompt.setToolMessages(response.getToolMessages());
            AiMessageResponse response1 = chatModel.chat(prompt);
            System.out.println(response1.getMessage().getContent());
        }
    }


    @Test
    public void testVisionModel() {
        OllamaChatConfig config = new OllamaChatConfig();
        config.setEndpoint("http://localhost:11434");
        config.setModel("llava");
        config.setLogEnabled(true);

        ChatModel chatModel = new OllamaChatModel(config);

        SimplePrompt imagePrompt = new SimplePrompt("What's in the picture?");
        imagePrompt.addImageUrl("https://agentsflex.com/assets/images/logo.png");

        AiMessageResponse response = chatModel.chat(imagePrompt);
        AiMessage message = response == null ? null : response.getMessage();
        System.out.println(message);
    }

}
