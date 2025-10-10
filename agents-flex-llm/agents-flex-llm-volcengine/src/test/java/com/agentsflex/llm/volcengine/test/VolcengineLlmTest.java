package com.agentsflex.llm.volcengine.test;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.exception.LlmException;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.HumanImageMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.llm.volcengine.VolcengineLlm;
import com.agentsflex.llm.volcengine.VolcengineLlmConfig;
import org.junit.Test;

public class VolcengineLlmTest {
    public static void main(String[] args) throws Exception {

    }

    // 单轮对话
    @Test(expected = LlmException.class)
    public void testChat() {
        VolcengineLlmConfig config = new VolcengineLlmConfig();
        config.setApiKey("********************************");
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setDefaultChatApi("/api/v3/chat/completions");
        config.setModel("doubao-1-5-vision-pro-32k-250115");
        Llm llm = new VolcengineLlm(config);
        String response = llm.chat("老舍讲的是什么？");

        System.out.println(response);
    }

    // 流式对话
    @Test()
    public void testChatStream() {
        VolcengineLlmConfig config = new VolcengineLlmConfig();
        config.setApiKey("*************************");
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setDefaultChatApi("/api/v3/chat/completions");
        config.setModel("doubao-1-5-vision-pro-32k-250115");

        Llm llm = new VolcengineLlm(config);
        llm.chatStream("你叫什么名字", new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
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
        VolcengineLlmConfig config = new VolcengineLlmConfig();
        config.setApiKey("***************************");
        config.setEndpoint("https://ark.cn-beijing.volces.com");
        config.setDefaultChatApi("/api/v3/chat/completions");
        config.setModel("doubao-1-5-vision-pro-32k-250115");


        Llm llm = new VolcengineLlm(config);
        ImagePrompt prompt = new ImagePrompt("这个图片说的是什么?");
        prompt.addImageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png");
        System.out.println(prompt.getImageUrls());


        Message message = new HumanImageMessage(prompt);
        prompt.addMetadata("message", message);

        AiMessageResponse response = llm.chat(prompt);
        System.out.println(response);
    }

}
