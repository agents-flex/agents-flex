/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.audio.volc.protocol;

import com.agentsflex.core.audio.tts.StreamingTextToSpeechListener;
import com.agentsflex.core.model.client.OkHttpClientUtil;
import okhttp3.*;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class VolcWebSocketClient {
    private static final Logger log = LoggerFactory.getLogger(VolcWebSocketClient.class);

    private final String serverUri;
    private final Map<String, Object> headers;

    private final BlockingQueue<Message> messageQueue = new LinkedBlockingQueue<>();
    private final List<StreamingTextToSpeechListener> listeners;
    private final CompletableFuture<Boolean> completeFuture = new CompletableFuture<>();

    private final OkHttpClient client;
    private WebSocket webSocket;

    public VolcWebSocketClient(OkHttpClient client, String serverUri, Map<String, Object> headers, List<StreamingTextToSpeechListener> listeners) {
        this.client = client == null ? OkHttpClientUtil.buildDefaultClient() : client;
        this.serverUri = serverUri;
        this.headers = headers;
        this.listeners = listeners;
    }

    /**
     * 建立 WebSocket 连接
     */
    public void connect() {
        Request.Builder requestBuilder = new Request.Builder()
            .url(serverUri);

        if (headers != null) {
            headers.forEach((s, o) -> requestBuilder.addHeader(s, o.toString()));
        }

        Request request = requestBuilder.build();

        webSocket = client.newWebSocket(request, new Listener());
    }

    /**
     * 关闭连接
     */
    public void closeBlocking() {
        if (webSocket != null) {
            webSocket.close(1000, "normal closure");
            webSocket = null;
        }

        this.messageQueue.clear();

        try {
            completeFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completeFuture.cancel(true);
            throw new RuntimeException("Execution interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

//        if (client != null) {
//            client.dispatcher().executorService().shutdown();
//            client.connectionPool().evictAll();
//        }
    }

    private class Listener extends WebSocketListener {

        private boolean isComplete = false;

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            String logId = response.header("x-tt-logid");
            log.info("WebSocket connection established, Logid: {}", logId);

            for (StreamingTextToSpeechListener listener : listeners) {
                try {
                    listener.onStart();
                } catch (Exception e) {
                    log.error(e.toString(), e);
                }
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            log.warn("Received unexpected text message: {}", text);
        }


        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            try {
                Message message = Message.unmarshal(bytes.toByteArray());
//                System.out.println("onMessage: " + message);
                if (message.getType() == MsgType.AUDIO_ONLY_SERVER) {
                    for (StreamingTextToSpeechListener listener : listeners) {
                        try {
                            listener.onReceived(message.getPayload());
                        } catch (Exception e) {
                            log.error(e.toString(), e);
                        }
                    }
                } else {
                    messageQueue.put(message);
                }
            } catch (Exception e) {
                log.error("Failed to parse message", e);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            fireOnComplete();
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            fireOnComplete();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            for (StreamingTextToSpeechListener listener : listeners) {
                try {
                    listener.onError(t);
                } catch (Exception e) {
                    log.error(e.toString(), e);
                }
            }
            fireOnComplete();
        }

        private synchronized void fireOnComplete() {
            if (isComplete) {
                return;
            }
            isComplete = true;
            for (StreamingTextToSpeechListener listener : listeners) {
                try {
                    listener.onComplete();
                } catch (Exception e) {
                    log.error(e.toString(), e);
                }
            }
            completeFuture.complete(true);
        }
    }

    /**
     * 发送连接开始事件
     */
    public void sendStartConnection() throws Exception {
        Message message = new Message(
            MsgType.FULL_CLIENT_REQUEST,
            MsgTypeFlagBits.WITH_EVENT
        );
        message.setEvent(EventType.START_CONNECTION);
        message.setPayload("{}".getBytes());

        sendMessage(message);
    }

    /**
     * 发送连接结束事件
     */
    public void sendFinishConnection() throws Exception {
        Message message = new Message(
            MsgType.FULL_CLIENT_REQUEST,
            MsgTypeFlagBits.WITH_EVENT
        );
        message.setEvent(EventType.FINISH_CONNECTION);
        message.setPayload("{}".getBytes());

        sendMessage(message);
    }

    /**
     * 开启 Session
     */
    public void sendStartSession(byte[] payload, String sessionId) throws Exception {
        Message message = new Message(
            MsgType.FULL_CLIENT_REQUEST,
            MsgTypeFlagBits.WITH_EVENT
        );

        message.setEvent(EventType.START_SESSION);
        message.setSessionId(sessionId);
        message.setPayload(payload);

        sendMessage(message);
    }

    /**
     * 结束 Session
     */
    public void sendFinishSession(String sessionId) throws Exception {
        Message message = new Message(
            MsgType.FULL_CLIENT_REQUEST,
            MsgTypeFlagBits.WITH_EVENT
        );

        message.setEvent(EventType.FINISH_SESSION);
        message.setSessionId(sessionId);
        message.setPayload("{}".getBytes());

        sendMessage(message);
    }

    /**
     * 发送任务请求
     */
    public void sendTaskRequest(byte[] payload, String sessionId) throws Exception {
        Message message = new Message(
            MsgType.FULL_CLIENT_REQUEST,
            MsgTypeFlagBits.WITH_EVENT
        );

        message.setEvent(EventType.TASK_REQUEST);
        message.setSessionId(sessionId);
        message.setPayload(payload);

        sendMessage(message);
    }

    /**
     * 发送自定义消息
     */
    public void sendFullClientMessage(byte[] payload) throws Exception {
        Message message = new Message(
            MsgType.FULL_CLIENT_REQUEST,
            MsgTypeFlagBits.NO_SEQ
        );

        message.setPayload(payload);

        sendMessage(message);
    }

    /**
     * 发送协议消息
     */
    public void sendMessage(Message message) throws Exception {
        if (webSocket == null) {
            throw new IllegalStateException("WebSocket not connected");
        }

        log.info("Send: {}", message);

        boolean success = webSocket.send(
            ByteString.of(message.marshal())
        );

        if (!success) {
            throw new IllegalStateException("Failed to send websocket message");
        }
    }

    /**
     * 阻塞接收
     */
    public Message receiveMessage() throws InterruptedException {
        Message message = messageQueue.take();

        log.info("Receive: {}", message);

        return message;
    }

    /**
     * 超时接收
     */
    public Message receiveMessage(long timeout, TimeUnit unit)
        throws InterruptedException {

        Message message = messageQueue.poll(timeout, unit);

        if (message != null) {
            log.info("Receive: {}", message);
        }

        return message;
    }

    /**
     * 等待指定消息
     */
    public Message waitForMessage(MsgType type, EventType event)
        throws InterruptedException {
        return waitForMessage(type, event, false);
    }

    public Message waitForMessage(MsgType type, EventType event, boolean ignoreMessageType)
        throws InterruptedException {

        while (true) {
            Message message = receiveMessage();

            if (message.getType() == type
                && message.getEvent() == event) {
                return message;
            }

            if (!ignoreMessageType) {
                throw new RuntimeException(
                    "Unexpected message: " + message
                );
            }
        }
    }
}
