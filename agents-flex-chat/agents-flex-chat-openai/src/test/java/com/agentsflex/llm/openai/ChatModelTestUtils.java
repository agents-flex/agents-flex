package com.agentsflex.llm.openai;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.SimplePrompt;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ChatModelTestUtils {

    public static void waitForStream(
        ChatModel model,
        String prompt,
        StreamResponseListener listener) {
        waitForStream(model, new SimplePrompt(prompt), listener, Integer.MAX_VALUE);
    }

    public static void waitForStream(
        ChatModel model,
        Prompt prompt,
        StreamResponseListener listener) {
        waitForStream(model, prompt, listener, Integer.MAX_VALUE);
    }

    public static void waitForStream(
        ChatModel model,
        Prompt prompt,
        StreamResponseListener listener,
        long timeoutSeconds) {

        CountDownLatch latch = new CountDownLatch(1);

        StreamResponseListener wrapped = new StreamResponseListener() {
            @Override
            public void onStart(StreamContext context) {
                listener.onStart(context);
            }

            @Override
            public void onMessage(StreamContext ctx, AiMessageResponse resp) {
                listener.onMessage(ctx, resp);
            }

            @Override
            public void onStop(StreamContext ctx) {
                listener.onStop(ctx);
                latch.countDown();
            }

            @Override
            public void onFailure(StreamContext context, Throwable throwable) {
                listener.onFailure(context, throwable);
            }
        };

        model.chatStream(prompt, wrapped);
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stream did not complete within " + timeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
