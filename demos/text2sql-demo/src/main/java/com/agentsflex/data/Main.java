package com.agentsflex.data;

import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.data.entity.JdbcDataSourceInfo;
import com.agentsflex.data.tools.DataTools;
import com.agentsflex.llm.openai.OpenAIChatConfig;
import com.agentsflex.llm.openai.OpenAIChatModel;

import java.util.List;

public class Main {

    public static void main(String[] args) {

        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .endpoint("https://ai.gitee.com")
            .requestPath("/v1/chat/completions")
            .apiKey(System.getenv("GITEE_APIKEY"))
            .model("Qwen3.5-35B-A3B")
//            .model("Qwen3-32B")
//            .thinkingEnabled(false)
            .logEnabled(false)
            .buildModel();

        MemoryPrompt prompt = new MemoryPrompt();

        UserMessage userMessage = new UserMessage("系统中有哪些姓李的用户呢？");
        prompt.addMessage(userMessage);

        JdbcDataSourceInfo dataSource = new JdbcDataSourceInfo();
//        dataSource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/aiflowy_v1?useInformationSchema=true&characterEncoding=utf-8");
        dataSource.setJdbcUrl("jdbc:mysql://192.168.2.10:3306/aiflowy-v2?useInformationSchema=true&characterEncoding=utf-8");
        dataSource.setUsername("root");
        dataSource.setPassword("123456");
        dataSource.setName("aiflowy_v1");
//        dataSource.setDescription();

        List<Tool> tools = DataTools.builder()
            .addDataSourceInfo(dataSource)
            .buildTools();

        prompt.addTools(tools);


        StreamResponseListener listener = new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                String content = StringUtil.hasText(response.getMessage().getContent()) ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                if (content != null)
                    System.out.print( content);

                if (response.getMessage().isFinalDelta() && response.getMessage().hasToolCalls()) {
                    System.out.println("\n----------");
                    prompt.addMessage(response.getMessage());
                    for (ToolCall toolCall : response.getMessage().getToolCalls()) {
                        System.out.println(">>>>> " + toolCall.getName() + ": " + toolCall.getArguments());
                        List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();

                        for (ToolMessage toolMessage : toolMessages) {
                            System.out.println("<<<<< " + toolMessage.getToolCallId() + ": " + toolMessage.getContent());
                        }

                        prompt.addMessages(toolMessages);
                    }
                    chatModel.chatStream(prompt, this);
                } else if (response.getMessage().isFinalDelta() && !response.getMessage().hasToolCalls()) {
                    System.out.println("\n>>>>>>> 结束 <<<<<<<<<");
                }
            }
        };

        chatModel.chatStream(prompt, listener);

        try {
            Thread.sleep(200000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
