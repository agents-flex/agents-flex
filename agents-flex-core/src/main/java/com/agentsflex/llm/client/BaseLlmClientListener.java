/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.llm.client;

import com.agentsflex.functions.Function;
import com.agentsflex.llm.ChatContext;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.ChatResponse;
import com.agentsflex.llm.Llm;
import com.agentsflex.llm.response.FunctionResultResponse;
import com.agentsflex.llm.response.MessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.FunctionMessage;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.HistoriesPrompt;
import com.agentsflex.prompt.Prompt;

import java.util.List;

public class BaseLlmClientListener implements LlmClientListener {

    private final Llm llm;
    private final LlmClient client;
    private final ChatListener chatListener;
    private final Prompt prompt;
    private final AiMessageParser messageParser;
    private final FunctionMessageParser functionInfoParser;
    private final StringBuilder fullMessage = new StringBuilder();
    private AiMessage lastAiMessage;
    private boolean isFunctionCalling = false;

    private final ChatContext context;

    public BaseLlmClientListener(Llm llm, LlmClient client, ChatListener chatListener, Prompt prompt
        , AiMessageParser messageParser
        , FunctionMessageParser functionInfoParser) {
        this.llm = llm;
        this.client = client;
        this.chatListener = chatListener;
        this.prompt = prompt;
        this.messageParser = messageParser;
        this.functionInfoParser = functionInfoParser;
        this.context = new ChatContext(llm, client);

        if (prompt instanceof FunctionPrompt) {
            if (functionInfoParser == null) {
                throw new IllegalArgumentException("Can not support Function Calling");
            } else {
                isFunctionCalling = true;
            }
        }
    }


    @Override
    public void onStart(LlmClient client) {
        chatListener.onStart(context);
    }

    @Override
    public void onMessage(LlmClient client, String response) {
        if (isFunctionCalling) {
            FunctionMessage functionInfo = functionInfoParser.parseMessage(response);
            List<Function<?>> functions = ((FunctionPrompt) prompt).getFunctions();
            ChatResponse<?> r = new FunctionResultResponse(functions, functionInfo);
            chatListener.onMessage(context, r);
        } else {
            lastAiMessage = messageParser.parseMessage(response);
            fullMessage.append(lastAiMessage.getContent());
            lastAiMessage.setFullContent(fullMessage.toString());
            ChatResponse<?> r = new MessageResponse(lastAiMessage);
            chatListener.onMessage(context, r);
        }
    }

    @Override
    public void onStop(LlmClient client) {
        if (lastAiMessage != null) {
            if (this.prompt instanceof HistoriesPrompt) {
                ((HistoriesPrompt) this.prompt).addMessage(lastAiMessage);
            }
        }

        chatListener.onStop(context);
    }

    @Override
    public void onFailure(LlmClient client, Throwable throwable) {
        chatListener.onFailure(context, throwable);
    }


    public interface AiMessageParser {
        AiMessage parseMessage(String response);
    }

    public interface FunctionMessageParser {
        FunctionMessage parseMessage(String response);
    }
}