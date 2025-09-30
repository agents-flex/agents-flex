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

import com.agentsflex.core.message.HumanAudioMessage;
import com.agentsflex.core.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class AudioPrompt extends TextPrompt {

    private List<String> audioUrls;

    public AudioPrompt(String content) {
        super(content);
    }

    public AudioPrompt(String content, String audioUrl) {
        super(content);
        this.audioUrls = new ArrayList<>(1);
        this.audioUrls.add(audioUrl);
    }

    public AudioPrompt(String content, List<String> audioUrls) {
        super(content);
        this.audioUrls = audioUrls;
    }

    public List<String> getAudioUrls() {
        return audioUrls;
    }

    public void setAudioUrls(List<String> audioUrls) {
        this.audioUrls = audioUrls;
    }

    public void addAudioUrl(String audioUrl) {
        if (audioUrls == null) {
            audioUrls = new ArrayList<>(1);
        }
        audioUrls.add(audioUrl);
    }

    @Override
    public List<Message> toMessages() {
        return Collections.singletonList(new HumanAudioMessage(this));
    }


    @Override
    public String toString() {
        return "AudioPrompt{" +
            "audioUrls=" + audioUrls +
            ", content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
