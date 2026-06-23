package com.agentsflex.audio.tecent;

import com.agentsflex.audio.tencent.*;
import com.agentsflex.core.audio.stt.SpeechToTextModel;
import com.agentsflex.core.audio.stt.SpeechToTextRequest;
import com.agentsflex.core.audio.stt.SpeechToTextResponse;
import com.agentsflex.core.audio.tts.*;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TencentAudioTest {

    @Test
    public void testTextToSpeech() {
        TencentTextToSpeechConfig config = new TencentTextToSpeechConfig();
        config.setAppId(System.getenv("TENCENT_APP_ID"));
        config.setSecretId(System.getenv("TENCENT_SECRET_ID"));
        config.setSecretKey(System.getenv("TENCENT_SECRET_KEY"));

        TextToSpeechModel model = new TencentTextToSpeechModel(config);
        TextToSpeechResponse helloWorld = model.tts(new TextToSpeechRequest("hello world"));

        System.out.println(helloWorld);
    }

    @Test
    public void testTextToSpeechStreaming() {
        TencentTextToSpeechConfig config = new TencentTextToSpeechConfig();
        config.setAppId(System.getenv("TENCENT_APP_ID"));
        config.setSecretId(System.getenv("TENCENT_SECRET_ID"));
        config.setSecretKey(System.getenv("TENCENT_SECRET_KEY"));

        try (StreamingTextToSpeechModel model = new TencentStreamingTextToSpeechModel(config)) {
            model.init(new TextToSpeechOptions());
            model.addListener(new StreamingTextToSpeechListener() {
                @Override
                public void onStart() {
                    System.out.println("start");
                }

                @Override
                public void onReceived(byte[] bytes) {
                    System.out.println("received: " + bytes);
                }

                @Override
                public void onError(Throwable throwable) {
                    System.out.println("error");
                }

                @Override
                public void onComplete() {
                    System.out.println("complete");
                }
            });

            for (int i = 0; i < 10; i++) {
                model.sendText("hello world");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }


    @Test
    public void testSpeechToText() {
        TencentSpeechToTextConfig config = new TencentSpeechToTextConfig();
        config.setAppId(System.getenv("TENCENT_APP_ID"));
        config.setSecretId(System.getenv("TENCENT_SECRET_ID"));
        config.setSecretKey(System.getenv("TENCENT_SECRET_KEY"));

        SpeechToTextRequest request = new SpeechToTextRequest();
        request.setAudioFile(new File("/Users/michael/Desktop/jenny 音色.wav"));

        SpeechToTextModel model = new TencentSpeechToTextModel(config);
        SpeechToTextResponse response = model.stt(request);

        System.out.println(response);
    }
}
