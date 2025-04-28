package com.agentsflex.llm.openai;

import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.ImagePrompt;

public class GiteeAiImageTest {

    public static void main(String[] args) {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey("P07AGYTQB********VESLLLNWCNR");
        config.setModel("InternVL3-78B");
        config.setEndpoint("https://ai.gitee.com");

        Llm llm = new OpenAILlm(config);

        ImagePrompt prompt = new ImagePrompt("请识别并输入 markdown");
        prompt.setImageUrl("http://www.codeformat.cn/static/images/logo.png");

        AiMessageResponse response = llm.chat(prompt);
        System.out.println(response.getMessage().getContent());
    }
}
