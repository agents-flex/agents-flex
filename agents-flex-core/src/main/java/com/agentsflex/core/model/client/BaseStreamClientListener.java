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
            if (finishedFlag.compareAndSet(false, true)) {
                notifyLastMessageAndStop(response);
            }
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
            if (hasContent(delta)) {
                AiMessageResponse resp = new AiMessageResponse(chatContext, response, delta);
                streamResponseListener.onMessage(context, resp);
            }

            // 在一些平台中，比如阿里云 【百炼】平台，倒数第 2 条为 "finish_reason":"stop", 最后 1 条内容为 usage 的内容,
            // 文档：https://bailian.console.aliyun.com/?tab=api#/api/?type=model&url=2712576
            // 因此，当 fullMessage 已经是 finalDelta 时，则认为已经结束（倒数第2条 delta 会让 fullMessage 为 finalDelta）
            if (!delta.isFinalDelta() && fullMessage.isFinalDelta()) {
                if (finishedFlag.compareAndSet(false, true)) {
                    notifyLastMessageAndStop(response);
                }
            }
        } catch (Exception err) {
            streamResponseListener.onFailure(context, err);
            onStop(this.context.getClient());
        }
    }

    private void notifyLastMessage(String response) {
        if (finishedFlag.compareAndSet(false, true)) {
            fullMessage.setFinished(true);
            AiMessageResponse resp = new AiMessageResponse(chatContext, response, fullMessage);
            streamResponseListener.onMessage(context, resp);
        }
    }

    private void notifyLastMessageAndStop(String response) {
        try {
            notifyLastMessage(response);
        } finally {
            onStop(this.context.getClient());
        }
    }


    @Override
    public void onStop(StreamClient client) {

        // onStop 在 sse 的 onClosed 中会被调用，可以用于在 onMessage 出现异常时进行兜底
        notifyLastMessage(null);

        if (stoppedFlag.compareAndSet(false, true)) {
            context.setAiMessage(fullMessage);
            streamResponseListener.onStop(context);
        }
    }

    @Override
    public void onFailure(StreamClient client, Throwable throwable) {
        context.setThrowable(throwable);
        streamResponseListener.onFailure(context, throwable);
    }

    private boolean hasContent(AiMessage delta) {
        return delta.getContent() != null ||
            delta.getReasoningContent() != null ||
            (delta.getToolCalls() != null && !delta.getToolCalls().isEmpty());
    }

}
