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


    private SpeechSynthesizer getSpeechSynthesizer(TextToSpeechRequest request, TextToSpeechResponse response) throws Exception {
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

            }

            @Override
            public void onMessage(ByteBuffer message) {
                byte[] bytesArray = new byte[message.remaining()];
                message.get(bytesArray, 0, bytesArray.length);
                response.addResult(bytesArray);
            }
        });

        synthesizer.setAppKey(config.getAppKey());

        //设置返回音频的编码格式。
        synthesizer.setFormat(OutputFormatEnum.valueOf(request.getOptions().getFormat().toUpperCase()));

        //设置返回音频的采样率。
//        synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
        synthesizer.setSampleRate(request.getOptions().getSampleRate());
        //发音人。注意Java SDK不支持调用超高清场景对应的发音人（例如"zhiqi"），如需调用请使用restfulAPI方式。
        synthesizer.setVoice(request.getOptions().getVoice());

        //音量，范围是0~100，可选，默认50。
        synthesizer.setVolume(request.getOptions().getVolume());
        //语调，范围是-500~500，可选，默认是0。
        synthesizer.setPitchRate(0);

        //语速，范围是-500~500，默认是0。
        synthesizer.setSpeechRate(request.getOptions().getSpeed().intValue());


        return synthesizer;
    }
}
