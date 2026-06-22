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
package com.agentsflex.core.audio.stt;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.util.IOUtil;

import java.io.File;
import java.io.InputStream;
import java.util.Base64;

public class SpeechToTextRequest {

    private File audioFile;
    private String audioUrl;
    private InputStream audioStream;

    private SpeechToTextOptions options = SpeechToTextOptions.NULL;

    public File getAudioFile() {
        return audioFile;
    }

    public void setAudioFile(File audioFile) {
        this.audioFile = audioFile;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }

    public InputStream getAudioStream() {
        return audioStream;
    }

    public void setAudioStream(InputStream audioStream) {
        this.audioStream = audioStream;
    }

    public SpeechToTextOptions getOptions() {
        return options;
    }

    public void setOptions(SpeechToTextOptions options) {
        this.options = options;
    }


    public byte[] getAudioBytes() {
        if (audioFile != null) {
            return IOUtil.readBytes(audioFile);
        } else if (audioUrl != null) {
            return new HttpClient().getBytes(audioUrl);
        } else if (audioStream != null) {
            return IOUtil.readBytes(audioStream);
        } else {
            throw new IllegalArgumentException("Audio source must not be null");
        }
    }

    public String getAudioBase64() {
        byte[] audioBytes = getAudioBytes();
        if (audioBytes == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(audioBytes);
    }

    @Override
    public String toString() {
        return "SpeechToTextRequest{" +
            "audioFile=" + audioFile +
            ", audioUrl='" + audioUrl + '\'' +
            ", audioStream=" + audioStream +
            ", options=" + options +
            '}';
    }


}
