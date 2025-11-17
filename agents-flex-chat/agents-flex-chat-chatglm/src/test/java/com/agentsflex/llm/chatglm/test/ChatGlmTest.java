package com.agentsflex.llm.chatglm.test;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.llm.chatglm.ChatglmChatConfig;
import com.agentsflex.llm.chatglm.ChatglmChatModel;
import org.junit.Test;

public class ChatGlmTest {

    public static void main(String[] args) {
        ChatglmChatConfig config = new ChatglmChatConfig();
        config.setApiKey("**.***********************");

        ChatModel chatModel = new ChatglmChatModel(config);
        chatModel.chatStream("你叫什么名字", (context, response) -> System.out.println(response.getMessage().getContent()));
    }



    @Test
    public void testFunctionCalling() {
        ChatglmChatConfig config = new ChatglmChatConfig();
        config.setApiKey("**.***********************");

        ChatModel chatModel = new ChatglmChatModel(config);

        SimplePrompt simplePrompt = new SimplePrompt("今天北京的天气怎么样");
        simplePrompt.getUserMessage().addFunctions(WeatherFunctions.class);

//        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(simplePrompt);

        System.out.println(response.callFunctions());
    }

}
