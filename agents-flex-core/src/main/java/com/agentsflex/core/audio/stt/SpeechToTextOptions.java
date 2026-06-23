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

import com.agentsflex.core.util.Metadata;
import com.agentsflex.core.util.StringUtil;

public class SpeechToTextOptions extends Metadata {

    /**
     * The output format (e.g., "mp3", "wav").
     */
    private String format;
    /**
     * 采样率
     */
    private Integer sampleRate;

    public SpeechToTextOptions() {
    }

    public String getFormat() {
        return format;
    }

    public String getFormatOrDefault(String defaultValue) {
        return StringUtil.hasText(format) ? format : defaultValue;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Integer getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    @Override
    public String toString() {
        return "SpeechToTextOptions{" +
            "format='" + format + '\'' +
            ", sampleRate=" + sampleRate +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
