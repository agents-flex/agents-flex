package com.agentsflex.image.test;

import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.image.qianfan.QianfanImageModel;
import com.agentsflex.image.qianfan.QianfanImageModelConfig;
import org.junit.Test;

public class QianfanImageModelTest {

    @Test
    public void testGenerate() throws InterruptedException {
        QianfanImageModelConfig config = new QianfanImageModelConfig();
        config.setApiKey("*************");
        QianfanImageModel imageModel = new QianfanImageModel(config);

        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("画一个职场性感女生图片");
        ImageResponse generate = imageModel.generate(request);
        System.out.println(generate);
    }
}
