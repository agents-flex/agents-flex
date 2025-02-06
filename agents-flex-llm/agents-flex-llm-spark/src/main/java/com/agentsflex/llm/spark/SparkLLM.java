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
package com.agentsflex.llm.spark;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.BaseLLM;
import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.client.BaseLlmClientListener;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.llm.client.LlmClient;
import com.agentsflex.core.llm.client.LlmClientListener;
import com.agentsflex.core.llm.client.impl.WebSocketClient;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AbstractBaseMessageResponse;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.SleepUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSONPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;

public class SparkLLM extends BaseLLM<SparkLlmConfig> {

    private static final Logger logger = LoggerFactory.getLogger(SparkLLM.class);
    public AiMessageParser aiMessageParser = SparkLlmUtil.getAiMessageParser();

    private final HttpClient httpClient = new HttpClient();


    public SparkLLM(SparkLlmConfig config) {
        super(config);
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        return embed(document, options, 0);
    }


    public VectorData embed(Document document, EmbeddingOptions options, int tryTimes) {
        String payload = SparkLlmUtil.embedPayload(config, document);
        String resp = httpClient.post(SparkLlmUtil.createEmbedURL(config), null, payload);
        if (StringUtil.noText(resp)) {
            return null;
        }

        Integer code = JSONPath.read(resp, "$.header.code", Integer.class);
        if (code == null) {
            logger.error(resp);
            return null;
        }

        if (code != 0) {
            //11202	授权错误：秒级流控超限。秒级并发超过授权路数限制
            if (code.equals(11202) && tryTimes < 3) {
                SleepUtil.sleep(200);
                return embed(document, options, tryTimes + 1);
            } else {
                logger.error(resp);
                return null;
            }
        }

        String text = JSONPath.read(resp, "$.payload.feature.text", String.class);
        if (StringUtil.noText(text)) {
            logger.error(resp);
            return null;
        }

        byte[] buffer = Base64.getDecoder().decode(text);
        double[] vector = new double[buffer.length / 4];
        for (int i = 0; i < vector.length; i++) {
            int n = i * 4;
            vector[i] = ByteBuffer.wrap(buffer, n, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        }

        VectorData vectorData = new VectorData();
        vectorData.setVector(vector);
        return vectorData;
    }


    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] failureThrowable = new Throwable[1];
        AiMessageResponse[] messageResponse = {null};

        waitResponse(prompt, options, messageResponse, latch, failureThrowable);

        AiMessageResponse response = messageResponse[0];
        Throwable fialureThrowable = failureThrowable[0];

        if (response == null) {
            if (fialureThrowable != null) {
                response = new AiMessageResponse(prompt, "", null);
            } else {
                return null;
            }
        }

        if (fialureThrowable != null || response.getMessage() == null) {
            response.setError(true);
            if (fialureThrowable != null) {
                response.setErrorMessage(fialureThrowable.getMessage());
            }
        } else {
            response.setError(false);
        }

        return response;
    }


    private void waitResponse(Prompt prompt
        , ChatOptions options
        , AbstractBaseMessageResponse<?>[] messageResponse
        , CountDownLatch latch
        , Throwable[] failureThrowable) {
        chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                AiMessage message = response.getMessage();
                if (message != null) message.setContent(message.getFullContent());

                messageResponse[0] = response;
            }

            @Override
            public void onStop(ChatContext context) {
                StreamResponseListener.super.onStop(context);
                latch.countDown();
            }

            @Override
            public void onFailure(ChatContext context, Throwable throwable) {
                logger.error(throwable.toString(), throwable);
                failureThrowable[0] = throwable;
            }
        }, options);

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        LlmClient llmClient = new WebSocketClient();
        String url = SparkLlmUtil.createURL(config);
        String payload = SparkLlmUtil.promptToPayload(prompt, config, options);
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiMessageParser);
        llmClient.start(url, null, payload, clientListener, config);
    }


}
