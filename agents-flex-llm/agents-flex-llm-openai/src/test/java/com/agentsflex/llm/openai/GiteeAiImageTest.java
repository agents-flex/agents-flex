package com.agentsflex.llm.openai;

import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.ImagePrompt;

public class GiteeAiImageTest {

    public static void main(String[] args) throws InterruptedException {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey("MFRIVS******6YUH1K");
//        config.setModel("Qwen3-32B");
//        config.setModel("Qwen2.5-VL-32B-Instruct");
        config.setModel("InternVL3-78B");
        config.setEndpoint("https://ai.gitee.com");
        config.setDebug(true);

        Llm llm = new OpenAILlm(config);

        ImagePrompt prompt = new ImagePrompt("请识别并输入 markdown，请用中文输出");
        prompt.addImageUrl("http://www.codeformat.cn/static/images/logo.png");
//        prompt.addImageFile(new File("/Users/michael/Desktop/lxs.jpeg"));

        AiMessageResponse response = llm.chat(prompt);
        System.out.println(response.getMessage().getContent());

//        TextPrompt prompt = new TextPrompt("请识别并输入 markdown");

//        llm.chatStream(prompt, new StreamResponseListener() {
//            @Override
//            public void onMessage(ChatContext context, AiMessageResponse response) {
//                System.out.println(response.getMessage().getContent());
//            }
//        });
//
//        Thread.sleep(20000);
    }
}
