///*
// *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
// *  <p>
// *  Licensed under the Apache License, Version 2.0 (the "License");
// *  you may not use this file except in compliance with the License.
// *  You may obtain a copy of the License at
// *  <p>
// *  http://www.apache.org/licenses/LICENSE-2.0
// *  <p>
// *  Unless required by applicable law or agreed to in writing, software
// *  distributed under the License is distributed on an "AS IS" BASIS,
// *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// *  See the License for the specific language governing permissions and
// *  limitations under the License.
// */
//package com.agentsflex.llm.spark;
//
//import com.agentsflex.core.message.AiMessage;
//import com.agentsflex.core.model.chat.BaseChatModel;
//import com.agentsflex.core.model.client.StreamContext;
//import com.agentsflex.core.model.chat.ChatOptions;
//import com.agentsflex.core.model.chat.StreamResponseListener;
//import com.agentsflex.core.model.chat.response.AbstractBaseMessageResponse;
//import com.agentsflex.core.model.chat.response.AiMessageResponse;
//import com.agentsflex.core.model.client.BaseStreamClientListener;
//import com.agentsflex.core.model.client.StreamClient;
//import com.agentsflex.core.model.client.StreamClientListener;
//import com.agentsflex.core.model.client.impl.WebSocketClient;
//import com.agentsflex.core.parser.AiMessageParser;
//import com.agentsflex.core.prompt.Prompt;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.concurrent.CountDownLatch;
//
//public class SparkChatModel extends BaseChatModel<SparkChatConfig> {
//
//    private static final Logger logger = LoggerFactory.getLogger(SparkChatModel.class);
//    public AiMessageParser aiMessageParser = SparkLlmUtil.getAiMessageParser();
//
//
//    public SparkChatModel(SparkChatConfig config) {
//        super(config);
//    }
//
//    @Override
//    public AiMessageResponse doChat(Prompt prompt, ChatOptions options) {
//        CountDownLatch latch = new CountDownLatch(1);
//        Throwable[] failureThrowable = new Throwable[1];
//        AiMessageResponse[] messageResponse = {null};
//
//        waitResponse(prompt, options, messageResponse, latch, failureThrowable);
//
//        AiMessageResponse response = messageResponse[0];
//        Throwable fialureThrowable = failureThrowable[0];
//
//        if (response == null) {
//            if (fialureThrowable != null) {
//                response = new AiMessageResponse(prompt, "", null);
//            } else {
//                return null;
//            }
//        }
//
//        if (fialureThrowable != null || response.getMessage() == null) {
//            response.setError(true);
//            if (fialureThrowable != null) {
//                response.setErrorMessage(fialureThrowable.getMessage());
//            }
//        } else {
//            response.setError(false);
//        }
//
//        return response;
//    }
//
//
//    private void waitResponse(Prompt prompt
//        , ChatOptions options
//        , AbstractBaseMessageResponse<?>[] messageResponse
//        , CountDownLatch latch
//        , Throwable[] failureThrowable) {
//        chatStream(prompt, new StreamResponseListener() {
//            @Override
//            public void onMessage(StreamContext context, AiMessageResponse response) {
//                AiMessage message = response.getMessage();
//                if (message != null) message.setContent(message.getFullContent());
//
//                messageResponse[0] = response;
//            }
//
//            @Override
//            public void onStop(StreamContext context) {
//                StreamResponseListener.super.onStop(context);
//                latch.countDown();
//            }
//
//            @Override
//            public void onFailure(StreamContext context, Throwable throwable) {
//                logger.error(throwable.toString(), throwable);
//                failureThrowable[0] = throwable;
//            }
//        }, options);
//
//        try {
//            latch.await();
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//
//    @Override
//    public void doChatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
//        StreamClient streamClient = new WebSocketClient();
//        String url = SparkLlmUtil.createURL(config);
//        String payload = SparkLlmUtil.promptToPayload(prompt, config, options);
//        StreamClientListener clientListener = new BaseStreamClientListener(this, streamClient, listener, prompt, aiMessageParser);
//        streamClient.start(url, null, payload, clientListener, config);
//    }
//
//
//}
