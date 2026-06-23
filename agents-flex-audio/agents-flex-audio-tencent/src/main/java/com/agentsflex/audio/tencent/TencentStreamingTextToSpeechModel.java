package com.agentsflex.audio.tencent;

import com.agentsflex.core.audio.tts.StreamingTextToSpeechListener;
import com.agentsflex.core.audio.tts.StreamingTextToSpeechModel;
import com.agentsflex.core.audio.tts.TextToSpeechOptions;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.tencent.core.ws.Credential;
import com.tencent.core.ws.SpeechClient;
import com.tencent.ttsv2.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TencentStreamingTextToSpeechModel implements StreamingTextToSpeechModel {

    private static final Logger log = LoggerFactory.getLogger(TencentStreamingTextToSpeechModel.class);
    static SpeechClient speechClient = new SpeechClient(TtsConstant.DEFAULT_TTS_V2_REQ_URL);
    private final TencentTextToSpeechConfig config;

    public TencentStreamingTextToSpeechModel(TencentTextToSpeechConfig config) {
        this.config = config;
    }

    private final List<StreamingTextToSpeechListener> listeners = new ArrayList<>();

    private FlowingSpeechSynthesizer flowingSpeechSynthesizer;

    public TencentTextToSpeechConfig getConfig() {
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
            flowingSpeechSynthesizer.process(text);
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
        Credential credential = this.config.toCredential();

        FlowingSpeechSynthesizerRequest request = new FlowingSpeechSynthesizerRequest();
        request.setVolume((float) options.getVolumeOrDefault(0));
        request.setSpeed(options.getSpeedOrDefault(0f).floatValue());
        request.setCodec(options.getFormat());
        request.setSampleRate(options.getSampleRate());
        request.setVoiceType(301032);
        request.setEnableSubtitle(true);
        request.setEmotionCategory("neutral");
        request.setEmotionIntensity(100);
        request.setSessionId(UUID.randomUUID().toString());//sessionId，需要保持全局唯一（推荐使用 uuid），遇到问题需要提供该值方便服务端排查

        return new FlowingSpeechSynthesizer(speechClient, credential, request, new FlowingSpeechSynthesizerListener() {

            @Override
            public void onSynthesisStart(SpeechSynthesizerResponse response) {
                for (StreamingTextToSpeechListener listener : listeners) {
                    try {
                        listener.onStart();
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                    }
                }
            }

            @Override
            public void onSynthesisEnd(SpeechSynthesizerResponse response) {
                for (StreamingTextToSpeechListener listener : listeners) {
                    try {
                        listener.onComplete();
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                    }
                }
            }

            @Override
            public void onAudioResult(ByteBuffer data) {
                byte[] bytesArray = new byte[data.remaining()];
                data.get(bytesArray, 0, bytesArray.length);
                for (StreamingTextToSpeechListener listener : listeners) {
                    try {
                        listener.onReceived(bytesArray);
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                    }
                }
            }

            @Override
            public void onTextResult(SpeechSynthesizerResponse response) {

            }

            /**
             * 请求失败
             *
             * @param response
             */
            @Override
            public void onSynthesisFail(SpeechSynthesizerResponse response) {
                for (StreamingTextToSpeechListener listener : listeners) {
                    try {
                        listener.onError(new RuntimeException(JSON.toJSONString(response)));
                    } catch (Exception e) {
                        log.error(e.toString(), e);
                    }
                }
            }
        });
    }


}
