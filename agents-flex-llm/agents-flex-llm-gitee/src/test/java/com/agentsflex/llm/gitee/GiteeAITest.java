package com.agentsflex.llm.gitee;

import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.prompt.FunctionPrompt;
import org.junit.Test;

public class GiteeAITest {

    public static void main(String[] args) {
        GiteeAiLlmConfig config = new GiteeAiLlmConfig();
        config.setApiKey("your-api-key");

        GiteeAiLLM llm = new GiteeAiLLM(config);
        String result = llm.chat("你好");
        System.out.println(result);
    }

    @Test
    public void testFunctionCalling() {
        GiteeAiLlmConfig config = new GiteeAiLlmConfig();
        config.setApiKey("your-api-key");
        config.setModel("Qwen2.5-72B-Instruct");

        GiteeAiLLM llm = new GiteeAiLLM(config);


        FunctionPrompt prompt = new FunctionPrompt("What's the weather like in Beijing?", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
    }
}
