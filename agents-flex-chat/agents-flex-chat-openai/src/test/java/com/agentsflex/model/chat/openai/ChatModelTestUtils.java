package com.agentsflex.model.chat.openai;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
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
        waitForStream(model, new SimplePrompt(prompt), listener, Integer.MAX_VALUE, null);
    }

    public static void waitForStream(
        ChatModel model,
        String prompt,
        StreamResponseListener listener,
        ChatOptions options) {
        waitForStream(model, new SimplePrompt(prompt), listener, Integer.MAX_VALUE, options);
    }

    public static void waitForStream(
        ChatModel model,
        Prompt prompt,
        StreamResponseListener listener) {
        waitForStream(model, prompt, listener, Integer.MAX_VALUE, null);
    }

    public static void waitForStream(
        ChatModel model,
        Prompt prompt,
        StreamResponseListener listener,
        ChatOptions options) {
        waitForStream(model, prompt, listener, Integer.MAX_VALUE, options);
    }

    public static void waitForStream(
        ChatModel model,
        Prompt prompt,
        StreamResponseListener listener,
        long timeoutSeconds, ChatOptions options) {

        CountDownLatch latch = new CountDownLatch(1);

        StreamResponseListener wrapped = new StreamResponseListener() {
            @Override
            public void onOpen(StreamContext context) {
                listener.onOpen(context);
            }

            @Override
            public void onMessage(StreamContext ctx, AiMessageResponse resp) {
                listener.onMessage(ctx, resp);
            }

            @Override
            public void onClose(StreamContext ctx) {
                listener.onClose(ctx);
                latch.countDown();
            }

            @Override
            public void onError(StreamContext context, Throwable throwable) {
                listener.onError(context, throwable);
            }
        };

        model.chatStream(prompt, wrapped, options);
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new RuntimeException("Stream did not complete within " + timeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
