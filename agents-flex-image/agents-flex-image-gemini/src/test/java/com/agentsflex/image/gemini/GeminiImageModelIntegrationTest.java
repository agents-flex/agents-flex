package com.agentsflex.image.gemini;

import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.ImageResponse;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class GeminiImageModelIntegrationTest {
    @Test
    public void shouldGenerateImageWithGeminiApi() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assume.assumeTrue(apiKey != null && !apiKey.trim().isEmpty());

        GeminiImageModelConfig config = new GeminiImageModelConfig();
        config.setApiKey(apiKey);
        String model = System.getenv("GEMINI_IMAGE_MODEL");
        if (model != null && !model.trim().isEmpty()) {
            config.setModel(model.trim());
        }

        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("Create a clean geometric poster with a red circle, a blue square, and a white background. No text.");
        request.setResolution("1K");
        request.addOption(GeminiImageModel.OPTION_ASPECT_RATIO, "1:1");

        ImageResponse response = new GeminiImageModel(config).generate(request);

        assertFalse(response.getErrorMessage(), response.isError());
        assertNotNull(response.getImage());
        response.getImage().writeToFile(new File("target/gemini-integration.png"));
    }
}
