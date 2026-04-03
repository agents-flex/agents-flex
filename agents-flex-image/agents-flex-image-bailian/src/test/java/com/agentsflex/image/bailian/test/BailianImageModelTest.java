package com.agentsflex.image.bailian.test;

import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageModel;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.image.bailian.BailianImageModel;
import com.agentsflex.image.bailian.BailianImageModelConfig;
import org.junit.Test;

import java.io.File;

public class BailianImageModelTest {

    @Test
    public void testGenImage() {
        BailianImageModelConfig config = new BailianImageModelConfig();
        config.setApiKey("sk-***");

        ImageModel imageModel = new BailianImageModel(config);

        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A cute little tiger standing in the high-speed train");
        request.setSizeString("1k");
        ImageResponse generate = imageModel.generate(request);
        if (generate != null && generate.getImages() != null) {
            int index = 0;
            for (Image image : generate.getImages()) {
                image.writeToFile(new File("/Users/michael/Desktop/test/image" + (index++) + ".jpg"));
            }
        }

        System.out.println(generate);
    }
}
