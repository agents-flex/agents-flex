package com.agentsflex.image.gitee;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Live tests for Gitee AI. Set GITEE_AI_API_KEY explicitly because these calls may incur charges. */
public class GiteeImageModelIntegrationTest {
    private GiteeImageModel model;

    @Before
    public void setUp() {
        String apiKey = System.getProperty("gitee.ai.api-key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = System.getenv("GITEE_AI_API_KEY");
        }
        Assume.assumeTrue("GITEE_AI_API_KEY or -Dgitee.ai.api-key is required for live tests",
            apiKey != null && !apiKey.trim().isEmpty());

        GiteeImageModelConfig config = new GiteeImageModelConfig();
        config.setApiKey(apiKey);
        config.setModel(GiteeImageModels.FLUX_1_SCHNELL);
        model = new GiteeImageModel(config);
    }

    @Test
    public void shouldGenerateImageUrl() {
        GenerateImageRequest request = textToImageRequest();
        request.setResponseFormat("url");

        ImageResponse response = model.generate(request);

        assertSuccessful(response, 1);
        assertTrue("Gitee AI did not return an image URL",
            hasText(response.getImage().getUrl()));
    }

    @Test
    public void shouldGenerateBase64Image() {
        GenerateImageRequest request = textToImageRequest();
        request.setResponseFormat("b64_json");

        ImageResponse response = model.generate(request);

        assertSuccessful(response, 1);
        assertTrue("Gitee AI did not return Base64 image data",
            hasText(response.getImage().getB64Json()));
    }

    @Test
    public void shouldHandleMultipleImageRequest() {
        GenerateImageRequest request = textToImageRequest();
        request.setN(2);
        request.setResponseFormat("url");

        ImageResponse response = model.generate(request);

        assertSuccessful(response);
        assertTrue("Gitee AI returned more images than requested",
            response.getImages().size() <= request.getN());
        for (Image image : response.getImages()) {
            assertTrue("Gitee AI returned an image without a URL", hasText(image.getUrl()));
        }
    }

    @Test
    public void shouldEditGeneratedImage() {
        GenerateImageRequest sourceRequest = textToImageRequest();
        sourceRequest.setResponseFormat("url");
        ImageResponse sourceResponse = model.generate(sourceRequest);
        assertSuccessful(sourceResponse, 1);
        assertTrue("Gitee AI did not return a source image URL",
            hasText(sourceResponse.getImage().getUrl()));

        byte[] sourceBytes = new HttpClient().getBytes(sourceResponse.getImage().getUrl());
        assertNotNull("Could not download the generated source image", sourceBytes);
        assertTrue("Downloaded source image is empty", sourceBytes.length > 0);

        GenerateImageRequest editRequest = new GenerateImageRequest();
        editRequest.setModel(GiteeImageModels.QWEN_IMAGE_EDIT);
        editRequest.setPrompt("把背景改成日落时的海边，保留红色纸飞机的形状和位置");
        editRequest.setN(1);
        editRequest.setResponseFormat("url");
        editRequest.addInputImage(Image.ofBytes(sourceBytes, "image/png"));

        ImageResponse editResponse = model.generate(editRequest);

        assertSuccessful(editResponse, 1);
        assertTrue("Gitee AI did not return an edited image URL",
            hasText(editResponse.getImage().getUrl()));
    }

    private GenerateImageRequest textToImageRequest() {
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("一架红色纸飞机放在白色桌面上，产品摄影，自然光，画面简洁");
        request.setSize(1024, 1024);
        request.setN(1);
        return request;
    }

    private void assertSuccessful(ImageResponse response, int expectedImageCount) {
        assertSuccessful(response);
        assertEquals("Unexpected number of generated images: " + response,
            expectedImageCount, response.getImages().size());
    }

    private void assertSuccessful(ImageResponse response) {
        assertNotNull("Gitee AI returned no response", response);
        assertFalse("Gitee AI image request failed: " + response, response.isError());
        assertFalse("Gitee AI returned no images: " + response, response.getImages().isEmpty());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
