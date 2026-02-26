package com.agentsflex.skills;

import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.llm.openai.OpenAIChatConfig;
import com.agentsflex.llm.openai.OpenAIChatModel;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.tool.commons.*;

import java.util.List;

public class Main {

    public static void main(String[] args) throws InterruptedException {

        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .endpoint("https://ai.gitee.com")
            .requestPath("/v1/chat/completions")
            .apiKey(System.getenv("GITEE_APIKEY"))
            .model("Qwen3.5-35B-A3B")
//            .model("Qwen3-32B")
            .thinkingEnabled(false)
//            .logEnabled(false)
            .buildModel();

        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("Always use the available skills to assist the user in their requests.");

        UserMessage userMessage = new UserMessage("Explain reinforcement learning in simple terms and use.\n" +
            "Use required skills.\n" +
            "Then use the Youtube video https://youtu.be/vXtfdGphr3c?si=xy8U2Al_Um5vE4Jd transcript to support your answer.\n" +
            "Use absolute paths for the skills and scripts. Do not ask me for more details.");


        Tool skillsTool = SkillsTool.builder()
            .addSkillsDirectory("/Users/michael/git/agents-flex_2.x/demos/skills-demo/src/main/resources/.claude/skills")
            .build();
        userMessage.addTool(skillsTool);
        userMessage.addTools(CommonTools.getAllCommonsTools());

        prompt.addMessage(userMessage);

        StreamResponseListener listener = new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                String content = StringUtil.hasText(response.getMessage().getContent()) ? response.getMessage().getContent() : response.getMessage().getReasoningContent();
                System.out.println(">>>>> " + content);

                if (response.getMessage().isFinalDelta() && response.getMessage().getToolCalls() != null) {
                    System.out.println("----------");
                    prompt.addMessage(response.getMessage());
                    for (ToolCall toolCall : response.getMessage().getToolCalls()) {
                        System.out.println(">>>>> " + toolCall.getName() + ": " + toolCall.getArguments());
                        List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();
                        prompt.addMessages(toolMessages);
                    }
                    chatModel.chatStream(prompt, this);
                }
            }
        };
        chatModel.chatStream(prompt, listener);
        Thread.sleep(200000L);
    }
}
