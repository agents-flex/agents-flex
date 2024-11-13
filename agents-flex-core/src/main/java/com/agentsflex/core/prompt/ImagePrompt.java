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
package com.agentsflex.core.prompt;

import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.util.Maps;

import java.util.*;

public class ImagePrompt extends TextPrompt {

    private String imageUrl;

    public ImagePrompt(String content) {
        super(content);
    }

    public ImagePrompt(String content, String imageUrl) {
        super(content);
        this.imageUrl = imageUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    @Override
    public List<Message> toMessages() {
        return Collections.singletonList(new TextAndImageMessage(this));
    }


    @Override
    public String toString() {
        return "ImagePrompt{" +
            "imageUrl='" + imageUrl + '\'' +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }


    public static class TextAndImageMessage extends HumanMessage {

        private final ImagePrompt prompt;

        public TextAndImageMessage(ImagePrompt prompt) {
            this.prompt = prompt;
        }

        public ImagePrompt getPrompt() {
            return prompt;
        }

        @Override
        public Object getMessageContent() {
            List<Map<String, Object>> messageContent = new ArrayList<>();
            messageContent.add(Maps.of("type", "text").set("text", prompt.content));
            messageContent.add(Maps.of("type", "image_url").set("image_url", Maps.of("url", prompt.imageUrl)));
            return messageContent;
        }
    }
}
