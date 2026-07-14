package com.agentsflex.websearch;


import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.log.ChatMessageLogger;
import com.agentsflex.core.model.chat.log.IChatMessageLogger;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.ToolScanner;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;
import com.agentsflex.model.chat.openai.OpenAIChatModel;
import com.agentsflex.tool.commons.TodoWriteTool;
import com.agentsflex.tool.commons.WebFetchTool;
import com.agentsflex.websearch.baidu.BaiduQianfanSearchProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class WebSearchDemo {

    public static void main(String[] args) throws InterruptedException {
        System.setProperty("agentsflex.otel.enabled", "false");

        ChatMessageLogger.setLogger(new IChatMessageLogger() {
            @Override
            public void logRequest(BaseChatConfig config, String message) {
//                System.out.println("request >>>>> " + JSON.toJSONString(JSON.parseObject(message), JSONWriter.Feature.PrettyFormat));
                System.out.println("request >>>>> " + message);
            }

            @Override
            public void logResponse(BaseChatConfig config, String message) {

            }
        });

        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .endpoint("https://ai.gitee.com")
            .requestPath("/v1/chat/completions")
            .apiKey(System.getenv("GITEE_APIKEY"))
//            .model("Qwen3.5-35B-A3B")
//            .model("Qwen3.7-Max")
            .model("Qwen3-32B")
//            .thinkingEnabled(false)
//            .logEnabled(false)
            .buildModel();

        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("请注意：在用户给出的问题中，请先使用 TodoWrite 来分解用户问题，然后再按步骤进行执行。");
        UserMessage userMessage = new UserMessage("帮我搜索一下什么是 Agents-flex，并给出示例代码。");

        AtomicReference<TodoWriteTool.Todos> todosRef = new AtomicReference<>();

        prompt.addTools(ToolScanner.scan(TodoWriteTool.builder().todoEventHandler(new TodoWriteTool.TodoEventHandler() {
            @Override
            public void handle(TodoWriteTool.Todos todos) {
                todosRef.set(todos);
            }
        }).build()));

        prompt.addTools(ToolScanner.scan(WebFetchTool.builder().useDefaultProviders().build()));
        prompt.addTools(ToolScanner.scan(WebSearchTool.builder()
//            .provider(new BochaSearchProvider(System.getenv("BOCHA_APIKEY")))
//            .provider(new TavilySearchProvider(System.getenv("TAVILY_API_KEY")))
            .provider(new BaiduQianfanSearchProvider(System.getenv("BAIDU_APIKEY")))
            .build()));


        prompt.addMessage(userMessage);

        StreamResponseListener listener = new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
//                String content = StringUtil.hasText(response.getMessage().getContent()) ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                String content = response.getMessage().getContent();
//                System.out.println(">>>>> " + content);
                if (content != null) {
                    System.out.print(content);
                }

                if (response.getMessage().isFinalDelta() && response.getMessage().getToolCalls() != null) {
                    System.out.println("\n\ncall tools start----------");
                    prompt.addMessage(response.getMessage());
                    for (ToolCall toolCall : response.getMessage().getToolCalls()) {
                        System.out.println(">>>>> " + toolCall.getName() + ": " + JSON.toJSONString(toolCall.getArgsMap(), JSONWriter.Feature.PrettyFormat));

                        List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();
//                        System.out.println(">>>>> Result: " + toolMessages.get(0).getContent());
                        System.out.println("call tools start end----------\n\n");


                        prompt.addMessages(toolMessages);
                    }
                    chatModel.chatStream(prompt, this);
                } else if (response.getMessage().isFinalDelta() && !response.getMessage().hasToolCalls()) {
                    TodoWriteTool.Todos todos = todosRef.get();
                    if (todos != null && !todos.isComplete()) {
                        UserMessage um = new UserMessage("请检查你的 todo list 是否已经完成。\n\n" + todos.toMarkdown());
                        prompt.addMessageTemporary(um);
                        chatModel.chatStream(prompt, this);
                    } else {
                        System.out.println("\n\n>>>>>>> 结束 <<<<<<<<<");
                    }

                }
            }
        };
        chatModel.chatStream(prompt, listener);
        Thread.sleep(200000L);
    }
}
