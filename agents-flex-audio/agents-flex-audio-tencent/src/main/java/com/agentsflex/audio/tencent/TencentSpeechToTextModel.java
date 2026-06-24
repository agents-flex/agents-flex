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

import com.agentsflex.core.audio.stt.SpeechToTextModel;
import com.agentsflex.core.audio.stt.SpeechToTextRequest;
import com.agentsflex.core.audio.stt.SpeechToTextResponse;
import com.tencent.asr.model.AsrConfig;
import com.tencent.asr.model.FlashRecognitionRequest;
import com.tencent.asr.model.FlashRecognitionResponse;
import com.tencent.asr.service.FlashRecognizer;

import java.util.List;

public class TencentSpeechToTextModel implements SpeechToTextModel {
    private TencentSpeechToTextConfig config;

    public TencentSpeechToTextModel(TencentSpeechToTextConfig config) {
        this.config = config;
    }

    public TencentSpeechToTextConfig getConfig() {
        return config;
    }

    public void setConfig(TencentSpeechToTextConfig config) {
        this.config = config;
    }

    @Override
    public SpeechToTextResponse stt(SpeechToTextRequest request) {
        SpeechToTextResponse response = new SpeechToTextResponse();
        AsrConfig asrConfig = AsrConfig.builder().appId(config.getAppId())
            .secretId(config.getSecretId())
            .secretKey(config.getSecretKey())
            .build();
        FlashRecognizer recognizer = new FlashRecognizer(asrConfig);
        byte[] data = request.getAudioBytes();
        FlashRecognitionRequest recognitionRequest = FlashRecognitionRequest.initialize();
        recognitionRequest.setEngineType("16k_zh");
        recognitionRequest.setFirstChannelOnly(1);
        recognitionRequest.setVoiceFormat(request.getOptions().getFormatOrDefault(request.guessAudioFormat()));
        recognitionRequest.setSpeakerDiarization(0);
        recognitionRequest.setFilterDirty(0);
        recognitionRequest.setFilterModal(0);
        recognitionRequest.setFilterPunc(0);
        recognitionRequest.setConvertNumMode(1);
        recognitionRequest.setWordInfo(1);
        FlashRecognitionResponse flashRecognitionResponse = recognizer.recognize(recognitionRequest, data);
        List<FlashRecognitionResponse.FlashRecognitionResult> flashResult = flashRecognitionResponse.getFlashResult();
        if (flashResult != null && !flashResult.isEmpty()) {
            FlashRecognitionResponse.FlashRecognitionResult flashRecognitionResult = flashResult.get(0);
            if (flashRecognitionResult != null) {
                response.addResult(flashRecognitionResult.getText());
            }
        } else {
            response.setSuccess(false);
            response.setMessage(flashRecognitionResponse.getMessage());
        }
        return response;
    }


}
