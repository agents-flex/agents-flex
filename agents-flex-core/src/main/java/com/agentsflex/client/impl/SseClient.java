package com.agentsflex.client.impl;

import com.agentsflex.client.LlmClient;
import com.agentsflex.client.LlmClientListener;
import okhttp3.*;
import okhttp3.internal.sse.RealEventSource;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SseClient extends EventSourceListener implements LlmClient {

    private OkHttpClient client;
    private RealEventSource eventSource;

    private LlmClientListener listener;
    private boolean isStop = false;

    @Override
    public void start(String url, Map<String, String> headers, String payload, LlmClientListener listener) {
        this.listener = listener;
        this.isStop = false;

        Request.Builder rBuilder = new Request.Builder()
            .url(url);

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(rBuilder::addHeader);
        }

        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(payload,mediaType);
        rBuilder.post(body);


        this.client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.MINUTES)
            .readTimeout(3, TimeUnit.MINUTES)
            .build();

//        this.eventSource = new RealEventSource(rBuilder.build(), this);
//        this.eventSource.connect(this.client);


        this.listener.onStart(this);

        EventSource.Factory factory = EventSources.createFactory(this.client);
        factory.newEventSource(rBuilder.build(),this);


    }

    @Override
    public void stop() {
        if (!isStop) {
            this.isStop = true;
            eventSource.cancel();
            client.dispatcher().executorService().shutdown();
            this.listener.onStop(this);
        }
    }


    @Override
    public void onClosed(@NotNull EventSource eventSource) {
        System.out.println("onClosed-->>" + eventSource);
        if (!isStop) {
            this.isStop = true;
            this.listener.onStop(this);
        }
    }

    @Override
    public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
        System.out.println("onEvent-->>" + eventSource);
        System.out.println("onEvent-->>" + id);
        System.out.println("onEvent-->>" + type);
        System.out.println("onEvent-->>" + data);
        this.listener.onMessage(this, data);
    }

    @Override
    public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
        System.out.println("onFailure-->>" + eventSource);
        System.out.println("onFailure-->>" + t);
        System.out.println("onFailure-->>" + response);
        String string = null;
        try {
            string = response.body().string();
            System.out.println("onFailure-->>" + string);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.listener.onFailure(this, t);
    }

    @Override
    public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        //super.onOpen(eventSource, response);
        System.out.println("onOpen-->>" + eventSource);
        System.out.println("onOpen-->>" + response);
    }
}
