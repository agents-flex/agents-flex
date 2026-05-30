package com.agentsflex.todolist;

import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatConfig;
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
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.util.List;

public class TodoListDemo {

    public static void main(String[] args) throws InterruptedException {
        System.setProperty("agentsflex.otel.enabled", "false");

        ChatMessageLogger.setLogger(new IChatMessageLogger() {
            @Override
            public void logRequest(ChatConfig config, String message) {
//                System.out.println("request >>>>> " + JSON.toJSONString(JSON.parseObject(message), JSONWriter.Feature.PrettyFormat));
                System.out.println("request >>>>> " + message);
            }

            @Override
            public void logResponse(ChatConfig config, String message) {

            }
        });

        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .endpoint("https://ai.gitee.com")
            .requestPath("/v1/chat/completions")
            .apiKey(System.getenv("GITEE_APIKEY"))
            .model("Qwen3.5-35B-A3B")
//            .model("Qwen3-32B")
//            .thinkingEnabled(false)
//            .logEnabled(false)
            .buildModel();

        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("请注意使用 TodoWrite 来分解用户问题。");
        UserMessage userMessage = new UserMessage("帮我总结一下 https://zhuanlan.zhihu.com/p/2012156966353515279 的内容。" +
            "然后写几个不同的示例。");

        prompt.addTools(ToolScanner.scan(TodoWriteTool.builder().build()));
        prompt.addTools(ToolScanner.scan(WebFetchTool.builder().useDefaultProviders().build()));


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
                        System.out.println(">>>>> Result: " + toolMessages.get(0).getContent());
                        System.out.println("call tools start end----------\n\n");
                        prompt.addMessages(toolMessages);
                    }
                    chatModel.chatStream(prompt, this);
                } else if (response.getMessage().isFinalDelta() && !response.getMessage().hasToolCalls()) {
                    System.out.println("\n\n>>>>>>> 结束 <<<<<<<<<");
                }
            }
        };
        chatModel.chatStream(prompt, listener);
        Thread.sleep(200000L);
    }
}
