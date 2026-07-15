package com.agentsflex.image.aliyun;

import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Live tests for Alibaba Model Studio. Set DASHSCOPE_API_KEY because these calls may incur charges. */
public class AliyunImageModelIntegrationTest {
    private static final File OUTPUT_DIR = new File("target/aliyun-images");

    private AliyunImageModel model;

    @Before
    public void setUp() {
        String apiKey = System.getProperty("dashscope.api-key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        Assume.assumeTrue("DASHSCOPE_API_KEY or -Ddashscope.api-key is required for live tests",
            apiKey != null && !apiKey.trim().isEmpty());

        AliyunImageModelConfig config = new AliyunImageModelConfig();
        config.setApiKey(apiKey);
        config.setPollIntervalMillis(2_000L);
        config.setTimeoutMillis(10 * 60_000L);
        model = new AliyunImageModel(config);
    }

    @Test
    public void shouldGenerateQwenImageAsynchronously() {
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel(AliyunImageModels.QWEN_IMAGE_PLUS);
        request.setPrompt("春日城市咖啡节海报，中文标题为春日咖啡，活版印刷风格，绿色和红色配色");
        request.setNegativePrompt("文字模糊，低清晰度，水印");
        request.setSize(1664, 928);
        request.setN(1);
        request.setPromptExtend(true);
        request.setWatermark(false);

        ImageResponse response = model.generate(request);

        assertSuccessful(response, 1);
        saveImages(response, "qwen-plus-text-to-image");
    }

    @Test
    public void shouldGenerateWan27ImageSynchronously() {
        GenerateImageRequest request = wanTextToImageRequest();

        ImageResponse response = model.generate(request);

        assertSuccessful(response, 1);
        saveImages(response, "wan27-text-to-image");
    }

    @Test
    public void shouldGenerateWan27ImageGroup() {
        GenerateImageRequest request = wanTextToImageRequest();
        request.setPrompt("同一架红色纸飞机在现代美术馆中的两幅连续广告画面，统一视觉风格");
        request.setN(2);
        request.setSequentialGeneration(true);

        ImageResponse response = model.generate(request);

        assertSuccessful(response, 2);
        saveImages(response, "wan27-image-group");
    }

    @Test
    public void shouldEditGeneratedImage() {
        ImageResponse sourceResponse = model.generate(wanTextToImageRequest());
        assertSuccessful(sourceResponse, 1);
        saveImages(sourceResponse, "edit-source");

        GenerateImageRequest editRequest = new GenerateImageRequest();
        editRequest.setModel(AliyunImageModels.QWEN_IMAGE_EDIT_PLUS);
        editRequest.setPrompt("把背景改成日落时的海边，保留红色纸飞机的形状和位置");
        editRequest.addInputImage(sourceResponse.getImage());
        editRequest.setSize(1024, 1024);
        editRequest.setN(1);
        editRequest.setWatermark(false);

        ImageResponse editResponse = model.generate(editRequest);

        assertSuccessful(editResponse, 1);
        saveImages(editResponse, "qwen-edit");
    }

    private GenerateImageRequest wanTextToImageRequest() {
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel(AliyunImageModels.WAN_2_7_IMAGE);
        request.setPrompt("一架红色纸飞机放在白色桌面上，产品摄影，自然光，画面简洁");
        request.setResolution("1K");
        request.setN(1);
        request.setWatermark(false);
        return request;
    }

    private void saveImages(ImageResponse response, String name) {
        for (int i = 0; i < response.getImages().size(); i++) {
            File output = new File(OUTPUT_DIR, name + "-" + (i + 1) + ".png");
            response.getImages().get(i).writeToFile(output);
            assertTrue("Generated image was not downloaded: " + output,
                output.isFile() && output.length() > 0);
            System.out.println("Aliyun generated image: " + output.getAbsolutePath());
        }
    }

    private void assertSuccessful(ImageResponse response, int expectedImageCount) {
        assertSuccessful(response);
        assertEquals("Unexpected number of generated images: " + response,
            expectedImageCount, response.getImages().size());
    }

    private void assertSuccessful(ImageResponse response) {
        assertNotNull("Aliyun returned no response", response);
        assertFalse("Aliyun image request failed: " + response, response.isError());
        assertFalse("Aliyun returned no images: " + response, response.getImages().isEmpty());
        for (Image image : response.getImages()) {
            assertTrue("Aliyun returned an image without a URL",
                image != null && image.getUrl() != null && !image.getUrl().trim().isEmpty());
        }
    }
}
