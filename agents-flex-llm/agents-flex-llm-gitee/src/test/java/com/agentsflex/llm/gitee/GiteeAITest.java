package com.agentsflex.llm.gitee;

public class GiteeAITest {

    public static void main(String[] args) {
        GiteeAiLlmConfig config = new GiteeAiLlmConfig();
        config.setApiKey("your-api-key");

        GiteeAiLlm llm = new GiteeAiLlm(config);
        String result = llm.chat("你好");
        System.out.println(result);
    }
}
