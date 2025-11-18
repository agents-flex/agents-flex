package com.agentsflex.llm.openai;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

public class GiteeAiImageTest {



    @NotNull
    private static OpenAIChatConfig getOpenAIChatConfig() {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey("PXW1GXEKF8ZNQ1NP0UCYN5SUFASW4KI3YLQL7D12");
//        config.setModel("InternVL3-78B");
        config.setModel("Qwen3-32B");
        config.setEndpoint("https://ai.gitee.com");
        config.setDebug(true);
        return config;
    }

    @Test
    public  void testImage()  {
        OpenAIChatConfig config = getOpenAIChatConfig();
        ChatModel chatModel = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("请识别并输入 markdown，请用中文输出");
        prompt.getUserMessage().addImageUrl("http://www.codeformat.cn/static/images/logo.png");

        AiMessageResponse response = chatModel.chat(prompt);
        System.out.println(response.getMessage().getContent());
    }
    @Test
    public  void testChat()  {
        OpenAIChatConfig config = getOpenAIChatConfig();
        ChatModel chatModel = new OpenAIChatModel(config);

        SimplePrompt prompt = new SimplePrompt("你叫什么名字");
        prompt.addImageUrl("http://www.codeformat.cn/static/images/logo.png");

        AiMessageResponse response = chatModel.chat(prompt);
        System.out.println(response.getMessage().getContent());
    }

}
