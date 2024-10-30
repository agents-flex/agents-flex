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
package com.agentsflex.core.llm.client;

import com.agentsflex.core.functions.Function;
import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.Llm;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.MessageResponse;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.llm.response.FunctionMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.FunctionMessage;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.FunctionMessageParser;
import com.agentsflex.core.prompt.FunctionPrompt;
import com.agentsflex.core.prompt.HistoriesPrompt;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.List;

public class BaseLlmClientListener implements LlmClientListener {

    private final StreamResponseListener streamResponseListener;
    private final Prompt prompt;
    private final AiMessageParser messageParser;
    private final FunctionMessageParser functionMessageParser;
    private final StringBuilder fullMessage = new StringBuilder();
    private AiMessage lastAiMessage;
    private boolean isFunctionCalling = false;
    private final ChatContext context;

    public BaseLlmClientListener(Llm llm
        , LlmClient client
        , StreamResponseListener streamResponseListener
        , Prompt prompt
        , AiMessageParser messageParser
        , FunctionMessageParser functionMessageParser) {

        this.streamResponseListener = streamResponseListener;
        this.prompt = prompt;
        this.messageParser = messageParser;
        this.functionMessageParser = functionMessageParser;
        this.context = new ChatContext(llm, client);

        if (prompt instanceof FunctionPrompt) {
            if (functionMessageParser == null) {
                throw new IllegalArgumentException("Can not support Function Calling");
            } else {
                isFunctionCalling = true;
            }
        }
    }


    @Override
    public void onStart(LlmClient client) {
        streamResponseListener.onStart(context);
    }

    @Override
    public void onMessage(LlmClient client, String response) {
        if (StringUtil.noText(response) || "[DONE]".equalsIgnoreCase(response.trim())) {
            return;
        }
        JSONObject jsonObject = JSON.parseObject(response);
        if (isFunctionCalling) {
            FunctionMessage functionMessage = functionMessageParser.parse(jsonObject);
            List<Function> functions = ((FunctionPrompt) prompt).getFunctions();
            MessageResponse<?> functionMessageResponse = new FunctionMessageResponse(response, functions, functionMessage);
            //noinspection unchecked
            streamResponseListener.onMessage(context, functionMessageResponse);
        } else {
            lastAiMessage = messageParser.parse(jsonObject);
            fullMessage.append(lastAiMessage.getContent());
            lastAiMessage.setFullContent(fullMessage.toString());
            MessageResponse<?> aiMessageResponse = new AiMessageResponse(response, lastAiMessage);
            //noinspection unchecked
            streamResponseListener.onMessage(context, aiMessageResponse);
        }
    }

    @Override
    public void onStop(LlmClient client) {
        if (lastAiMessage != null) {
            if (this.prompt instanceof HistoriesPrompt) {
                ((HistoriesPrompt) this.prompt).addMessage(lastAiMessage);
            }
        }

        streamResponseListener.onStop(context);
    }

    @Override
    public void onFailure(LlmClient client, Throwable throwable) {
        streamResponseListener.onFailure(context, throwable);
    }


}
