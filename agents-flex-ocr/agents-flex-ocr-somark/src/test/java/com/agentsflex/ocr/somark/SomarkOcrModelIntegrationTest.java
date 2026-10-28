/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.ocr.somark;

import com.agentsflex.core.model.ocr.OcrBlock;
import com.agentsflex.core.model.ocr.OcrCoordinateOrigin;
import com.agentsflex.core.model.ocr.OcrDocument;
import com.agentsflex.core.model.ocr.OcrPage;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SoMark 真实服务集成测试。只有显式设置 {@code SOMARK_OCR_API_KEY} 时才消耗配额执行。
 */
public class SomarkOcrModelIntegrationTest {
    private File sampleImage;

    /**
     * 创建包含稳定英文文本的临时图片。
     */
    @Before
    public void createSampleImage() throws Exception {
        sampleImage = Files.createTempFile("agents-flex-somark-ocr-", ".png").toFile();
        BufferedImage image = new BufferedImage(1200, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
            graphics.drawString("AgentsFlex SoMark OCR Test", 55, 140);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 48));
            graphics.drawString("Async Task 2026", 55, 235);
        } finally {
            graphics.dispose();
        }
        assertTrue(ImageIO.write(image, "png", sampleImage));
    }

    /**
     * 删除真实测试使用的临时图片。
     */
    @After
    public void deleteSampleImage() throws Exception {
        if (sampleImage != null) Files.deleteIfExists(sampleImage.toPath());
    }

    /**
     * 验证异步提交、轮询、状态映射和 Markdown 解析全链路。
     */
    @Test
    public void shouldRecognizeImageThroughRealSomarkApi() {
        String apiKey = System.getenv("SOMARK_OCR_API_KEY");
        Assume.assumeTrue("未设置 SOMARK_OCR_API_KEY，跳过真实 SoMark OCR 测试",
            apiKey != null && !apiKey.trim().isEmpty());

        SomarkOcrConfig config = new SomarkOcrConfig();
        config.setApiKey(apiKey);
        SomarkOcrModel model = new SomarkOcrModel(config);

        OcrResponse response = model.recognizeAndWait(
            OcrRequest.ofFile(sampleImage), 10 * 60_000L, 3_000L);

        assertNotNull("供应商不应返回空响应", response);
        assertFalse("真实 SoMark OCR 请求失败: " + response.getErrorCode() + " / " + response.getErrorMessage(),
            response.isError());
        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertNotNull("成功响应必须保留任务编号", response.getTaskId());
        assertTrue("默认输出应包含 Markdown", response.getMarkdown() != null
            && !response.getMarkdown().trim().isEmpty());
        assertStructuredDocument(response);
    }

    /**
     * 验证同步接口的文件上传、结果映射和 Markdown 解析全链路。
     */
    @Test
    public void shouldRecognizeImageSynchronouslyThroughRealSomarkApi() {
        String apiKey = System.getenv("SOMARK_OCR_API_KEY");
        Assume.assumeTrue("未设置 SOMARK_OCR_API_KEY，跳过真实 SoMark OCR 测试",
            apiKey != null && !apiKey.trim().isEmpty());

        SomarkOcrConfig config = new SomarkOcrConfig();
        config.setApiKey(apiKey);
        OcrResponse response = new SomarkOcrModel(config).recognizeSync(OcrRequest.ofFile(sampleImage));

        assertNotNull("供应商不应返回空响应", response);
        assertFalse("真实 SoMark 同步 OCR 请求失败: " + response.getErrorCode() + " / "
            + response.getErrorMessage(), response.isError());
        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertNotNull("成功响应必须保留任务编号", response.getTaskId());
        assertTrue("默认输出应包含 Markdown", response.getMarkdown() != null
            && !response.getMarkdown().trim().isEmpty());
        assertStructuredDocument(response);
    }

    private static void assertStructuredDocument(OcrResponse response) {
        OcrDocument document = response.getDocument();
        assertNotNull("成功响应应包含结构化文档", document);
        assertFalse("结构化文档应至少包含一页", document.getPages().isEmpty());
        OcrPage page = document.getPages().get(0);
        assertNotNull("页面应包含尺寸", page.getSize());
        assertTrue("页面宽度应大于零", page.getSize().getWidth() > 0);
        assertTrue("页面高度应大于零", page.getSize().getHeight() > 0);
        assertEquals(OcrCoordinateOrigin.UNKNOWN, page.getCoordinateOrigin());
        assertFalse("页面应至少包含一个内容块", page.getBlocks().isEmpty());
        boolean hasBoundingBox = false;
        for (OcrBlock block : page.getBlocks()) {
            if (block.getBoundingBox() != null) {
                hasBoundingBox = true;
                break;
            }
        }
        assertTrue("至少一个内容块应包含 bbox", hasBoundingBox);
    }
}
