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

import com.agentsflex.core.util.Metadata;
import com.agentsflex.core.util.StringUtil;

public class TextToSpeechOptions extends Metadata {
    /**
     * 语音合成模型
     */
    private String model;
    /**
     * 发音人
     */
    private String voice;
    /**
     * The output format (e.g., "mp3", "wav").
     */
    private String format;
    /**
     * 语速
     */
    private Double speed;
    /**
     * 音量
     */
    private Integer volume;
    /**
     * 采样率
     */
    private Integer sampleRate;

    public TextToSpeechOptions() {
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getVoice() {
        return voice;
    }

    public String getVoiceOrDefault(String defaultValue) {
        return StringUtil.hasText(voice) ? voice : defaultValue;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Double getSpeed() {
        return speed;
    }

    public Double getSpeedOrDefault(double defaultValue) {
        return speed == null ? defaultValue : speed;
    }

    public void setSpeed(Double speed) {
        this.speed = speed;
    }

    public Integer getVolume() {
        return volume;
    }

    public int getVolumeOrDefault(int defaultValue) {
        return volume == null ? defaultValue : volume;
    }

    public void setVolume(Integer volume) {
        this.volume = volume;
    }

    public Integer getSampleRate() {
        return sampleRate;
    }

    public int getSampleRateOrDefault(int defaultValue) {
        return sampleRate == null ? defaultValue : sampleRate;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }


    public String getFormatOrDefault(String defaultValue) {
        return StringUtil.hasText(format) ? format : defaultValue;
    }

    @Override
    public String toString() {
        return "TextToSpeechOptions{" +
            "model='" + model + '\'' +
            ", voice='" + voice + '\'' +
            ", format='" + format + '\'' +
            ", speed=" + speed +
            ", volume=" + volume +
            ", sampleRate=" + sampleRate +
            ", metadataMap=" + metadataMap +
            '}';
    }

}
