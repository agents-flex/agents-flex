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

import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.model.client.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.Test;

public class CozeChatModelTest {

    String token = "changeit";
    String botId = "changeit";
    String userId = "changeit";
    String textPrompt = "你是谁？告诉我你能干什么事情？并列出5点你能做的事情";
    CozeChatConfig config;
    CozeChatModel llm;
    CozeChatOptions options;

    public CozeChatModelTest() {
        config = new CozeChatConfig();
        config.setApiKey(token);
        config.setDebug(true);

        llm  = new CozeChatModel(config);
        options = new CozeChatOptions();
        options.setBotId(botId);
        options.setUserId(userId);
    }


    @Test
    public void testChat() {
        SimplePrompt prompt = new SimplePrompt(textPrompt);
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
        SimplePrompt prompt = new SimplePrompt(textPrompt);
        llm.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                AiMessage message = response.getMessage();
                System.out.print(message.getContent());
            }

            @Override
            public void onStart(StreamContext context) {
                StreamResponseListener.super.onStart(context);
            }

            @Override
            public void onStop(StreamContext context) {
                // 停止了
                CozeStreamContext ccc = (CozeStreamContext) context;
                System.out.println(ccc.getUsage());
                StreamResponseListener.super.onStop(context);
            }

            @Override
            public void onFailure(StreamContext context, Throwable throwable) {
                //发生错误了
            }
        }, options);
    }

}
