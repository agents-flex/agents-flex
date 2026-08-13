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
package com.agentsflex.ocr.baidu;

import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResource;
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
 * 百度 PaddleOCR-VL 真实服务集成测试。
 *
 * <p>测试会以 Base64 表单真实提交本地图片并轮询结果。只有显式设置
 * {@code BAIDU_OCR_ACCESS_TOKEN} 时才执行，凭证不会写入源码或测试输出。</p>
 */
public class BaiduOcrModelIntegrationTest {
    /**
     * 百度文档解析任务最长等待三分钟。
     */
    private static final long TIMEOUT_MILLIS = 180_000L;
    /**
     * 使用五秒查询间隔，与供应商配置默认值保持一致。
     */
    private static final long POLL_INTERVAL_MILLIS = 5_000L;

    private File sampleImage;

    /**
     * 动态生成包含稳定英文文本的 PNG 测试输入。
     */
    @Before
    public void createSampleImage() throws Exception {
        sampleImage = Files.createTempFile("agents-flex-baidu-ocr-", ".png").toFile();
        BufferedImage image = new BufferedImage(1200, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
            graphics.drawString("AgentsFlex Baidu OCR Test", 55, 140);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 48));
            graphics.drawString("PaddleOCR Task 2026", 55, 235);
        } finally {
            graphics.dispose();
        }
        assertTrue("临时 OCR 图片写入失败", ImageIO.write(image, "png", sampleImage));
    }

    /**
     * 删除真实测试使用的临时图片。
     */
    @After
    public void deleteSampleImage() throws Exception {
        if (sampleImage != null) Files.deleteIfExists(sampleImage.toPath());
    }

    /**
     * 真实提交并轮询至成功，验证鉴权、Base64 上传、状态和结果资源映射。
     */
    @Test
    public void shouldRecognizeImageThroughRealBaiduApi() {
        String accessToken = System.getenv("BAIDU_OCR_ACCESS_TOKEN");
        Assume.assumeTrue("未设置 BAIDU_OCR_ACCESS_TOKEN，跳过真实百度 OCR 测试",
            accessToken != null && !accessToken.trim().isEmpty());

        BaiduOcrConfig config = new BaiduOcrConfig();
        config.setApiKey(accessToken);
        BaiduOcrModel model = new BaiduOcrModel(config);

        OcrResponse response = model.recognizeAndWait(
            OcrRequest.ofFile(sampleImage), TIMEOUT_MILLIS, POLL_INTERVAL_MILLIS);

        assertNotNull("供应商不应返回空响应", response);
        assertFalse("真实百度 OCR 请求失败: " + response.getErrorCode() + " / " + response.getErrorMessage(),
            response.isError());
        assertEquals("真实百度 OCR 任务未成功完成", OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertNotNull("成功响应必须保留任务编号", response.getTaskId());
        assertTrue("成功结果应至少包含一个有效下载资源", hasValidResource(response));
    }

    /**
     * 判断响应中是否至少存在一个类型和 URL 都有效的下载资源。
     */
    private static boolean hasValidResource(OcrResponse response) {
        for (OcrResource resource : response.getResources()) {
            if (resource != null && hasText(resource.getType()) && hasText(resource.getUrl())) return true;
        }
        return false;
    }

    /**
     * 判断字符串是否包含非空白内容。
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
