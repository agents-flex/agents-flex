//package com.agentsflex.image.volcengine;
//
//import com.agentsflex.core.model.image.GenerateImageRequest;
//import com.agentsflex.core.model.image.Image;
//import com.agentsflex.core.model.image.ImageResponse;
//import org.junit.Assume;
//import org.junit.Before;
//import org.junit.Test;
//
//import java.io.File;
//
//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.assertFalse;
//import static org.junit.Assert.assertNotNull;
//import static org.junit.Assert.assertTrue;
//
///** Live tests for Volcengine Ark. Set ARK_API_KEY because these calls may incur charges. */
//public class VolcengineImageModelIntegrationTest {
//    private static final File OUTPUT_DIR = new File("target/volcengine-images");
//
//    private VolcengineImageModel model;
//
//    @Before
//    public void setUp() {
//        String apiKey = System.getProperty("ark.api-key");
//        if (apiKey == null || apiKey.trim().isEmpty()) {
//            apiKey = System.getenv("ARK_API_KEY");
//        }
//        Assume.assumeTrue("ARK_API_KEY or -Dark.api-key is required for live tests",
//            apiKey != null && !apiKey.trim().isEmpty());
//
//        VolcengineImageModelConfig config = new VolcengineImageModelConfig();
//        config.setApiKey(apiKey);
//        config.setModel(VolcengineImageModels.SEEDREAM_5_0_LITE);
//        model = new VolcengineImageModel(config);
//    }
//
//    @Test
//    public void shouldGenerateImageUrl() {
//        GenerateImageRequest request = textToImageRequest();
//        request.setResponseFormat("url");
//
//        ImageResponse response = model.generate(request);
//
//        assertSuccessful(response, 1);
//        assertTrue("Volcengine did not return an image URL",
//            hasText(response.getImage().getUrl()));
//        saveImages(response, "seedream-text-to-image");
//    }
//
//    @Test
//    public void shouldGenerateBase64Png() {
//        GenerateImageRequest request = textToImageRequest();
//        request.setResponseFormat("b64_json");
//
//        ImageResponse response = model.generate(request);
//
//        assertSuccessful(response, 1);
//        assertNotNull("Volcengine did not return decoded image bytes",
//            response.getImage().getBytes());
//        assertTrue("Volcengine returned empty image bytes",
//            response.getImage().getBytes().length > 0);
//        saveImages(response, "seedream-base64");
//    }
//
//    @Test
//    public void shouldGenerateSequentialImageGroup() {
//        GenerateImageRequest request = textToImageRequest();
//        request.setPrompt("同一架红色纸飞机在现代美术馆中的两幅连续广告画面，统一视觉风格");
//        request.setResponseFormat("url");
//        request.setSequentialGeneration(true);
//        request.setMaxImages(2);
//
//        ImageResponse response = model.generate(request);
//
//        assertSuccessful(response, 2);
//        saveImages(response, "seedream-image-group");
//    }
//
//    @Test
//    public void shouldEditGeneratedImage() {
//        GenerateImageRequest sourceRequest = textToImageRequest();
//        sourceRequest.setResponseFormat("url");
//        ImageResponse sourceResponse = model.generate(sourceRequest);
//        assertSuccessful(sourceResponse, 1);
//        saveImages(sourceResponse, "edit-source");
//
//        GenerateImageRequest editRequest = new GenerateImageRequest();
//        editRequest.setPrompt("把背景改成日落时的海边，保留红色纸飞机的形状和位置");
//        editRequest.addInputImage(sourceResponse.getImage());
//        editRequest.setResolution("2k");
//        editRequest.setResponseFormat("url");
//        editRequest.setOutputFormat("png");
//        editRequest.setWatermark(false);
//
//        ImageResponse editResponse = model.generate(editRequest);
//
//        assertSuccessful(editResponse, 1);
//        saveImages(editResponse, "seedream-edit");
//    }
//
//    private GenerateImageRequest textToImageRequest() {
//        GenerateImageRequest request = new GenerateImageRequest();
//        request.setPrompt("一架红色纸飞机放在白色桌面上，产品摄影，自然光，画面简洁");
//        request.setResolution("2k");
//        request.setOutputFormat("png");
//        request.setWatermark(false);
//        request.setPromptExtend(true);
//        return request;
//    }
//
//    private void saveImages(ImageResponse response, String name) {
//        for (int i = 0; i < response.getImages().size(); i++) {
//            File output = new File(OUTPUT_DIR, name + "-" + (i + 1) + ".png");
//            response.getImages().get(i).writeToFile(output);
//            assertTrue("Generated image was not downloaded: " + output,
//                output.isFile() && output.length() > 0);
//            System.out.println("Volcengine generated image: " + output.getAbsolutePath());
//        }
//    }
//
//    private void assertSuccessful(ImageResponse response, int expectedImageCount) {
//        assertSuccessful(response);
//        assertEquals("Unexpected number of generated images: " + response,
//            expectedImageCount, response.getImages().size());
//    }
//
//    private void assertSuccessful(ImageResponse response) {
//        assertNotNull("Volcengine returned no response", response);
//        assertFalse("Volcengine image request failed: " + response, response.isError());
//        assertFalse("Volcengine returned no images: " + response, response.getImages().isEmpty());
//    }
//
//    private boolean hasText(String value) {
//        return value != null && !value.trim().isEmpty();
//    }
//}
