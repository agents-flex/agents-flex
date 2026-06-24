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
package com.agentsflex.audio.tencent;

import com.agentsflex.core.audio.tts.TextToSpeechModel;
import com.agentsflex.core.audio.tts.TextToSpeechRequest;
import com.agentsflex.core.audio.tts.TextToSpeechResponse;
import com.tencent.core.ws.Credential;
import com.tencent.core.ws.SpeechClient;
import com.tencent.ttsv2.*;

import java.nio.ByteBuffer;
import java.util.UUID;

public class TencentTextToSpeechModel implements TextToSpeechModel {

    static SpeechClient speechClient = new SpeechClient(TtsConstant.DEFAULT_TTS_REQ_URL);

    private TencentTextToSpeechConfig config;

    public TencentTextToSpeechModel(TencentTextToSpeechConfig config) {
        this.config = config;
    }

    public TencentTextToSpeechConfig getConfig() {
        return config;
    }

    public void setConfig(TencentTextToSpeechConfig config) {
        this.config = config;
    }

    @Override
    public TextToSpeechResponse tts(TextToSpeechRequest request) {
        TextToSpeechResponse response = new TextToSpeechResponse();
        SpeechSynthesizer speechSynthesizer = null;
        try {
            speechSynthesizer = getSpeechSynthesizer(request, response);
            speechSynthesizer.start();
            speechSynthesizer.stop();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (speechSynthesizer != null) {
                speechSynthesizer.close();
            }
        }
        return response;
    }


    private SpeechSynthesizer getSpeechSynthesizer(TextToSpeechRequest request, TextToSpeechResponse response) throws Exception {
        Credential credential = config.toCredential();

        SpeechSynthesizerRequest speechSynthesizerRequest = new SpeechSynthesizerRequest();
        speechSynthesizerRequest.setText(request.getText() +"\n");
        speechSynthesizerRequest.setVoiceType(301036);
        speechSynthesizerRequest.setVolume(0f);
        speechSynthesizerRequest.setSpeed(0f);
        speechSynthesizerRequest.setCodec(request.getOptions().getFormatOrDefault("mp3"));
        speechSynthesizerRequest.setSampleRate(16000);
        speechSynthesizerRequest.setEnableSubtitle(true);
        speechSynthesizerRequest.setEmotionCategory("happy");
        speechSynthesizerRequest.setEmotionIntensity(100);
        speechSynthesizerRequest.setSessionId(UUID.randomUUID().toString());//sessionId，需要保持全局唯一（推荐使用 uuid），遇到问题需要提供该值方便服务端排查
        speechSynthesizerRequest.set("SegmentRate", 0); //sdk暂未支持参数，可通过该方法设置


        return new SpeechSynthesizer(speechClient,credential, speechSynthesizerRequest, new SpeechSynthesizerListener() {

            @Override
            public void onSynthesisStart(SpeechSynthesizerResponse response1) {
            }

            @Override
            public void onSynthesisEnd(SpeechSynthesizerResponse response1) {
            }

            @Override
            public void onAudioResult(ByteBuffer data) {
                byte[] bytesArray = new byte[data.remaining()];
                data.get(bytesArray, 0, bytesArray.length);
                response.addResult(bytesArray);
            }

            @Override
            public void onTextResult(SpeechSynthesizerResponse response1) {
            }

            /**
             * 请求失败
             *
             * @param response1
             */
            @Override
            public void onSynthesisFail(SpeechSynthesizerResponse response1) {
                response.setSuccess(false);
                response.setMessage(response1.getMessage());
            }
        });
    }
}
