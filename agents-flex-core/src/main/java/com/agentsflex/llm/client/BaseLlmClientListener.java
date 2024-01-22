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

import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.Llm;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.HistoriesPrompt;
import com.agentsflex.prompt.Prompt;

public class BaseLlmClientListener implements LlmClientListener {

    private final Llm llm;
    private final ChatListener chatListener;

    private final Prompt prompt;

    private final MessageParser messageParser;

    private final StringBuilder fullMessage = new StringBuilder();

    private AiMessage lastAiMessage;

    public BaseLlmClientListener(Llm llm, ChatListener chatListener, Prompt prompt, MessageParser messageParser) {
        this.llm = llm;
        this.chatListener = chatListener;
        this.prompt = prompt;
        this.messageParser = messageParser;
    }


    @Override
    public void onStart(LlmClient client) {
        chatListener.onStart(llm);
    }

    @Override
    public void onMessage(LlmClient client, String response) {
        lastAiMessage =  messageParser.parseMessage(response);
        fullMessage.append(lastAiMessage.getContent());
        chatListener.onMessage(llm, lastAiMessage);
    }

    @Override
    public void onStop(LlmClient client) {
        if (lastAiMessage != null){

            lastAiMessage.setFullContent(fullMessage.toString());

            if (this.prompt instanceof HistoriesPrompt){
                ((HistoriesPrompt) this.prompt).addMessage(lastAiMessage);
            }
        }

        chatListener.onStop(llm);
    }

    @Override
    public void onFailure(LlmClient client, Throwable throwable) {
        chatListener.onFailure(llm, throwable);
    }


    public interface MessageParser{
        AiMessage parseMessage(String response);
    }
}
