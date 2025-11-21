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
package com.agentsflex.core.model.client;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class BaseStreamClientListener implements StreamClientListener {

    private final StreamResponseListener streamResponseListener;
    private final ChatContext chatContext;
    private final AiMessageParser messageParser;
    private final StreamContext context;
    private final AiMessage fullMessage = new AiMessage();
    private final AtomicBoolean finishedFlag = new AtomicBoolean(false);

    public BaseStreamClientListener(
        ChatModel chatModel,
        ChatContext chatContext,
        StreamClient client,
        StreamResponseListener streamResponseListener,
        AiMessageParser messageParser) {
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
            if (finishedFlag.compareAndSet(false, true)) {
                notifyLastMessageAndStop(response);
            }
            return;
        }

        try {
            JSONObject jsonObject = JSON.parseObject(response);
            AiMessage delta = messageParser.parse(jsonObject, chatContext);
            fullMessage.merge(delta); //核心：一行合并所有增量

            delta.setFullContent(fullMessage.getContent());
            delta.setFullReasoningContent(fullMessage.getReasoningContent());

            //最后 1 条消息
            if (delta.isLastMessage()) {
                if (finishedFlag.compareAndSet(false, true)) {
                    notifyLastMessageAndStop(response);
                }
            }
            //输出内容
            else if (hasContent(delta)) {
                AiMessageResponse resp = new AiMessageResponse(chatContext, response, delta);
                streamResponseListener.onMessage(context, resp);
            }
        } catch (Exception err) {
            streamResponseListener.onFailure(context, err);
            onStop(this.context.getClient());
        }
    }

    private void notifyLastMessageAndStop(String response) {
        AiMessageResponse resp = new AiMessageResponse(chatContext, response, fullMessage);
        streamResponseListener.onMessage(context, resp);
        onStop(this.context.getClient());
    }


    @Override
    public void onStop(StreamClient client) {
        context.setAiMessage(fullMessage);
        streamResponseListener.onStop(context);
    }

    @Override
    public void onFailure(StreamClient client, Throwable throwable) {
        context.setThrowable(throwable);
        streamResponseListener.onFailure(context, throwable);
    }

    private boolean hasContent(AiMessage delta) {
        return delta.getContent() != null ||
            delta.getReasoningContent() != null ||
            (delta.getCalls() != null && !delta.getCalls().isEmpty());
    }

    private boolean isFunctionCallExists(String id) {
        if (fullMessage.getCalls() == null || id == null) return false;
        for (FunctionCall call : fullMessage.getCalls()) {
            if (Objects.equals(call.getId(), id)) return true;
        }
        return false;
    }
}
