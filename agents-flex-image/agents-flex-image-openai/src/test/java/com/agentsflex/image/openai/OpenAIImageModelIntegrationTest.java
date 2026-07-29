package com.agentsflex.image.openai;

import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.ImageResponse;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class OpenAIImageModelIntegrationTest {
    @Test
    public void shouldGenerateImageWithOpenAIApi() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assume.assumeTrue(apiKey != null && !apiKey.trim().isEmpty());

        OpenAIImageModelConfig config = new OpenAIImageModelConfig();
        config.setApiKey(apiKey);
        String model = System.getenv("OPENAI_IMAGE_MODEL");
        if (model != null && !model.trim().isEmpty()) {
            config.setModel(model.trim());
        }

        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("Create a clean geometric poster with a red circle, a blue square, and a white background. No text.");
        request.setResolution("1024x1024");
        request.setQuality("low");
        request.setOutputFormat("png");
        request.setN(1);

        ImageResponse response = new OpenAIImageModel(config).generate(request);

        assertFalse(response.getErrorMessage(), response.isError());
        assertNotNull(response.getImage());
        response.getImage().writeToFile(new File("target/openai-integration.png"));
    }
}
