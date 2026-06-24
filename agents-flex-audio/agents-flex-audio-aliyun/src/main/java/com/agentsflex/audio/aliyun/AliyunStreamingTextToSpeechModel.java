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

import com.agentsflex.core.audio.tts.*;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.FlowingSpeechSynthesizerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AliyunStreamingTextToSpeechModel implements StreamingTextToSpeechModel {

    private static final Logger log = LoggerFactory.getLogger(AliyunStreamingTextToSpeechModel.class);
    private final AliyunTextToSpeechConfig config;

    public AliyunStreamingTextToSpeechModel(AliyunTextToSpeechConfig config) {
        this.config = config;
    }

    private final List<StreamingTextToSpeechListener> listeners = new ArrayList<>();

    private FlowingSpeechSynthesizer flowingSpeechSynthesizer;

    public AliyunTextToSpeechConfig getConfig() {
        return config;
    }

    public List<StreamingTextToSpeechListener> getListeners() {
        return listeners;
    }

    public FlowingSpeechSynthesizer getFlowingSpeechSynthesizer() {
        return flowingSpeechSynthesizer;
    }

    public void setFlowingSpeechSynthesizer(FlowingSpeechSynthesizer flowingSpeechSynthesizer) {
        this.flowingSpeechSynthesizer = flowingSpeechSynthesizer;
    }

    @Override
    public void addListener(StreamingTextToSpeechListener listener) {
        listeners.add(listener);
    }

    @Override
    public void init(TextToSpeechOptions options) {
        if (flowingSpeechSynthesizer == null) {
            try {
                flowingSpeechSynthesizer = getFlowingSpeechSynthesizer(options);
                flowingSpeechSynthesizer.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void sendText(String text) {
        if (StringUtil.hasText(text)) {
            flowingSpeechSynthesizer.send(text);
        } else {
            flowingSpeechSynthesizer.getConnection().sendPing();
        }
    }


    @Override
    public void close() throws IOException {
        if (flowingSpeechSynthesizer != null) {
            try {
                flowingSpeechSynthesizer.stop();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                flowingSpeechSynthesizer.close();
            }
        }
    }


    private FlowingSpeechSynthesizer getFlowingSpeechSynthesizer(TextToSpeechOptions options) throws Exception {
        NlsClient nlsClient = new NlsClient(config.createToken());
        FlowingSpeechSynthesizer synthesizer = new FlowingSpeechSynthesizer(nlsClient, new FlowingSpeechSynthesizerListener() {
            @Override
            public void onSynthesisStart(FlowingSpeechSynthesizerResponse response) {
                //log.info("【阿里云nls】流入语音合成开始 ---> name: {}，status: {}", response.getName(), response.getStatus());
                for (StreamingTextToSpeechListener listener : listeners) {
                    try {
                        listener.onStart();
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                    }
                }
            }

            @Override
            public void onSentenceBegin(FlowingSpeechSynthesizerResponse response) {
                //log.info("【阿里云nls】服务端检测到了一句话的开始 ---> name: {}，status: {}", response.getName(), response.getStatus());
            }

            @Override
            public void onAudioData(ByteBuffer message) {
                //收到语音合成的语音二进制数据
                byte[] bytesArray = new byte[message.remaining()];
                message.get(bytesArray, 0, bytesArray.length);
                for (StreamingTextToSpeechListener listener : listeners) {
                    try {
                        listener.onReceived(bytesArray);
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                    }
                }
            }

            @Override
            public void onSentenceEnd(FlowingSpeechSynthesizerResponse response) {
                //log.info("【阿里云nls】服务端检测到了一句话的结束 ---> name: {}，status: {}，subtitles: {}", response.getName(), response.getStatus(), response.getObject("subtitles"));
            }

            @Override
            public void onSynthesisComplete(FlowingSpeechSynthesizerResponse response) {
                //log.info("【阿里云nls】流入语音合成结束 ---> name: {}，status: {}", response.getName(), response.getStatus());
                for (StreamingTextToSpeechListener listener : listeners) {
                    try {
                        listener.onComplete();
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                    }
                }
            }

            @Override
            public void onFail(FlowingSpeechSynthesizerResponse response) {
                for (StreamingTextToSpeechListener listener : listeners) {
                    try {
                        listener.onError(new RuntimeException(JSON.toJSONString(response)));
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                    }
                }
            }


            @Override
            public void onSentenceSynthesis(FlowingSpeechSynthesizerResponse response) {
                //log.info("【阿里云nls】收到语音合成的增量音频时间戳 ---> name: {}，status: {}，subtitles: {}", response.getName(), response.getStatus(), response.getObject("subtitles"));
            }
        });

        synthesizer.setAppKey(config.getAppKey());

        //设置返回音频的编码格式。
        synthesizer.setFormat(OutputFormatEnum.valueOf(options.getFormatOrDefault("mp3").toUpperCase()));

        //设置返回音频的采样率。
//        synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
        synthesizer.setSampleRate(options.getSampleRateOrDefault(16000));
        //发音人。注意Java SDK不支持调用超高清场景对应的发音人（例如"zhiqi"），如需调用请使用restfulAPI方式。
        synthesizer.setVoice(options.getVoice());

        //音量，范围是0~100，可选，默认50。
        synthesizer.setVolume(options.getVolumeOrDefault(50));
        //语调，范围是-500~500，可选，默认是0。
        synthesizer.setPitchRate(0);

        //语速，范围是-500~500，默认是0。
        synthesizer.setSpeechRate(options.getSpeedOrDefault(0).intValue());


        return synthesizer;
    }


}
