/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.audio.tts;

public class TextToSpeechRequest {

    private String text;

    private TextToSpeechOptions options = new TextToSpeechOptions();

    public TextToSpeechRequest() {
    }

    public TextToSpeechRequest(String text) {
        this.text = text;
    }

    public TextToSpeechRequest(String text, TextToSpeechOptions options) {
        this.text = text;
        this.options = options;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public TextToSpeechOptions getOptions() {
        return options;
    }

    public void setOptions(TextToSpeechOptions options) {
        this.options = options;
    }

    @Override
    public String toString() {
        return "TextToSpeechRequest{" +
            "text='" + text + '\'' +
            ", options=" + options +
            '}';
    }
}
