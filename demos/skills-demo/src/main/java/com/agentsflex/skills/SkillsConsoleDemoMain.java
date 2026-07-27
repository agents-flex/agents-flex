package com.agentsflex.skills;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;
import com.agentsflex.model.chat.openai.OpenAIChatModel;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可在控制台中持续输入问题的 Skills 多轮对话示例。
 */
public class SkillsConsoleDemoMain {

    public static void main(String[] args) throws Exception {
        SkillsDemoMain.disableObservabilityByDefault();

        String skillsDirectory = SkillsDemoMain.resolveSkillsDirectory();
        long turnTimeoutSeconds = SkillsDemoMain.environmentLong("SKILLS_DEMO_TIMEOUT_SECONDS", 900L);
        OpenAIChatModel chatModel = createChatModel();

        try (SkillRuntime runtime = SkillsDemoMain.createRuntime();
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            MemoryPrompt prompt = createPrompt(skillsDirectory, runtime);

            System.out.println("Skills directory: " + skillsDirectory);
            System.out.println("Skill runtime: " + runtime.getName());
            System.out.println("持续对话已启动。输入 /clear 清空对话历史，输入 /exit 或 /quit 退出。\n");

            while (true) {
                System.out.print("You> ");
                System.out.flush();
                String input = reader.readLine();
                if (input == null || isExitCommand(input)) {
                    System.out.println("Bye.");
                    break;
                }
                if ("/clear".equalsIgnoreCase(input.trim())) {
                    prompt.clear();
                    System.out.println("对话历史已清空。\n");
                    continue;
                }
                if (!StringUtil.hasText(input)) {
                    continue;
                }

                prompt.addUserMessage(input.trim());
                System.out.print("Assistant> ");
                runTurn(chatModel, prompt, turnTimeoutSeconds);
                System.out.println();
            }
        }
    }

    private static OpenAIChatModel createChatModel() {
        return OpenAIChatConfig.builder()
            .provider(SkillsDemoMain.environment("DEEPSEEK_PROVIDER", "DeepSeek"))
            .endpoint(SkillsDemoMain.environment("DEEPSEEK_ENDPOINT", "https://api.deepseek.com"))
            .requestPath(SkillsDemoMain.environment("DEEPSEEK_REQUEST_PATH", "/chat/completions"))
            .apiKey(SkillsDemoMain.requireEnvironment("DEEPSEEK_API_KEY"))
            .model(SkillsDemoMain.environment("DEEPSEEK_MODEL", "deepseek-v4-pro"))
            .logEnabled(Boolean.parseBoolean(
                SkillsDemoMain.environment("CHAT_LOG_ENABLED", "true")))
            .buildModel();
    }

    private static MemoryPrompt createPrompt(String skillsDirectory, SkillRuntime runtime) {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("Always use the available skills when they match the request. "
            + "Run every shell command with the provided Bash tool in the configured skill runtime.");
        prompt.addTools(SkillsTool.builder()
            .addSkillsDirectory(skillsDirectory)
            .runtime(runtime)
            .buildTools());
        return prompt;
    }

    private static void runTurn(OpenAIChatModel chatModel, MemoryPrompt prompt,
                                long timeoutSeconds) throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        chatModel.chatStream(prompt, new ConsoleConversationListener(
            chatModel, prompt, completed, failure));

        if (!completed.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Conversation turn timed out after "
                + timeoutSeconds + " seconds");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Conversation turn failed", failure.get());
        }
    }

    private static boolean isExitCommand(String input) {
        String command = input.trim();
        return "/exit".equalsIgnoreCase(command) || "/quit".equalsIgnoreCase(command);
    }

    private static class ConsoleConversationListener implements StreamResponseListener {

        private final OpenAIChatModel chatModel;
        private final MemoryPrompt prompt;
        private final CountDownLatch completed;
        private final AtomicReference<Throwable> failure;
        private final AtomicBoolean streamActive = new AtomicBoolean(false);
        private final AtomicBoolean followUpStarted = new AtomicBoolean(false);

        private ConsoleConversationListener(OpenAIChatModel chatModel, MemoryPrompt prompt,
                                            CountDownLatch completed,
                                            AtomicReference<Throwable> failure) {
            this.chatModel = chatModel;
            this.prompt = prompt;
            this.completed = completed;
            this.failure = failure;
        }

        @Override
        public void onOpen(StreamContext context) {
            streamActive.set(true);
        }

        @Override
        public void onMessage(StreamContext context, AiMessageResponse response) {
            try {
                if (response.isError()) {
                    recordFailure(new IllegalStateException(response.getErrorMessage()));
                    return;
                }

                AiMessage message = response.getMessage();
                if (message == null) {
                    return;
                }
                String content = StringUtil.hasText(message.getContent())
                    ? message.getContent() : message.getReasoningContent();
                if (content != null) {
                    System.out.print(content);
                    System.out.flush();
                }

                if (!message.isFinalDelta()) {
                    return;
                }

                prompt.addMessage(message);
                if (!message.hasToolCalls()) {
                    return;
                }

                System.out.println("\n\n---------- tools ----------");
                for (ToolCall toolCall : message.getToolCalls()) {
                    System.out.println(toolCall.getName() + ": "
                        + JSON.toJSONString(toolCall.getArgsMap(), JSONWriter.Feature.PrettyFormat));
                }
                List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();
                for (ToolMessage toolMessage : toolMessages) {
                    System.out.println("result: " + toolMessage.getContent());
                }
                System.out.println("---------------------------\n");
                prompt.addMessages(toolMessages);
                followUpStarted.set(true);
                try {
                    chatModel.chatStream(prompt, new ConsoleConversationListener(
                        chatModel, prompt, completed, failure));
                } catch (RuntimeException e) {
                    followUpStarted.set(false);
                    throw e;
                }
            } catch (RuntimeException e) {
                recordFailure(e);
            }
        }

        @Override
        public void onError(StreamContext context, Throwable throwable) {
            recordFailure(throwable == null
                ? new IllegalStateException("Unknown model failure") : throwable);
        }

        @Override
        public void onClose(StreamContext context) {
            if (streamActive.compareAndSet(true, false) && !followUpStarted.get()) {
                completed.countDown();
            }
        }

        private void recordFailure(Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }
}
