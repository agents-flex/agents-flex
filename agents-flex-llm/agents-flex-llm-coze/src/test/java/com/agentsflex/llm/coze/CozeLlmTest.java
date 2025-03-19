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

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.prompt.TextPrompt;
import org.junit.Test;

public class CozeLlmTest {

    String token = "changeit";
    String botId = "changeit";
    String userId = "changeit";
    String textPrompt = "你是谁？告诉我你能干什么事情？并列出5点你能做的事情";
    CozeLlmConfig config;
    CozeLlm llm;
    CozeChatOptions options;

    public CozeLlmTest() {
        config = new CozeLlmConfig();
        config.setApiKey(token);
        config.setDebug(true);

        llm  = new CozeLlm(config);
        options = new CozeChatOptions();
        options.setBotId(botId);
        options.setUserId(userId);
    }


    @Test
    public void testChat() {
        TextPrompt prompt = new TextPrompt(textPrompt);
        AiMessageResponse response = llm.chat(prompt, options);

        if (response.isError()){
            System.out.println(response.getErrorMessage());
            return;
        }


        AiMessage message = response.getMessage();
        String content = message.getContent();
        System.out.println(content);
    }

    @Test
    public void testChatStream() {
        TextPrompt prompt = new TextPrompt(textPrompt);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(ChatContext context, AiMessageResponse response) {
                AiMessage message = response.getMessage();
                System.out.print(message.getContent());
            }

            @Override
            public void onStart(ChatContext context) {
                StreamResponseListener.super.onStart(context);
            }

            @Override
            public void onStop(ChatContext context) {
                // 停止了
                CozeChatContext ccc = (CozeChatContext) context;
                System.out.println(ccc.getUsage());
                StreamResponseListener.super.onStop(context);
            }

            @Override
            public void onFailure(ChatContext context, Throwable throwable) {
                //发生错误了
            }
        }, options);
    }

}
