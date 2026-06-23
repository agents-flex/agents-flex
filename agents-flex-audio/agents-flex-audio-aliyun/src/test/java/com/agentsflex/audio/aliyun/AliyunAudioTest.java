package com.agentsflex.audio.aliyun;

import com.agentsflex.core.audio.stt.SpeechToTextModel;
import com.agentsflex.core.audio.stt.SpeechToTextRequest;
import com.agentsflex.core.audio.stt.SpeechToTextResponse;
import com.agentsflex.core.audio.tts.*;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class AliyunAudioTest {

    @Test
    public void testTextToSpeech() {
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));

        TextToSpeechModel model = new AliyunTextToSpeechModel(config);
        TextToSpeechResponse helloWorld = model.tts(new TextToSpeechRequest("hello world"));

        System.out.println(helloWorld);
    }

    @Test
    public void testTextToSpeechStreaming() {
        AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));


        try (StreamingTextToSpeechModel model = new AliyunStreamingTextToSpeechModel(config)) {
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
        AliyunSpeechToTextConfig config = new AliyunSpeechToTextConfig();
        config.setAppKey(System.getenv("ALIYUN_NLS_APP_KEY"));
        config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));
        config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));


        SpeechToTextRequest request = new SpeechToTextRequest();
        request.setAudioFile(new File("/Users/michael/Desktop/jenny 音色.wav"));

        SpeechToTextModel model = new AliyunSpeechToTextModel(config);
        SpeechToTextResponse response = model.stt(request);

        System.out.println(response);
    }
}
