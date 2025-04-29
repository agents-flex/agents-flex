package com.agentsflex.llm.qwen.test;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.exception.LlmException;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.llm.qwen.QwenChatOptions;
import com.agentsflex.llm.qwen.QwenChatOptions.SearchOptions;
import com.agentsflex.llm.qwen.QwenLlm;
import com.agentsflex.llm.qwen.QwenLlmConfig;
import org.junit.Test;

public class QwenTest {

    public static void main(String[] args) throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();

        //https://bailian.console.aliyun.com/?apiKey=1#/api-key
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-plus");

        Llm llm = new QwenLlm(config);
        llm.chatStream("请写一个小兔子战胜大灰狼的故事", (context, response) -> {
            AiMessage message = response.getMessage();
            LogUtil.println(">>>> " + message.getContent());
        });

        Thread.sleep(10000);
    }

    @Test(expected = LlmException.class)
    public void testForcedSearch() throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-max");

        Llm llm = new QwenLlm(config);
        QwenChatOptions options = new QwenChatOptions();
        options.setEnableSearch(true);
        options.setSearchOptions(new SearchOptions().setForcedSearch(true));

        String responseStr = llm.chat("今天是几号？", options);

        System.out.println(responseStr);
    }

    @Test
    public void testFunctionCalling() throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        Llm llm = new QwenLlm(config);

        FunctionPrompt prompt = new FunctionPrompt("今天北京的天气怎么样", WeatherFunctions.class);
        AiMessageResponse response = llm.chat(prompt);

        System.out.println(response.callFunctions());
        // "Today it will be dull and overcast in 北京"
    }

    @Test
    public void testEmbedding() throws InterruptedException {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        Llm llm = new QwenLlm(config);
        VectorData vectorData = llm.embed(Document.of("test"));

        System.out.println(vectorData);
    }

    /**
     * 动态替换模型
     */
    @Test
    public void testDynamicModel() throws InterruptedException {
        // 默认模型
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        // 运行时动态替换模型
        ChatOptions options = new QwenChatOptions();
        options.setModel("deepseek-r1");

        Llm llm = new QwenLlm(config);
        llm.chatStream("请写一个小兔子战胜大灰狼的故事", (context, response) -> {
            AiMessage message = response.getMessage();
            LogUtil.println(">>>> " + message.getReasoningContent() + ">>>> " + message.getContent());
        }, options);
        Thread.sleep(10000);
    }

}
