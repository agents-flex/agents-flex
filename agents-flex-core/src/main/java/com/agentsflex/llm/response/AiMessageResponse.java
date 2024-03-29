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
package com.agentsflex.llm.response;

import com.agentsflex.llm.MessageResponse;
import com.agentsflex.message.AiMessage;

public class AiMessageResponse implements MessageResponse<AiMessage> {
    private final AiMessage aiMessage;

    public AiMessageResponse(AiMessage aiMessage) {
        this.aiMessage = aiMessage;
    }

    @Override
    public AiMessage getMessage() {
        return aiMessage;
    }
}
