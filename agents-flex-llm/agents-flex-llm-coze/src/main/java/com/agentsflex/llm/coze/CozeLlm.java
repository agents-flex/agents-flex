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
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AbstractBaseMessageResponse;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.*;
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

    private <R extends AiMessageResponse> void botChat(Prompt<R> prompt, CozeRequestListener listener, ChatOptions chatOptions, boolean stream) {
        String botId = config.getDefaultBotId();
        String userId = config.getDefaultUserId();
        String conversationId = config.getDefaultConversationId();
        Map<String, String> customVariables = null;

        if (chatOptions instanceof CozeChatOptions) {
            CozeChatOptions options = (CozeChatOptions) chatOptions;
            botId = StringUtil.hasText(options.getBotId()) ? options.getBotId() : botId;
            userId = StringUtil.hasText(options.getUserId()) ? options.getUserId() : userId;
            conversationId = StringUtil.hasText(options.getConversationId()) ? options.getConversationId() : conversationId;
            customVariables = options.getCustomVariables();
        }

        String payload = CozeLlmUtil.promptToPayload(prompt, botId, userId, customVariables, stream);
        String url = config.getEndpoint() + config.getChatApi();
        if (StringUtil.hasText(conversationId)) {
            url += "?conversation_id=" + conversationId;
        }
        String response = httpClient.post(url, buildHeader(), payload);
        if (config.isDebug()) {
            System.out.println(">>>>request payload:" + payload);
        }

        // stream mode
        if (stream) {
            handleStreamResponse(response, listener);
            return;
        }

        JSONObject jsonObject = JSON.parseObject(response);
        String code = jsonObject.getString("code");
        String error = jsonObject.getString("msg");

        CozeChatContext cozeChat = jsonObject.getObject("data", (Type) CozeChatContext.class);
        cozeChat.setResponse(response);

        if (!error.isEmpty() && !Objects.equals(code, "0")) {
            listener.onFailure(cozeChat, new Throwable(error));
            listener.onStop(cozeChat);
            return;
        }

        // try to check status
        int attemptCount = 0;
        boolean isCompleted = false;
        int maxAttempts = 20;
        while (attemptCount < maxAttempts && !isCompleted) {
            attemptCount++;
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
        CozeChatContext context = new CozeChatContext(this, null);
        List<AiMessage> messageList = new ArrayList<>();
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty() || !line.startsWith("data:") || line.contains("[DONE]")) {
                    continue;
                }

                //remove "data:"
                line = line.substring(5);
                JSONObject data = JSON.parseObject(line);
                String status = data.getString("status");
                String type = data.getString("type");
                if ("completed".equalsIgnoreCase(status)) {
                    context = JSON.parseObject(line, CozeChatContext.class);
                    context.setResponse(line);
                    listener.onStop(context);
                    continue;
                }
                // N 条answer，最后一条是完整的
                if ("answer".equalsIgnoreCase(type)) {
                    AiMessage message = new AiMessage();
                    message.setContent(data.getString("content"));
                    messageList.add(message);
                }
            }
            if (!messageList.isEmpty()) {
                // 删除最后一条完整的之后输出
                messageList.remove(messageList.size() - 1);
                for (AiMessage m : messageList) {
                    context.setMessage(m);
                    listener.onMessage(context);
                    Thread.sleep(10);
                }
            }
        } catch (IOException ex) {
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
        return resObj.getObject("data", (Type) CozeChatContext.class);
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
        if (messageList == null || messageList.isEmpty()) {
            return null;
        }
        List<JSONObject> objects = messageList.stream()
            .map(JSONObject.class::cast)
            .filter(obj -> "answer".equals(obj.getString("type")))
            .collect(Collectors.toList());

        JSONObject answer = !objects.isEmpty() ? objects.get(0) : null;
        if (answer != null) {
            answer.put("usage", cozeChat.getUsage());
            answer.put("content", answer.getString("content"));
            return aiMessageParser.parse(answer);
        }
        return null;
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        throw new UnsupportedOperationException("Not supported yet.");
    }


    @Override
    public <R extends AiMessageResponse> R chat(Prompt<R> prompt, ChatOptions options) {
        CountDownLatch latch = new CountDownLatch(1);
        Message[] messages = new Message[1];
        String[] responses = new String[1];
        Throwable[] failureThrowable = new Throwable[1];

        this.botChat(prompt, new CozeRequestListener() {
            @Override
            public void onMessage(CozeChatContext context) {
                boolean isCompleted = Objects.equals(context.getStatus(), "completed");
                if (isCompleted) {
                    AiMessage answer = getChatAnswer(context);
                    messages[0] = answer;
                    responses[0] = context.getResponse();
                }
            }

            @Override
            public void onFailure(CozeChatContext context, Throwable throwable) {
                failureThrowable[0] = throwable;
                responses[0] = context.getResponse();
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

        AbstractBaseMessageResponse<?> response = new AiMessageResponse(responses[0], (AiMessage) messages[0]);

        if (messages[0] == null || failureThrowable[0] != null) {
            response.setError(true);
            if (failureThrowable[0] != null) {
                response.setErrorMessage(failureThrowable[0].getMessage());
            }
        }

        return (R) response;
    }

    @Override
    public <R extends AiMessageResponse> void chatStream(Prompt<R> prompt, StreamResponseListener<R> listener, ChatOptions options) {
        this.botChat(prompt, new CozeRequestListener() {
            @Override
            public void onMessage(CozeChatContext context) {
                AiMessageResponse response = new AiMessageResponse(context.getResponse(), context.getMessage());
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
