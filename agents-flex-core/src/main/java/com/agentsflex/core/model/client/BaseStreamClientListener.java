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
package com.agentsflex.core.model.client;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

public class BaseStreamClientListener implements StreamClientListener {

    private final StreamResponseListener streamResponseListener;
    private final ChatContext chatContext;
    private final AiMessageParser<JSONObject> messageParser;
    private final StreamContext context;
    private final AiMessage fullMessage = new AiMessage();
    private final AtomicBoolean finishedFlag = new AtomicBoolean(false);
    private final AtomicBoolean stoppedFlag = new AtomicBoolean(false);
    private final AtomicBoolean isFailure = new AtomicBoolean(false);

    public BaseStreamClientListener(
        ChatModel chatModel,
        ChatContext chatContext,
        StreamClient client,
        StreamResponseListener streamResponseListener,
        AiMessageParser<JSONObject> messageParser) {
        this.streamResponseListener = streamResponseListener;
        this.chatContext = chatContext;
        this.messageParser = messageParser;
        this.context = new StreamContext(chatModel, chatContext, client);
    }

    @Override
    public void onStart(StreamClient client) {
        streamResponseListener.onStart(context);
    }

    @Override
    public void onMessage(StreamClient client, String response) {
        if (StringUtil.noText(response) || "[DONE]".equalsIgnoreCase(response.trim()) || finishedFlag.get()) {
            notifyLastMessageAndStop(response);
            return;
        }

        try {
            JSONObject jsonObject = JSON.parseObject(response);
            AiMessage delta = messageParser.parse(jsonObject, chatContext);

            //合并 增量 delta 到 fullMessage
            fullMessage.merge(delta);

            // 设置 delta 全内容
            delta.setFullContent(fullMessage.getContent());
            delta.setFullReasoningContent(fullMessage.getReasoningContent());

            //输出内容
            AiMessageResponse resp = new AiMessageResponse(chatContext, response, delta);
            streamResponseListener.onMessage(context, resp);
        } catch (Exception err) {
            streamResponseListener.onFailure(context, err);
            onStop(this.context.getClient());
        }
    }

    private void notifyLastMessage(String response) {
        if (finishedFlag.compareAndSet(false, true)) {
            finalizeFullMessage();
            fullMessage.setFinished(true);
            AiMessageResponse resp = new AiMessageResponse(chatContext, response, fullMessage);
            streamResponseListener.onMessage(context, resp);
        }
    }

    private void notifyLastMessageAndStop(String response) {
        try {

            notifyLastMessage(response);

        } finally {
            if (stoppedFlag.compareAndSet(false, true)) {
                context.setAiMessage(fullMessage);
                streamResponseListener.onStop(context);
            }
        }
    }


    @Override
    public void onStop(StreamClient client) {
        try {
            if (!isFailure.get()) {
                // onStop 在 sse 的 onClosed 中会被调用，可以用于在 onMessage 出现异常时进行兜底
                notifyLastMessage(null);
            }
        } finally {
            if (stoppedFlag.compareAndSet(false, true)) {
                context.setAiMessage(fullMessage);
                streamResponseListener.onStop(context);
            }
        }
    }

    private void finalizeFullMessage() {
        String currentContent = fullMessage.getContent();
        String currentReasoningContent = fullMessage.getReasoningContent();

        fullMessage.setFullContent(currentContent);
        fullMessage.setContent(null);

        fullMessage.setFullReasoningContent(currentReasoningContent);
        fullMessage.setReasoningContent(null);
    }


    @Override
    public void onFailure(StreamClient client, Throwable throwable) {
        if (isFailure.compareAndSet(false, true)) {
            context.setThrowable(throwable);
            streamResponseListener.onFailure(context, throwable);
        }
    }

}
