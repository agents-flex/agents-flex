package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.core.util.StringUtil;
import org.junit.Assume;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import static org.junit.Assert.*;

/** Live contract test. It is skipped unless OPENAI_RESPONSES_API_KEY is set. */
public class OpenAIResponsesChatModelIntegrationTest {

    @Test
    public void callsResponsesEndpoint() {
        String apiKey = System.getenv("OPENAI_RESPONSES_API_KEY");
        Assume.assumeTrue("OPENAI_RESPONSES_API_KEY is not set", StringUtil.hasText(apiKey));

        OpenAIResponsesChatModel model = model(apiKey);

        AiMessageResponse response = model.chat(new SimplePrompt("Reply with exactly RESPONSES_OK"));
        response.throwIfError();
        AiMessage message = response.getMessage();

        assertNotNull(message);
        assertEquals("RESPONSES_OK", message.getTextContent().trim());
        assertNotNull(message.getId());
        assertNotNull(message.getPromptTokens());
        assertNotNull(message.getCompletionTokens());
        assertNotNull(message.getPromptTokensDetails());
        assertTrue(message.getPromptTokensDetails().containsKey("cached_tokens"));
    }

    @Test
    public void streamsResponsesEndpoint() throws Exception {
        String apiKey = System.getenv("OPENAI_RESPONSES_API_KEY");
        Assume.assumeTrue("OPENAI_RESPONSES_API_KEY is not set", StringUtil.hasText(apiKey));

        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<AiMessage> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        model(apiKey).chatStream(new SimplePrompt("Reply with exactly STREAM_OK"),
            new StreamResponseListener() {
                @Override
                public void onMessage(StreamContext context, AiMessageResponse response) {
                    if (response.getMessage().isFinalDelta()) result.set(response.getMessage());
                }

                @Override
                public void onError(StreamContext context, Throwable err) {
                    failure.set(err);
                }

                @Override
                public void onClose(StreamContext context) {
                    if (result.get() == null) result.set(context.getFullMessage());
                    closed.countDown();
                }
            });

        assertTrue("stream did not close", closed.await(60, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertNotNull(result.get());
        assertEquals("STREAM_OK", result.get().getFullContent().trim());
        assertNotNull(result.get().getPromptTokens());
    }

    @Test
    public void completesFunctionCallRoundTrip() {
        String apiKey = System.getenv("OPENAI_RESPONSES_API_KEY");
        Assume.assumeTrue("OPENAI_RESPONSES_API_KEY is not set", StringUtil.hasText(apiKey));

        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("Call get_weather once. After receiving its result, reply exactly WEATHER_RESULT_SUNNY.");
        prompt.addUserMessage("What is the weather in Hangzhou?");
        prompt.addTool(Tool.builder("get_weather", "Get current weather")
            .addParameter(Parameter.builder().name("city").type("string").required(true).build())
            .function(args -> "WEATHER_RESULT_SUNNY")
            .build());
        prompt.setToolChoice("required");

        OpenAIResponsesChatModel model = model(apiKey);
        AiMessageResponse toolResponse = model.chat(prompt);
        toolResponse.throwIfError();
        assertTrue(toolResponse.hasToolCalls());

        prompt.addMessage(toolResponse.getMessage());
        prompt.addMessages(toolResponse.executeToolCallsAndGetToolMessages());
        prompt.setToolChoice(null);

        AiMessageResponse finalResponse = model.chat(prompt);
        finalResponse.throwIfError();
        assertFalse(finalResponse.hasToolCalls());
        assertEquals("WEATHER_RESULT_SUNNY", finalResponse.getMessage().getTextContent().trim());
    }

    @Test
    public void callsRestrictedBashToolRoundTrip() {
        String apiKey = System.getenv("OPENAI_RESPONSES_API_KEY");
        Assume.assumeTrue("OPENAI_RESPONSES_API_KEY is not set", StringUtil.hasText(apiKey));

        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("You must call the bash tool exactly once with command printf BASH_TOOL_OK. "
            + "After receiving the result, reply with exactly BASH_TOOL_OK.");
        prompt.addUserMessage("Run the requested shell check.");
        prompt.addTool(Tool.builder("bash", "Run the restricted shell check")
            .addParameter(Parameter.builder().name("command").type("string")
                .description("The exact command printf BASH_TOOL_OK").required(true).build())
            .function(args -> {
                String command = String.valueOf(args.get("command"));
                if (!"printf BASH_TOOL_OK".equals(command.trim())) {
                    throw new SecurityException("Only the fixed test command is allowed");
                }
                try {
                    Process process = new ProcessBuilder("/bin/bash", "-lc", command)
                        .redirectErrorStream(true)
                        .start();
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) output.append(line).append('\n');
                    }
                    if (process.waitFor() != 0) throw new IllegalStateException("bash command failed");
                    return output.toString().trim();
                } catch (Exception error) {
                    throw new IllegalStateException("restricted bash execution failed", error);
                }
            })
            .build());
        prompt.setToolChoice("required");

        OpenAIResponsesChatModel model = model(apiKey);
        AiMessageResponse toolResponse = model.chat(prompt);
        toolResponse.throwIfError();
        assertTrue(toolResponse.hasToolCalls());

        prompt.addMessage(toolResponse.getMessage());
        prompt.addMessages(toolResponse.executeToolCallsAndGetToolMessages());
        prompt.setToolChoice(null);

        AiMessageResponse finalResponse = model.chat(prompt);
        finalResponse.throwIfError();
        assertEquals("BASH_TOOL_OK", finalResponse.getMessage().getTextContent().trim());
    }

    private static OpenAIResponsesChatModel model(String apiKey) {
        return OpenAIResponsesChatConfig.builder()
            .apiKey(apiKey)
            .endpoint(env("OPENAI_RESPONSES_ENDPOINT", "https://api.openai.com"))
            .requestPath(env("OPENAI_RESPONSES_PATH", "/v1/responses"))
            .model(env("OPENAI_RESPONSES_MODEL", "gpt-4.1-mini"))
            .logEnabled(false)
            .buildModel();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return StringUtil.hasText(value) ? value : defaultValue;
    }
}
