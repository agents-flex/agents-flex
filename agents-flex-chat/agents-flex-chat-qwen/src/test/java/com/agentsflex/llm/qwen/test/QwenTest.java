package com.agentsflex.llm.qwen.test;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.prompt.SimplePrompt;

import com.agentsflex.llm.qwen.QwenChatConfig;
import com.agentsflex.llm.qwen.QwenChatModel;
import com.agentsflex.llm.qwen.QwenChatOptions;
import com.agentsflex.llm.qwen.QwenChatOptions.SearchOptions;
import org.junit.Test;

public class QwenTest {

    public static void main(String[] args) throws InterruptedException {
        QwenChatConfig config = new QwenChatConfig();

        //https://bailian.console.aliyun.com/?apiKey=1#/api-key
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-plus");

        ChatModel chatModel = new QwenChatModel(config);
        chatModel.chatStream("请写一个小兔子战胜大灰狼的故事", (context, response) -> {
            AiMessage message = response.getMessage();
            System.out.println(">>>> " + message.getContent());
        });

        Thread.sleep(10000);
    }

    @Test(expected = ModelException.class)
    public void testForcedSearch() throws InterruptedException {
        QwenChatConfig config = new QwenChatConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-max");

        ChatModel chatModel = new QwenChatModel(config);
        QwenChatOptions options = new QwenChatOptions();
        options.setEnableSearch(true);
        options.setSearchOptions(new SearchOptions().setForcedSearch(true));

        String responseStr = chatModel.chat("今天是几号？", options);

        System.out.println(responseStr);
    }

    @Test
    public void testFunctionCalling() throws InterruptedException {
        QwenChatConfig config = new QwenChatConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        ChatModel chatModel = new QwenChatModel(config);

        SimplePrompt prompt = new SimplePrompt("今天北京的天气怎么样");
        prompt.addFunctionsFromClass(WeatherFunctions.class);
        AiMessageResponse response = chatModel.chat(prompt);

        System.out.println(response.getFunctionResults());
        // "Today it will be dull and overcast in 北京"
    }

    /**
     * 动态替换模型
     */
    @Test
    public void testDynamicModel() throws InterruptedException {
        // 默认模型
        QwenChatConfig config = new QwenChatConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen-turbo");

        // 运行时动态替换模型
        ChatOptions options = new QwenChatOptions();
        options.setModel("deepseek-r1");

        ChatModel chatModel = new QwenChatModel(config);
        chatModel.chatStream("请写一个小兔子战胜大灰狼的故事", (context, response) -> {
            AiMessage message = response.getMessage();
            System.err.println(message.getReasoningContent());
            System.out.println(message.getFullContent());
            System.out.println();
        }, options);
        Thread.sleep(10000);
    }

    /**
     * 测试千问3 开启思考模式的开关
     */
    @Test
    public void testQwen3Thinking() throws InterruptedException {
        QwenChatConfig config = new QwenChatConfig();
        config.setApiKey("sk-28a6be3236****");
        config.setModel("qwen3-235b-a22b");

        ChatModel chatModel = new QwenChatModel(config);
        QwenChatOptions options = new QwenChatOptions();
        options.setThinkingEnabled(false);
        //options.setThinkingBudget(1024);

        chatModel.chatStream("你是谁", (context, response) -> {
            AiMessage message = response.getMessage();
            System.err.println(message.getReasoningContent());
            System.out.println(message.getFullContent());
            System.out.println();
        }, options);
        Thread.sleep(10000);
    }

}
