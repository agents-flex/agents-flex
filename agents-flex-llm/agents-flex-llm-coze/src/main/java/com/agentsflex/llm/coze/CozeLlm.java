/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.llm.coze;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.BaseLlm;
import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.MessageResponse;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.client.BaseLlmClientListener;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.llm.client.LlmClientListener;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AbstractBaseMessageResponse;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.FunctionMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.store.VectorData;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;


/**
 * @author yulsh
 */
public class CozeLlm extends BaseLlm<CozeLlmConfig> {

    private final HttpClient httpClient = new HttpClient();
    private final AiMessageParser aiMessageParser = CozeLlmUtil.getAiMessageParser();

    public CozeLlm(CozeLlmConfig config) {
        super(config);
    }

    private Map<String, String> buildHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    private <R extends MessageResponse<?>> void botChat(Prompt<R> prompt, CozeRequestListener listener, ChatOptions chatOptions, boolean stream) {
        CozeChatOptions options = (CozeChatOptions) chatOptions;
        String payload = CozeLlmUtil.promptToPayload(prompt, config, options, stream);
        String url = config.getEndpoint() + config.getChatApi();
        if (options.getConversationId() != null) {
            url += "?conversation_id=" + options.getConversationId();
        }
        String response = httpClient.post(url, buildHeader(), payload);
        if (config.isDebug()) {
            System.out.println(">>>>request payload:" + payload);
        }
        CozeChatContext cozeChat;

        // stream mode
        if (stream) {
            handleStreamResponse(response, listener);
            return;
        }

        JSONObject jsonObject = JSON.parseObject(response);
        String code = jsonObject.getString("code");
        String error = jsonObject.getString("msg");
        cozeChat = jsonObject.getObject("data", (Type) CozeChatContext.class);

        if (!error.isEmpty() && !Objects.equals(code, "0")) {
            listener.onFailure(cozeChat, new Throwable(error));
            listener.onStop(cozeChat);
            return;
        }

        // try to check status
        int attemptCount = 0;
        boolean isCompleted = false;
        int maxAttempts = 20;
        while (attemptCount < maxAttempts && !isCompleted)  {
            attemptCount ++;
            try {
                cozeChat = checkStatus(cozeChat);
                listener.onMessage(cozeChat);

                isCompleted = Objects.equals(cozeChat.getStatus(), "completed");
                if (isCompleted || attemptCount == maxAttempts) {
                    listener.onStop(cozeChat);
                    break;
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                listener.onFailure(cozeChat, e.getCause());
                listener.onStop(cozeChat);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleStreamResponse(String response, CozeRequestListener listener) {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(response.getBytes(Charset.defaultCharset()));
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, Charset.defaultCharset()));
        String line;
        CozeChatContext context = new CozeChatContext(this, null);
        List<AiMessage> messageList = new ArrayList<>();
        try {
            while ( (line = br.readLine()) != null ) {
                if(!line.trim().equals("") && line.startsWith("data:")){
                    if (line.contains("[DONE]")) {
                        continue;
                    }
                    line = line.replace("data:", "");
                    Map<String, String> data = JSON.parseObject(line, Map.class);
                    String status = data.getOrDefault("status", "");
                    String type = data.getOrDefault("type", "");
                    if (status.equals("completed")) {
                        context = JSON.parseObject(line, CozeChatContext.class);
                        listener.onStop(context);
                        continue;
                    }
                    // N 条answer，最后一条是完整的
                    if (type.equals("answer")) {
                        AiMessage message = new AiMessage();
                        message.setContent(data.get("content"));
                        messageList.add(message);
                    }
                }
            }
            if (!messageList.isEmpty()) {
                // 删除最后一条完整的之后输出
                messageList.remove(messageList.size() -1);
                for(AiMessage m: messageList) {
                    context.setMessage(m);
                    listener.onMessage(context);
                    Thread.sleep(10);
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            listener.onFailure(context, ex.getCause());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private CozeChatContext checkStatus(CozeChatContext cozeChat) {
        String chatId = cozeChat.getId();
        String conversationId = cozeChat.getConversationId();
        String url = String.format("%s/v3/chat/retrieve?chat_id=%s&conversation_id=%s", config.getEndpoint(), chatId, conversationId);
        String response = httpClient.get(url, buildHeader());
        JSONObject resObj = JSON.parseObject(response);
        CozeChatContext data = resObj.getObject("data", (Type) CozeChatContext.class);
        return data;
    }

    private JSONArray fetchMessageList(CozeChatContext cozeChat) {
        String chatId = cozeChat.getId();
        String conversationId = cozeChat.getConversationId();
        String endpoint = config.getEndpoint();
        String url = String.format("%s/v3/chat/message/list?chat_id=%s&conversation_id=%s", endpoint, chatId, conversationId);
        String response = httpClient.get(url, buildHeader());
        JSONObject jsonObject = JSON.parseObject(response);
        String code = jsonObject.getString("code");
        String error = jsonObject.getString("msg");
        JSONArray messageList = jsonObject.getJSONArray("data");
        if (!error.isEmpty() && !Objects.equals(code, "0")) {
            return null;
        }
        return messageList;
    }

    public AiMessage getChatAnswer(CozeChatContext cozeChat) {
        JSONArray messageList = fetchMessageList(cozeChat);
        List<JSONObject> objects = messageList.stream()
        .map(JSONObject.class::cast)
        .filter(obj -> "answer".equals(obj.getString("type")))
        .collect(Collectors.toList());

        JSONObject answer = objects.size() > 0 ? objects.get(0) : null;
        if (answer != null) {
            answer.put("usage", cozeChat.getUsage());
            answer.put("content",answer.getString("content"));
            AiMessage message = aiMessageParser.parse(answer);
            return message;
        }
        return null;
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        return super.embed(document);
    }

    @Override
    public <R extends MessageResponse<?>> R chat(Prompt<R> prompt, ChatOptions options) {
        CountDownLatch latch = new CountDownLatch(1);
        Message[] messages = new Message[1];
        Throwable[] failureThrowable = new Throwable[1];

        this.botChat(prompt, new CozeRequestListener() {
            @Override
            public void onMessage(CozeChatContext context) {
                boolean isCompleted = Objects.equals(context.getStatus(), "completed");
                if (isCompleted) {
                    AiMessage answer = getChatAnswer(context);
                    messages[0] = answer;
                }
            }

            @Override
            public void onFailure(CozeChatContext context, Throwable throwable) {
                failureThrowable[0] = throwable;
                latch.countDown();
            }

            @Override
            public void onStop(CozeChatContext context) {
                latch.countDown();
            }
        }, options, false);

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        AbstractBaseMessageResponse<?> response;
        response = new AiMessageResponse((AiMessage) messages[0]);

        if (messages[0] == null || failureThrowable[0] != null) {
            response.setError(true);
            if (failureThrowable[0] != null) {
                response.setErrorMessage(failureThrowable[0].getMessage());
            }
        }

        return (R) response;
    }

    @Override
    public <R extends MessageResponse<?>> void chatStream(Prompt<R> prompt, StreamResponseListener<R> listener, ChatOptions options) {
        this.botChat(prompt, new CozeRequestListener() {
            @Override
            public void onMessage(CozeChatContext context) {
                AiMessageResponse response = new AiMessageResponse(context.getMessage());
                listener.onMessage(context, (R) response);
            }

            @Override
            public void onFailure(CozeChatContext context, Throwable throwable) {
                listener.onFailure(context, throwable);
            }

            @Override
            public void onStop(CozeChatContext context) {
                listener.onStop(context);
            }
        }, options, true);
    }

}
