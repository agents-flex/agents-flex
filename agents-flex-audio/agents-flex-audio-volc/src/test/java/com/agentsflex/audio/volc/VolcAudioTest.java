package com.agentsflex.audio.volc;

import com.agentsflex.core.audio.stt.SpeechToTextModel;
import com.agentsflex.core.audio.stt.SpeechToTextRequest;
import com.agentsflex.core.audio.stt.SpeechToTextResponse;
import com.agentsflex.core.audio.tts.*;
import org.junit.Test;

import java.io.File;

public class VolcAudioTest {

    @Test
    public void testTextToSpeech() {
        VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        TextToSpeechModel model = new VolcTextToSpeechModel(config);
        TextToSpeechResponse helloWorld = model.tts(new TextToSpeechRequest("hello world"));

        System.out.println(helloWorld);
    }

    @Test
    public void testTextToSpeechStreaming() throws InterruptedException {
        VolcTextToSpeechConfig config = new VolcTextToSpeechConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        try (StreamingTextToSpeechModel model = new VolcStreamingTextToSpeechModel(config)) {
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

            for (int i = 0; i < 100; i++) {
                model.sendText("hello world");
                Thread.sleep(1000L);
            }

//            Thread.sleep(1000L * 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

//        Thread.sleep(1000L * 10);

    }


    @Test
    public void testSpeechToText() {
        VolcSpeechToTextConfig config = new VolcSpeechToTextConfig();
        config.setApiKey(System.getenv("VOLC_API_KEY"));

        SpeechToTextRequest request = new SpeechToTextRequest();
        request.setAudioFile(new File("/Users/michael/Desktop/jenny 音色.wav"));

        SpeechToTextModel model = new VolcSpeechToTextModel(config);
        SpeechToTextResponse response = model.stt(request);

        System.out.println(response);
    }
}
