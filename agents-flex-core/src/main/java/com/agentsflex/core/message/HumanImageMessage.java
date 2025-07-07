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
package com.agentsflex.core.message;

import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.core.util.Maps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HumanImageMessage extends HumanMessage {

    private final ImagePrompt prompt;

    public HumanImageMessage(ImagePrompt prompt) {
        this.prompt = prompt;
    }

    public ImagePrompt getPrompt() {
        return prompt;
    }

    @Override
    public Object getMessageContent() {
        List<Map<String, Object>> messageContent = new ArrayList<>();
        messageContent.add(Maps.of("type", "text").set("text", prompt.getContent()));
        messageContent.add(Maps.of("type", "image_url").set("image_url", Maps.of("url", prompt.toUrl())));
        return messageContent;
    }

}
