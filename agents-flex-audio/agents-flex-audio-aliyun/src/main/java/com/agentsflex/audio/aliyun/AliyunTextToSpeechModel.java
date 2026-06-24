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
package com.agentsflex.audio.aliyun;

import com.agentsflex.core.audio.tts.TextToSpeechModel;
import com.agentsflex.core.audio.tts.TextToSpeechRequest;
import com.agentsflex.core.audio.tts.TextToSpeechResponse;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse;

import java.nio.ByteBuffer;

public class AliyunTextToSpeechModel implements TextToSpeechModel {

    private AliyunTextToSpeechConfig config;

    public AliyunTextToSpeechModel(AliyunTextToSpeechConfig config) {
        this.config = config;
    }

    public AliyunTextToSpeechConfig getConfig() {
        return config;
    }

    public void setConfig(AliyunTextToSpeechConfig config) {
        this.config = config;
    }

    @Override
    public TextToSpeechResponse tts(TextToSpeechRequest request) {
        TextToSpeechResponse response = new TextToSpeechResponse();
        SpeechSynthesizer speechSynthesizer = null;
        try {
            speechSynthesizer = getSpeechSynthesizer(request, response);
            speechSynthesizer.start();
            speechSynthesizer.waitForComplete();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (speechSynthesizer != null) {
                speechSynthesizer.close();
            }
        }
        return response;
    }


    private SpeechSynthesizer getSpeechSynthesizer(TextToSpeechRequest request, TextToSpeechResponse resp) throws Exception {
        NlsClient nlsClient = new NlsClient(config.createToken());
        SpeechSynthesizer synthesizer = new SpeechSynthesizer(nlsClient, new SpeechSynthesizerListener() {
            /**
             * 语音合成结束
             *
             * @param response
             */
            @Override
            public void onComplete(SpeechSynthesizerResponse response) {
            }

            /**
             * 失败处理
             *
             * @param response
             */
            @Override
            public void onFail(SpeechSynthesizerResponse response) {
                resp.setSuccess(false);
                resp.setMessage(response.getStatusText());
            }

            @Override
            public void onMessage(ByteBuffer message) {
                byte[] bytesArray = new byte[message.remaining()];
                message.get(bytesArray, 0, bytesArray.length);
                resp.addResult(bytesArray);
            }
        });

        synthesizer.setText(request.getText());

        synthesizer.setAppKey(config.getAppKey());

        //设置返回音频的编码格式。
        synthesizer.setFormat(OutputFormatEnum.valueOf(request.getOptions().getFormatOrDefault("mp3").toUpperCase()));

        //设置返回音频的采样率。
//        synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
        synthesizer.setSampleRate(request.getOptions().getSampleRateOrDefault(16000));
        //发音人。注意Java SDK不支持调用超高清场景对应的发音人（例如"zhiqi"），如需调用请使用restfulAPI方式。
        synthesizer.setVoice(request.getOptions().getVoice());

        //音量，范围是0~100，可选，默认50。
        synthesizer.setVolume(request.getOptions().getVolumeOrDefault(50));
        //语调，范围是-500~500，可选，默认是0。
        synthesizer.setPitchRate(0);

        //语速，范围是-500~500，默认是0。
        synthesizer.setSpeechRate(request.getOptions().getSpeedOrDefault(0).intValue());


        return synthesizer;
    }
}
