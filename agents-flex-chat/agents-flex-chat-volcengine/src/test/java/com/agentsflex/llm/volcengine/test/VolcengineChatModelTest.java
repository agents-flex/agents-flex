package com.agentsflex.llm.volcengine.test;

import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.llm.volcengine.VolcengineChatConfig;
import com.agentsflex.llm.volcengine.VolcengineChatModel;
import org.junit.Test;

public class VolcengineChatModelTest {
    public static void main(String[] args) throws Exception {

    }

    // 单轮对话
    @Test(expected = ModelException.class)
    public void testChat() {
        VolcengineChatConfig config = new VolcengineChatConfig();
        config.setApiKey("********************************");
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setDefaultChatApi("/api/v3/chat/completions");
        config.setModel("doubao-1-5-vision-pro-32k-250115");
        ChatModel chatModel = new VolcengineChatModel(config);
        String response = chatModel.chat("老舍讲的是什么？");

        System.out.println(response);
    }

    // 流式对话
    @Test()
    public void testChatStream() {
        VolcengineChatConfig config = new VolcengineChatConfig();
        config.setApiKey("*************************");
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setDefaultChatApi("/api/v3/chat/completions");
        config.setModel("doubao-1-5-vision-pro-32k-250115");

        ChatModel chatModel = new VolcengineChatModel(config);
        chatModel.chatStream("你叫什么名字", new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                System.out.println(response.getMessage().getContent());
            }
        });

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // 带图片的对话
    @Test()
    public void testChatWithImage() {
        VolcengineChatConfig config = new VolcengineChatConfig();
        config.setApiKey("***************************");
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setDefaultChatApi("/api/v3/chat/completions");
        config.setModel("doubao-1-5-vision-pro-32k-250115");


        ChatModel chatModel = new VolcengineChatModel(config);
        SimplePrompt prompt = new SimplePrompt("这个图片说的是什么?");
        prompt.addImageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png");
        System.out.println(prompt.getImageUrls());


        AiMessageResponse response = chatModel.chat(prompt);
        System.out.println(response);
    }

}
