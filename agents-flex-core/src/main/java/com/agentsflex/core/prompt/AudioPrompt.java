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
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class AudioPrompt extends TextPrompt {

    private String audioUrl;

    public AudioPrompt(String content) {
        super(content);
    }

    public AudioPrompt(String content, String audioUrl) {
        super(content);
        this.audioUrl = audioUrl;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }

    public String toUrl() {
        if (StringUtil.hasText(audioUrl)) {
            return audioUrl;
        }
        return null;
    }


    @Override
    public List<Message> toMessages() {
        return Collections.singletonList(new TextAndAudioMessage(this));
    }


    @Override
    public String toString() {
        return "AudioPrompt{" +
            "audioUrl='" + audioUrl + '\'' +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }

    public static class TextAndAudioMessage extends HumanMessage {

        private final AudioPrompt prompt;

        public TextAndAudioMessage(AudioPrompt prompt) {
            this.prompt = prompt;
        }

        public AudioPrompt getPrompt() {
            return prompt;
        }

        @Override
        public Object getMessageContent() {
            List<Map<String, Object>> messageContent = new ArrayList<>();
            messageContent.add(Maps.of("type", "text").set("text", prompt.content));
            messageContent.add(Maps.of("type", "audio_url").set("audio_url", Maps.of("url", prompt.toUrl())));
            return messageContent;
        }

    }
}
